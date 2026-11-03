package org.ntust.app.tigerduck.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.auth.NotSignedInException
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.PendingCourseIgnore
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.PushApiException
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.SyncCategory
import org.ntust.app.tigerduck.sync.SyncClock
import org.ntust.app.tigerduck.sync.SyncPolicies
import org.ntust.app.tigerduck.sync.outbox.OldEntryTime
import org.ntust.app.tigerduck.sync.outbox.OutboxChannel
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Sends one course's setting, and says whether it went and was stored; for a test that sends through one. */
internal fun interface CourseIgnoreSender {
    suspend fun send(moodleId: String, ignore: Boolean?, moodleCourseId: Int?, signIn: SignIn, madeAtMs: Long?): Boolean?
}

/**
 * What follows a change to the ignored courses beyond the write: re-arming
 * reminders. The Live Update follows every write on its own; see
 * [org.ntust.app.tigerduck.liveactivity.LiveActivityManager].
 */
internal fun interface CourseIgnoreEffects {
    suspend fun changed()
}

/**
 * Every write of the ignored courses ([CourseIgnores]) and of the changes
 * waiting to reach the backend, so one lock covers both files.
 *
 * Every change waits in `pending_course_ignores.json` from the tap until a
 * PATCH the backend confirms, and is sent again before each full sync; until
 * then it keeps this device's value through a sync. It is held, not sent,
 * while cloud, course or assignment sync is off or an assignments re-enable
 * conflict is open, so one made offline still reaches the backend later.
 * One the backend refuses [MAX_ATTEMPTS] times is parked, as the iOS outbox
 * parks it: it keeps its value, but only [retryPending]'s first run in a
 * process sends it again.
 *
 * Work started for one account carries its sign-in, and drops its writes once
 * that sign-in has ended. The queue half is [CourseIgnoreQueue]'s, the
 * outbox's course ignores channel.
 */
@Singleton
class CourseIgnoreSync internal constructor(
    private val dataCache: DataCache,
    writer: CourseIgnoreWriter,
    private val effects: CourseIgnoreEffects,
    private val sends: () -> Boolean,
    private val clock: () -> Long,
    outbox: IgnoreOutbox = IgnoreOutbox(clock, { false }, AckClock(), CallersScope(), null),
) {
    /** The sync for a test that sends through a [CourseIgnoreSender], as the queue's own tests do. */
    internal constructor(
        dataCache: DataCache,
        sender: CourseIgnoreSender,
        effects: CourseIgnoreEffects,
        sends: () -> Boolean,
        clock: () -> Long,
    ) : this(dataCache, sender.answering(), effects, sends, clock)

    @Inject constructor(
        dataCache: DataCache,
        pushApiClient: PushApiClient,
        prefs: AppPreferences,
        scheduler: AssignmentNotificationScheduler,
        policies: SyncPolicies = SyncPolicies(prefs),
        /** What a change is stamped by; null only in tests, for the wall clock. */
        syncClock: SyncClock? = null,
        ack: AckClock = AckClock(),
        @ApplicationScope scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        /** The old-entry time of a change an older build wrote with no time; null only in tests, for none. */
        oldEntryTime: OldEntryTime? = null,
    ) : this(
        dataCache = dataCache,
        writer = CourseIgnoreWriter(pushApiClient::patchCourseIgnoreAnswered),
        effects = CourseIgnoreEffects {
            if (prefs.notifyAssignments) {
                scheduler.scheduleAll(
                    dataCache.loadOutstandingAssignments(),
                    prefs.notifyAssignmentOffsets,
                )
            }
        },
        sends = { syncsCourseIgnores(policies) },
        clock = System::currentTimeMillis,
        outbox = IgnoreOutbox(
            stamp = syncClock?.let { it::stampMs } ?: System::currentTimeMillis,
            lastWriterWins = { policies.current.lastWriterWins },
            ack = ack,
            scope = scope,
            oldEntryTime = oldEntryTime,
        ),
    )

    private val mutex = Mutex()

    // When each key last changed here or was confirmed by the backend, under
    // [mutex]. A snapshot fetched before then may predate the change, so its
    // value for the key is not taken. In memory: only a sync running in this
    // process can be that old.
    private val touchedAt = HashMap<String, Long>()

    // When the user last settled a re-enable choice for the whole set, the
    // backend's or this device's, under [mutex]. A snapshot fetched before
    // then predates that choice, so [applySnapshot] takes nothing from it.
    private var setChosenAt = Long.MIN_VALUE

    // The queue half: the changes waiting, their sends and the fill-in.
    private val queue = CourseIgnoreQueue(dataCache, writer, effects, sends, clock, mutex, touchedAt, outbox)

    /** The outbox's course ignores channel: the queue half. */
    internal val channel: OutboxChannel get() = queue

    /**
     * Ignores, or stops ignoring, every assignment of [semester]'s
     * [courseNo]: here at once, then on the backend. [onWritten] runs once
     * the file has the change, or the write was dropped, before the backend
     * is asked.
     */
    suspend fun setIgnored(
        semester: String,
        courseNo: String,
        moodleId: String,
        moodleCourseId: Int?,
        ignore: Boolean,
        signIn: SignIn,
        onWritten: () -> Unit = {},
    ) = queue.setIgnored(semester, courseNo, moodleId, moodleCourseId, ignore, signIn, onWritten)

    /** Sends every change still waiting, oldest first, but a parked one; see [CourseIgnoreQueue.retryPending]. */
    suspend fun retryPending(signIn: SignIn) = queue.retryPending(signIn)

    /**
     * Keeps [ignore] as [semester]'s [courseNo]'s setting stamped 0, the oldest, unless a change of
     * the course waits (LWW D13); see [CourseIgnoreQueue.queueAsOldest]. False once [signIn] has ended.
     */
    suspend fun queueAsOldest(semester: String, courseNo: String, moodleId: String, moodleCourseId: Int?, ignore: Boolean, signIn: SignIn): Boolean =
        queue.queueAsOldest(queue.changeNow(semester, courseNo, moodleId, moodleCourseId, ignore), signIn)

    /**
     * Brings the ignored courses in line with a full sync; see
     * [CourseIgnoreReconciler.afterSync]. Courses the backend lost the
     * setting of ([CourseIgnoreReconciler.toResend]) are sent again. Returns
     * the new set when it changed, else null.
     */
    suspend fun applySnapshot(result: BackendSyncResult, signIn: SignIn): Set<String>? {
        val server = serverView(result)
        val applies = sends()
        var resend = emptyList<PendingCourseIgnore>()
        val updated = mutex.withLock {
            val local = dataCache.loadIgnoredCourses()
            val pending = queue.entries()
            val protectedKeys = pending.mapNotNullTo(mutableSetOf()) { it.key } +
                touchedAt.filterValues { it >= result.fetchedAtMs }.keys
            val takes = applies && result.fetchedAtMs >= setChosenAt
            if (takes && server != null) {
                val now = clock()
                val rows = serverRows(result)
                val lost = CourseIgnoreReconciler.toResend(local, server, protectedKeys)
                val changes = lost.sorted().map { queue.changeFor(it, true, rows[it], now) }
                if (changes.isNotEmpty() && queue.edit(signIn) { it + changes }) {
                    resend = changes
                }
            }
            val written = CourseIgnoreReconciler.afterSync(local, server, protectedKeys, takes)
                ?.takeIf { it != local && dataCache.saveIgnoredCourses(it, signIn) }
            if (takes && server != null) {
                // The rows this take saw, for the next one to tell a course
                // re-added since; last, once what it took is written, so a
                // take cut short is taken again. Not a term this device is
                // resetting or reset after the fetch: its rows are about to
                // change here, and a course of it counts as unchanged until
                // seen again.
                val reset = resetSince(result)
                dataCache.saveCourseRowsSeen(
                    CourseIgnoreReconciler.listedRows(result.serverCourses)
                        .filterKeys { CourseIgnores.semesterOf(it) !in reset },
                    signIn,
                )
            }
            written
        }
        if (updated != null) effects.changed()
        resend.forEach { queue.send(it, signIn) }
        if (server != null && applies) queue.fillInMoodleCourseIds(result.courseOverrides, signIn)
        return updated
    }

    /** Whether this device's ignored courses match [result]'s; for the re-enable check. */
    suspend fun agreesWith(result: BackendSyncResult): Boolean = CourseIgnoreReconciler.agrees(
        dataCache.loadIgnoredCourses(),
        serverView(result),
    )

    /**
     * Sends this device's set over [result]'s: what is ignored here, and an
     * end to what only the backend ignores. With no [result] to go by, only
     * the first half.
     *
     * [resetTerms] are the terms Use Local reset and uploaded again before
     * this, whose overrides the reset took off the server. A course [checked]
     * (the re-enable check's snapshot, from before the reset) shows ignored
     * there, and which the upload put back, is sent an end too: left without
     * a setting, another device that ignores it sends its own again
     * ([CourseIgnoreReconciler.toResend]), and this one would then take that
     * up, undoing Use Local. iOS sends these from the check's snapshot as
     * well (tigerduck-app-android#307).
     */
    suspend fun pushLocal(
        result: BackendSyncResult?,
        signIn: SignIn,
        resetTerms: Set<String> = emptySet(),
        checked: BackendSyncResult? = null,
    ) {
        val server = result?.let { serverView(it) }
        val serverRows = serverRows(result)
        // A term reset after [result] was fetched, as Use Local's own course
        // reset when it fell back to the re-enable check's snapshot, is left
        // out of [server], but its courses are back on the server now.
        val reset = result?.let { resetSince(it) }.orEmpty()
        val clearedInReset = clearsInResetTerms(checked, resetTerms)
        mutex.withLock {
            val local = dataCache.loadIgnoredCourses()
            val ignored = if (server == null) {
                local
            } else {
                local.filterTo(mutableSetOf()) {
                    val term = CourseIgnores.semesterOf(it)
                    term in server.semesters || term in reset
                }
            }
            val cleared = server?.keys.orEmpty() - local
            val now = clock()
            val changes = ignored.map { queue.changeFor(it, true, serverRows[it], now) } +
                cleared.map { queue.changeFor(it, false, serverRows[it], now) } +
                // By the course the upload put back, not the old row: the reset replaced it.
                (clearedInReset - local - cleared).map { queue.changeFor(it, false, null, now) }
            if (queue.edit(signIn) { pending -> changes.fold(pending) { kept, change -> kept.with(change) } }) setChosenAt = now
        }
        retryPending(signIn)
    }

    /**
     * The courses [checked] shows ignored in [resetTerms] that this device
     * caches there, visible: the ones Use Local's upload put back on the
     * server. A course it left out has no row to send an end to.
     */
    private suspend fun clearsInResetTerms(checked: BackendSyncResult?, resetTerms: Set<String>): Set<String> {
        if (checked == null || resetTerms.isEmpty()) return emptySet()
        val ignored = CourseIgnoreReconciler.serverIgnores(checked.courseOverrides, checked.serverCourses)
            ?.keys.orEmpty()
        val hiding = dataCache.loadDeletedCourseNos()
        return resetTerms.flatMapTo(mutableSetOf()) { term ->
            val hidden = CourseTombstoneKeys.hiddenIn(term, hiding)
            dataCache.loadCourses(term)
                .map { it.courseNo }
                .filter { it !in hidden }
                .map { CourseIgnores.key(term, it) }
                .filter { it in ignored }
        }
    }

    /**
     * Drops every waiting change and takes [result]'s set. Without a
     * [result], when its fetch failed, this device's set goes too and the
     * next sync brings the backend's: kept, a course ignored only here would
     * read as one the backend lost and go up again.
     */
    suspend fun adoptServer(result: BackendSyncResult?, signIn: SignIn) {
        val server = result?.let { serverView(it) }
        val updated = mutex.withLock {
            if (!queue.edit(signIn) { emptyList() }) return@withLock null
            setChosenAt = clock()
            val local = dataCache.loadIgnoredCourses()
            val adopted = if (result == null) emptySet() else CourseIgnoreReconciler.adopt(local, server)
            adopted?.takeIf { it != local && dataCache.saveIgnoredCourses(it, signIn) }
        }
        if (updated != null) effects.changed()
    }

    /**
     * Forgets the keys [matches] picks, with any change of theirs still
     * waiting: their course was deleted, or their term reset, and the
     * backend's override went with its course row. A snapshot fetched
     * before this cannot bring them back.
     */
    suspend fun forget(signIn: SignIn, matches: (String) -> Boolean) {
        val changed = mutex.withLock {
            val now = clock()
            (queue.entries().mapNotNull { it.key } + dataCache.loadIgnoredCourses())
                .filter(matches)
                .forEach { touchedAt[it] = now }
            queue.edit(signIn) { pending -> pending.filterNot { it.key?.let(matches) == true } }
            forgetRowsSeenLocked(matches, signIn)
            val keys = dataCache.loadIgnoredCourses()
            val kept = keys.filterNotTo(mutableSetOf(), matches)
            kept != keys && dataCache.saveIgnoredCourses(kept, signIn)
        }
        if (changed) effects.changed()
    }

    /**
     * Forgets the rows the last take saw in [terms], which this device just
     * reset (Use Local): their courses come back as new rows, which must not
     * read as re-added elsewhere and take this device's ignores away.
     */
    suspend fun forgetRowsSeen(terms: Set<String>, signIn: SignIn) {
        mutex.withLock { forgetRowsSeenLocked({ CourseIgnores.semesterOf(it) in terms }, signIn) }
    }

    // With [mutex] held.
    private suspend fun forgetRowsSeenLocked(matches: (String) -> Boolean, signIn: SignIn) {
        val seen = dataCache.loadCourseRowsSeen()
        val kept = seen.filterKeys { !matches(it) }
        if (kept.size != seen.size) dataCache.saveCourseRowsSeen(kept, signIn)
    }

    /**
     * [result]'s settings as this device takes them; see
     * [CourseIgnoreReconciler.serverIgnores]. A course deleted here is not
     * left out: the course list's reconcile brings back one the snapshot
     * lists, and [forget] guards a delete against older snapshots.
     */
    private suspend fun serverView(result: BackendSyncResult): CourseIgnoreReconciler.ServerIgnores? =
        CourseIgnoreReconciler.serverIgnores(
            result.courseOverrides,
            result.serverCourses,
            resetSemesters = resetSince(result),
            recreated = CourseIgnoreReconciler.recreated(
                CourseIgnoreReconciler.listedRows(result.serverCourses),
                dataCache.loadCourseRowsSeen(),
            ),
        )

    // The terms this device reset after [result] was fetched, or is resetting.
    private suspend fun resetSince(result: BackendSyncResult): Set<String> =
        dataCache.loadSemesterResetAt().filterValues { it > result.fetchedAtMs }.keys +
            dataCache.resettingSemesters()

    private fun serverRows(result: BackendSyncResult?): Map<String?, CourseOverrideResult> =
        result?.courseOverrides.orEmpty().associateBy { o ->
            val semester = o.semester ?: return@associateBy null
            val courseNo = o.courseNo ?: return@associateBy null
            CourseIgnores.key(semester, courseNo)
        }

    internal companion object {
        /** Refusals before a change is parked, as on the iOS outbox. */
        const val MAX_ATTEMPTS = 5

        /**
         * Whether changes go out and a full sync applies the backend's set:
         * cloud, assignment and course sync on, the last as the backend drops
         * the settings with the course rows once no device syncs courses, and
         * no assignments re-enable conflict open (`SyncPolicy.sendsCourseIgnores`
         * and `sends` of the assignments).
         */
        fun syncsCourseIgnores(prefs: AppPreferences): Boolean = syncsCourseIgnores(SyncPolicies(prefs))

        /** [syncsCourseIgnores] as [policies] read the switches now. */
        fun syncsCourseIgnores(policies: SyncPolicies): Boolean =
            policies.current.let { it.sendsCourseIgnores && it.sends(SyncCategory.ASSIGNMENTS) }

        /**
         * Whether a failed PATCH says the backend refused the change, as the
         * iOS outbox's `keepsWithoutAttempt` decides: no network, a session to
         * renew or none to send it with, and a server that is down or busy
         * say nothing about it.
         */
        fun refuses(e: Exception): Boolean {
            if (e is IOException || e is NotSignedInException) return false
            val code = (e as? PushApiException)?.statusCode ?: return true
            return code != 401 && code != 408 && code != 429 && code !in 500..599
        }
    }
}
