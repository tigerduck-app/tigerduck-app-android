package org.ntust.app.tigerduck.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.auth.NotSignedInException
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.PendingCourseIgnore
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.MoodleCourseIds
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.PushApiException
import org.ntust.app.tigerduck.util.logSummary
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Sends one course's setting; see [PushApiClient.patchCourseIgnore]. */
internal fun interface CourseIgnoreSender {
    suspend fun send(moodleId: String, ignore: Boolean?, moodleCourseId: Int?): Boolean?
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
 * Work started for one account carries [currentGeneration] and drops its writes
 * once the user data is wiped at sign-out.
 */
@Singleton
class CourseIgnoreSync internal constructor(
    private val dataCache: DataCache,
    private val sender: CourseIgnoreSender,
    private val effects: CourseIgnoreEffects,
    private val sends: () -> Boolean,
    private val clock: () -> Long,
) {
    @Inject constructor(
        dataCache: DataCache,
        pushApiClient: PushApiClient,
        prefs: AppPreferences,
        scheduler: AssignmentNotificationScheduler,
    ) : this(
        dataCache = dataCache,
        sender = CourseIgnoreSender(pushApiClient::patchCourseIgnore),
        effects = CourseIgnoreEffects {
            if (prefs.notifyAssignments) {
                scheduler.scheduleAll(
                    dataCache.loadOutstandingAssignments(),
                    prefs.notifyAssignmentOffsets,
                )
            }
        },
        sends = { syncsCourseIgnores(prefs) },
        clock = System::currentTimeMillis,
    )

    private val mutex = Mutex()

    // One PATCH at a time, so changes reach the backend in the order they
    // were made: the backend keeps whichever request lands last.
    private val sendMutex = Mutex()

    // When each key last changed here or was confirmed by the backend, under
    // [mutex]. A snapshot fetched before then may predate the change, so its
    // value for the key is not taken. In memory: only a sync running in this
    // process can be that old.
    private val touchedAt = HashMap<String, Long>()

    // When the user last settled a re-enable choice for the whole set, the
    // backend's or this device's, under [mutex]. A snapshot fetched before
    // then predates that choice, so [applySnapshot] takes nothing from it.
    private var setChosenAt = Long.MIN_VALUE

    // Whether this process has given the parked changes their one more try.
    private val parkedRequeued = AtomicBoolean(false)

    /** Taken before fetching a snapshot that is then passed in here. */
    fun currentGeneration(): Int = dataCache.userDataGeneration

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
        onWritten: () -> Unit = {},
    ) {
        val generation = currentGeneration()
        val change = PendingCourseIgnore(semester, courseNo, moodleId, moodleCourseId, ignore, clock())
        val key = CourseIgnores.key(semester, courseNo)
        val written = mutex.withLock {
            val keys = dataCache.loadIgnoredCourses()
            val pending = dataCache.loadPendingCourseIgnores()
            if (!dataCache.saveIgnoredCourses(if (ignore) keys + key else keys - key, generation)) {
                return@withLock false
            }
            dataCache.savePendingCourseIgnores(pending.with(change), generation)
            touchedAt[key] = change.changedAtMs
            true
        }
        onWritten()
        if (!written) return
        effects.changed()
        send(change, generation)
    }

    /**
     * Sends every change still waiting, oldest first, but a parked one. One
     * that fails waits for the next sync. The first run in a process gives
     * each parked change one more try, as every full sync on iOS does: here a
     * full sync runs before every pull and hourly in the background, so
     * trying a parked change at each would send a refused one without end.
     */
    suspend fun retryPending() {
        if (!sends()) return
        val generation = currentGeneration()
        if (parkedRequeued.compareAndSet(false, true)) requeueParked(generation)
        dataCache.loadPendingCourseIgnores()
            .filterNot { it.isParked }
            .sortedBy { it.changedAtMs }
            .forEach { send(it, generation) }
    }

    /** Leaves each parked change one attempt, as `SyncOutbox.requeueParked` on iOS does. */
    private suspend fun requeueParked(generation: Int) = mutex.withLock {
        val pending = dataCache.loadPendingCourseIgnores()
        if (pending.none { it.isParked }) return@withLock
        dataCache.savePendingCourseIgnores(
            pending.map { if (it.isParked) it.copy(attempts = MAX_ATTEMPTS - 1) else it },
            generation,
        )
    }

    /**
     * Brings the ignored courses in line with a full sync; see
     * [CourseIgnoreReconciler.afterSync]. Courses the backend lost the
     * setting of ([CourseIgnoreReconciler.toResend]) are sent again. Returns
     * the new set when it changed, else null.
     */
    suspend fun applySnapshot(result: BackendSyncResult, generation: Int): Set<String>? {
        val server = serverView(result)
        val applies = sends()
        var resend = emptyList<PendingCourseIgnore>()
        val updated = mutex.withLock {
            val local = dataCache.loadIgnoredCourses()
            val pending = dataCache.loadPendingCourseIgnores()
            val protectedKeys = pending.mapNotNullTo(mutableSetOf()) { it.key } +
                touchedAt.filterValues { it >= result.fetchedAtMs }.keys
            val takes = applies && result.fetchedAtMs >= setChosenAt
            if (takes && server != null) {
                val now = clock()
                val rows = serverRows(result)
                val lost = CourseIgnoreReconciler.toResend(local, server, protectedKeys)
                val changes = lost.sorted().map { changeFor(it, true, rows[it], now) }
                if (changes.isNotEmpty() && dataCache.savePendingCourseIgnores(pending + changes, generation)) {
                    resend = changes
                }
            }
            val written = CourseIgnoreReconciler.afterSync(local, server, protectedKeys, takes)
                ?.takeIf { it != local && dataCache.saveIgnoredCourses(it, generation) }
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
                    generation,
                )
            }
            written
        }
        if (updated != null) effects.changed()
        resend.forEach { send(it, generation) }
        if (server != null && applies) fillInMoodleCourseIds(result.courseOverrides, generation)
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
        generation: Int,
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
            var pending = dataCache.loadPendingCourseIgnores()
            for (key in ignored) pending = pending.with(changeFor(key, true, serverRows[key], now))
            for (key in cleared) pending = pending.with(changeFor(key, false, serverRows[key], now))
            // By the course the upload put back, not the old row: the reset
            // replaced it.
            for (key in clearedInReset - local - cleared) pending = pending.with(changeFor(key, false, null, now))
            if (dataCache.savePendingCourseIgnores(pending, generation)) setChosenAt = now
        }
        retryPending()
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
    suspend fun adoptServer(result: BackendSyncResult?, generation: Int) {
        val server = result?.let { serverView(it) }
        val updated = mutex.withLock {
            if (!dataCache.savePendingCourseIgnores(emptyList(), generation)) return@withLock null
            setChosenAt = clock()
            val local = dataCache.loadIgnoredCourses()
            val adopted = if (result == null) emptySet() else CourseIgnoreReconciler.adopt(local, server)
            adopted?.takeIf { it != local && dataCache.saveIgnoredCourses(it, generation) }
        }
        if (updated != null) effects.changed()
    }

    /**
     * Forgets the keys [matches] picks, with any change of theirs still
     * waiting: their course was deleted, or their term reset, and the
     * backend's override went with its course row. A snapshot fetched
     * before this cannot bring them back.
     */
    suspend fun forget(matches: (String) -> Boolean) {
        val generation = currentGeneration()
        val changed = mutex.withLock {
            val pending = dataCache.loadPendingCourseIgnores()
            val now = clock()
            (pending.mapNotNull { it.key } + dataCache.loadIgnoredCourses())
                .filter(matches)
                .forEach { touchedAt[it] = now }
            val keptPending = pending.filterNot { it.key?.let(matches) == true }
            if (keptPending.size != pending.size) {
                dataCache.savePendingCourseIgnores(keptPending, generation)
            }
            forgetRowsSeenLocked(matches, generation)
            val keys = dataCache.loadIgnoredCourses()
            val kept = keys.filterNotTo(mutableSetOf(), matches)
            kept != keys && dataCache.saveIgnoredCourses(kept, generation)
        }
        if (changed) effects.changed()
    }

    /**
     * Forgets the rows the last take saw in [terms], which this device just
     * reset (Use Local): their courses come back as new rows, which must not
     * read as re-added elsewhere and take this device's ignores away.
     */
    suspend fun forgetRowsSeen(terms: Set<String>) {
        val generation = currentGeneration()
        mutex.withLock { forgetRowsSeenLocked({ CourseIgnores.semesterOf(it) in terms }, generation) }
    }

    // With [mutex] held.
    private suspend fun forgetRowsSeenLocked(matches: (String) -> Boolean, generation: Int) {
        val seen = dataCache.loadCourseRowsSeen()
        val kept = seen.filterKeys { !matches(it) }
        if (kept.size != seen.size) dataCache.saveCourseRowsSeen(kept, generation)
    }

    private suspend fun send(change: PendingCourseIgnore, generation: Int) = sendMutex.withLock {
        if (!sends()) return@withLock
        val moodleId = change.moodleId ?: return@withLock
        // Gone from the file: confirmed already, replaced by a later change,
        // or wiped with the account that made it. Sending it would undo
        // whatever replaced it. A parked one waits for its one more try.
        val current = mutex.withLock {
            generation == currentGeneration() &&
                dataCache.loadPendingCourseIgnores().any { it.isSameChange(change) && !it.isParked }
        }
        if (!current) return@withLock
        val stored = try {
            sender.send(moodleId, change.ignore, change.moodleCourseId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "course ignore PATCH failed: ${e.logSummary()}")
            if (refuses(e)) false else null
        }
        when (stored) {
            true -> settle(change, generation)
            false -> countRefusal(change, generation)
            null -> Unit
        }
    }

    private suspend fun settle(change: PendingCourseIgnore, generation: Int) = mutex.withLock {
        val pending = dataCache.loadPendingCourseIgnores()
        if (pending.none { it.isSameChange(change) }) return@withLock
        if (dataCache.savePendingCourseIgnores(pending.filterNot { it.isSameChange(change) }, generation)) {
            change.key?.let { touchedAt[it] = clock() }
        }
    }

    private suspend fun countRefusal(change: PendingCourseIgnore, generation: Int) = mutex.withLock {
        val pending = dataCache.loadPendingCourseIgnores()
        val stored = pending.find { it.isSameChange(change) } ?: return@withLock
        val counted = stored.copy(attempts = stored.attempts + 1)
        if (counted.isParked) Log.w(TAG, "course ignore parked after $MAX_ATTEMPTS refusals")
        dataCache.savePendingCourseIgnores(pending.map { if (it === stored) counted else it }, generation)
    }

    /**
     * A course the backend ignores but cannot match to the assignments it
     * fetches itself, for want of the numeric Moodle id, gets the id from
     * this device when it knows it.
     */
    private suspend fun fillInMoodleCourseIds(overrides: List<CourseOverrideResult>, generation: Int) {
        val missing = overrides.filter { it.ignoreAssignments == true && it.moodleNumericCourseId == null }
        if (missing.isEmpty()) return
        val known = dataCache.loadMoodleCourseIds()
            .mapKeys { MoodleCourseIds.normalizedIdnumber(it.key) }
        for (o in missing) {
            val semester = o.semester ?: continue
            val courseNo = o.courseNo ?: continue
            val numericId = dataCache.loadCourses(semester)
                .find { it.courseNo == courseNo }
                ?.moodleNumericCourseId
                ?: known[MoodleCourseIds.normalizedIdnumber(o.moodleCourseId)]
                ?: continue
            sendMutex.withLock {
                if (generation != currentGeneration()) return
                try {
                    sender.send(o.moodleCourseId, null, numericId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "moodle_course_id fill-in failed for $semester: ${e.logSummary()}")
                }
            }
        }
    }

    private suspend fun changeFor(
        key: String,
        ignore: Boolean,
        serverRow: CourseOverrideResult?,
        now: Long,
    ): PendingCourseIgnore {
        val semester = CourseIgnores.semesterOf(key)
        val courseNo = key.substringAfter(':')
        val course = dataCache.loadCourses(semester).find { it.courseNo == courseNo }
        val moodleId = serverRow?.moodleCourseId
            ?: course?.moodleIdNumber?.takeIf { it.isNotEmpty() }
            ?: "$semester$courseNo"
        return PendingCourseIgnore(
            semester = semester,
            courseNo = courseNo,
            moodleId = moodleId,
            moodleCourseId = serverRow?.moodleNumericCourseId ?: course?.moodleNumericCourseId,
            ignore = ignore,
            changedAtMs = now,
        )
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

    private fun List<PendingCourseIgnore>.with(change: PendingCourseIgnore) =
        filterNot { it.key == change.key } + change

    private val PendingCourseIgnore.isParked: Boolean
        get() = attempts >= MAX_ATTEMPTS

    internal companion object {
        private const val TAG = "CourseIgnoreSync"

        /** The re-enable conflict category course ignores are part of. */
        private const val REENABLE_CATEGORY = "assignments"

        /** Refusals before a change is parked, as on the iOS outbox. */
        const val MAX_ATTEMPTS = 5

        /**
         * Whether changes go out and a full sync applies the backend's set:
         * cloud, assignment and course sync on, the last as the backend drops
         * the settings with the course rows once no device syncs courses, and
         * no assignments re-enable conflict open.
         */
        fun syncsCourseIgnores(prefs: AppPreferences): Boolean =
            prefs.cloudSyncEnabled && prefs.syncAssignments && prefs.syncCourses &&
                REENABLE_CATEGORY !in prefs.pendingConflictCategories

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
