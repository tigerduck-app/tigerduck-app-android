package org.ntust.app.tigerduck.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.PendingCourseOverride
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.PushApiException
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends one course's colour, or its name in one locale, as sign-in [signIn]; see
 * [PushApiClient.patchCourseOverride]. [stillAllowed] is read once more right before the request
 * goes, after whatever suspends in between.
 */
internal fun interface CourseOverrideSender {
    suspend fun send(
        moodleId: String,
        colorHex: String?,
        customName: String?,
        locale: String?,
        signIn: Int,
        stillAllowed: () -> Boolean,
    ): Boolean
}

/**
 * Colours and names set on the class table that the backend has not
 * confirmed, by course and, for a name, locale.
 *
 * A change went up once. Failed, as it does offline, it was only logged: the
 * colour or name stayed on this phone alone, never on another device, and
 * lost at sign-out (tigerduck-app-android#380). Now the change waits on disk,
 * kept before the request so a process killed during it keeps it too, and
 * every full sync sends what waits first, as [PendingCourseAdds] does for
 * hand-added courses. Every colour and name PATCH goes through here — a
 * reset of every colour, a sync's moved colours, Use Local's — so a later
 * change of the same colour, or of the same name, replaces the one waiting
 * and the older never lands over it. Each change carries a version that only
 * grows ([PendingCourseOverride.seq]); a batch ([queue]) is written whole at
 * a version reserved before its data was read, so a change made while the
 * batch is read or goes up one by one is the newer, landed already or not,
 * and is not replaced when the batch's own reaches its turn. A change goes
 * once the backend confirms it, once the
 * backend has refused it [MAX_ATTEMPTS] times, or at once on a 404 for a
 * course no longer cached in the term it was made in: the row went with the
 * course. Account-scoped: sent only as the account that made it, and wiped
 * with the user data at sign-out.
 *
 * With TigerSync or the kind's switch off ([records] false) colours or names
 * are this phone's own: a change goes nowhere and is not kept, but replaces
 * one waiting for the same key, as a mark does in [UnsentMarks]. One already
 * waiting when the switch goes off is kept and not sent, and so is every one
 * while the kind's re-enable check waits for the user ([sends] false): the
 * answer decides them. Use Server drops them ([adoptServer]); Use Local
 * queues its own and sends them once the hold is lifted. The hold is read
 * right before each request, and once more by the sender after the header is
 * made: the checks before suspend, and sync switched off and on again
 * meanwhile puts the kind on hold, which the switch alone would let through.
 *
 * A snapshot fetched before a change, or before its PATCH landed, still has
 * the old value; [held] names what a sync must not take from it.
 */
@Singleton
class PendingCourseOverrides internal constructor(
    private val dataCache: DataCache,
    private val sender: CourseOverrideSender,
    private val records: (color: Boolean) -> Boolean,
    private val sends: (color: Boolean) -> Boolean,
    private val signInGeneration: () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(
        dataCache: DataCache,
        pushApiClient: PushApiClient,
        prefs: AppPreferences,
        authTokenManager: AuthTokenManager,
    ) : this(
        dataCache = dataCache,
        sender = CourseOverrideSender { moodleId, colorHex, customName, locale, signIn, stillAllowed ->
            pushApiClient.patchCourseOverride(
                moodleId, colorHex = colorHex, customName = customName, locale = locale, signIn = signIn, allowed = stillAllowed,
            )
        },
        records = { color -> records(prefs, color) },
        sends = { color -> sends(prefs, color) },
        signInGeneration = { authTokenManager.signInGeneration },
    )

    /**
     * One change for [queue]: the colour [colorHex], or the name [customName]
     * in [locale], of the course the backend knows as [moodleId]. [semester]
     * and [courseNo] say where this phone holds the course, when known; see
     * [PendingCourseOverride.semester].
     */
    data class Change(
        val moodleId: String,
        val colorHex: String? = null,
        val customName: String? = null,
        val locale: String? = null,
        val semester: String? = null,
        val courseNo: String? = null,
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

    // The last version given out, under [mutex]; see [nextSeq].
    private var lastSeq = 0L

    // The version last recorded under each key, under [mutex], kept past the
    // entry's send: a batch reserved before a change that has since landed
    // is still the older, and the file no longer says so. In memory, as a
    // reservation is: neither outlives the process.
    private val latestSeq = HashMap<String, Long>()

    /** Whether a colour or name set here has yet to reach the backend: data a sign-out would lose. */
    suspend fun hasPending(): Boolean = waiting().isNotEmpty()

    /**
     * Keeps one change (see [Change] for the arguments), in place of any
     * older change of the same, and sends it unless its kind's re-enable
     * check is pending. Called in the order the changes were made; nothing
     * to do without a colour or a name.
     */
    suspend fun send(
        moodleId: String,
        colorHex: String? = null,
        customName: String? = null,
        locale: String? = null,
        semester: String? = null,
        courseNo: String? = null,
    ) {
        val generation = dataCache.writeGeneration()
        val signIn = signInGeneration()
        val recorded = record(listOf(Change(moodleId, colorHex, customName, locale, semester, courseNo)), generation)
        for ((key, change) in recorded) {
            if (sends(change.isColor)) trySend(key, change, generation, signIn)
        }
    }

    /**
     * Keeps [changes], each in place of an older change of the same, all at
     * [version] (from [reserveVersion], taken before the data they were read
     * from), and sends none: [retry] does, once the caller has saved what
     * they stand for. A reset of every colour goes through here before it
     * saves the colours, so a pick made while they are read or go up one by
     * one is the newer, and the reset's own colour for that course, reaching
     * its turn, neither replaces it nor goes up.
     */
    suspend fun queue(changes: List<Change>, version: Long) {
        if (changes.isEmpty()) return
        record(changes, dataCache.writeGeneration(), version)
    }

    /**
     * A version for a batch whose changes are yet to be read, taken before
     * the read: an edit recorded while the read completes gets a later one,
     * and [queue]'s batch, built from data that predates the edit, neither
     * replaces it nor goes up over it. In memory only: a reservation lives in
     * its caller and does not outlive the process.
     */
    suspend fun reserveVersion(): Long = mutex.withLock { nextSeq(waiting()) }

    /**
     * Sends every change still waiting whose kind goes out; each full sync
     * starts with this, so the snapshot it fetches has them.
     */
    suspend fun retry() {
        val generation = dataCache.writeGeneration()
        val signIn = signInGeneration()
        for ((key, change) in waiting()) {
            if (sends(change.isColor)) trySend(key, change, generation, signIn)
        }
    }

    /**
     * The keys ([PendingCourseOverride.key]) a snapshot fetched at
     * [fetchedAtMs] must not overwrite: every change still waiting, and
     * every one made or confirmed since the fetch, which the snapshot
     * predates.
     */
    suspend fun held(fetchedAtMs: Long): Set<String> = mutex.withLock {
        waiting().keys + touchedAt.filterValues { it >= fetchedAtMs }.keys
    }

    /**
     * Forgets what waits for the courses [moodleIds]: deleted by hand or on
     * another device, or their term reset, and the backend's override went
     * with the course row. Sent later, a change would set its colour or name
     * on the row a re-add or an upload makes anew, and until then be refused
     * at every sync. Only the changes older than [madeBefore], a version
     * [reserveVersion] gave before the course went: one made since is the
     * course's put back meanwhile, and stays.
     */
    suspend fun forget(moodleIds: Collection<String>, madeBefore: Long = Long.MAX_VALUE) {
        if (moodleIds.isEmpty()) return
        val generation = dataCache.writeGeneration()
        mutex.withLock {
            val waiting = waiting()
            val kept = waiting.filterValues { it.moodleId !in moodleIds || it.seq >= madeBefore }
            if (kept.size != waiting.size) save(kept, generation)
        }
    }

    /**
     * Drops the changes of the kinds in [categories] (`course_colors`,
     * `course_names`; others are ignored), the user having taken the
     * server's over this phone's at their re-enable check: sent later, they
     * would put this phone's back.
     */
    suspend fun adoptServer(categories: Collection<String>) {
        val generation = dataCache.writeGeneration()
        mutex.withLock {
            val waiting = waiting()
            val kept = waiting.filterValues { category(it.isColor) !in categories }
            if (kept.size == waiting.size) return@withLock
            if (save(kept, generation)) (waiting.keys - kept.keys).forEach(touchedAt::remove)
        }
    }

    /**
     * Writes [changes] at [version], or at a new one, each in place of the
     * change waiting under its key unless a newer one was recorded under it,
     * waiting still or landed since: made after the version was reserved,
     * from data the batch predates, so it stands. A change of a kind that
     * does not sync is this phone's own: not kept, and the last word on its
     * key, as one still waiting would go up over it later. Returns the ones
     * now waiting, by key; nothing is written once the data was wiped since
     * [generation].
     */
    private suspend fun record(
        changes: List<Change>,
        generation: Int,
        version: Long? = null,
    ): Map<String, PendingCourseOverride> =
        mutex.withLock {
            val waiting = waiting()
            val seq = version ?: nextSeq(waiting)
            val now = clock()
            val updated = LinkedHashMap(waiting)
            val recorded = LinkedHashMap<String, PendingCourseOverride>()
            for (c in changes) {
                val entry = PendingCourseOverride(
                    c.moodleId, c.colorHex, c.customName, c.locale, now, c.semester, c.courseNo, seq = seq,
                )
                val key = entry.key ?: continue
                if (maxOf(waiting[key]?.seq ?: 0L, latestSeq[key] ?: 0L) > seq) continue
                if (!records(entry.isColor)) {
                    updated.remove(key)
                    latestSeq[key] = seq
                    continue
                }
                updated[key] = entry
                recorded[key] = entry
            }
            if (updated == waiting) return@withLock emptyMap()
            if (!save(updated, generation)) return@withLock emptyMap()
            for (key in recorded.keys) {
                touchedAt[key] = now
                latestSeq[key] = seq
            }
            recorded
        }

    // Under [mutex]: later than every version an entry still waiting carries
    // and than every one given out in this process, reservations included.
    // A version is only ever compared with the entries waiting when it is
    // used, and a reservation does not outlive the process, so it need not
    // keep growing across restarts beyond theirs: the clock where it is
    // ahead, else one past the last.
    private fun nextSeq(waiting: Map<String, PendingCourseOverride>): Long {
        val floor = maxOf(lastSeq, waiting.values.maxOfOrNull { it.seq } ?: 0L)
        lastSeq = maxOf(clock(), floor + 1)
        return lastSeq
    }

    /**
     * Sends [change] if it is still the change waiting under [key], for the
     * account whose data [generation] and sign-in [signIn] were read with.
     * Checked under the send lock, right before the request: a newer change
     * of the key, a send of it that landed meanwhile, a sign-out, or a hold
     * put on its kind since each make it stale, and sent it would undo the
     * newer change, repeat the request, land on the next account, or pre-empt
     * the user's answer. The hold and the account are read after the check
     * that suspends for the file, and once more by the sender right before
     * the request. The request itself carries [signIn], so a sign-out after
     * the check sends it without a token rather than with the next account's.
     * Let go once confirmed, unless a newer change replaced it meanwhile;
     * kept when nothing went out, for the switch's re-enable check to decide.
     */
    private suspend fun trySend(key: String, change: PendingCourseOverride, generation: Int, signIn: Int) {
        val moodleId = change.moodleId ?: return
        val allowed = {
            sends(change.isColor) && generation == dataCache.userDataGeneration && signIn == signInGeneration()
        }
        val sent = sendMutex.withLock {
            val current = mutex.withLock { isWaiting(key, change) } && allowed()
            if (!current) return
            try {
                sender.send(moodleId, change.colorHex, change.customName, change.locale, signIn, allowed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "course override PATCH failed, kept for the next sync: ${e.logSummary()}")
                if (CourseIgnoreSync.refuses(e)) countRefusal(key, change, generation, gone = courseGone(e, change))
                return
            }
        }
        if (!sent) return
        mutex.withLock {
            val waiting = waiting()
            if (!isWaiting(key, change)) return@withLock
            if (save(waiting - key, generation)) touchedAt[key] = clock()
        }
    }

    /**
     * A refusal the backend gave [change] (see [CourseIgnoreSync.refuses]),
     * counted; the change is let go at [MAX_ATTEMPTS], or at once when its
     * course is [gone]: the backend will not take it, and kept it would be
     * sent, and refused, at every sync, and count as unsynced data for good.
     */
    private suspend fun countRefusal(key: String, change: PendingCourseOverride, generation: Int, gone: Boolean) =
        mutex.withLock {
            val waiting = waiting()
            val stored = waiting[key]?.takeIf { it.isSameChange(change) } ?: return@withLock
            val counted = stored.copy(attempts = stored.attempts + 1)
            if (gone || counted.attempts >= MAX_ATTEMPTS) {
                val why = if (gone) "its course has left this phone" else "refused $MAX_ATTEMPTS times"
                Log.w(TAG, "course override let go: $why")
                save(waiting - key, generation)
            } else {
                save(waiting + (key to counted), generation)
            }
        }

    // Whether [e] is a 404 for a course no longer cached in the term [change]
    // was made in: deleted on another device, with the backend's row, and
    // pruned here by a sync. Not known without the term.
    private suspend fun courseGone(e: Exception, change: PendingCourseOverride): Boolean {
        if ((e as? PushApiException)?.statusCode != 404) return false
        val semester = change.semester ?: return false
        val courseNo = change.courseNo ?: return false
        return dataCache.loadCourses(semester).none { it.courseNo == courseNo }
    }

    private suspend fun isWaiting(key: String, change: PendingCourseOverride): Boolean =
        waiting()[key]?.isSameChange(change) == true

    // By key, in the order the changes were made.
    private suspend fun waiting(): Map<String, PendingCourseOverride> =
        dataCache.loadPendingCourseOverrides().associateByTo(LinkedHashMap()) { it.key.orEmpty() }

    private suspend fun save(waiting: Map<String, PendingCourseOverride>, generation: Int): Boolean =
        dataCache.savePendingCourseOverrides(waiting.values.toList(), generation)

    private val PendingCourseOverride.isColor: Boolean
        get() = colorHex != null

    companion object {
        private const val TAG = "PendingCourseOverrides"

        /** Refusals before a change is let go; the marks and course ignores park after as many. */
        const val MAX_ATTEMPTS = 5

        /** The re-enable conflict category colours ([color]) or names belong to. */
        fun category(color: Boolean): String = if (color) "course_colors" else "course_names"

        /** Whether colours ([color]) or names sync at all: TigerSync and their switch on. */
        fun records(prefs: AppPreferences, color: Boolean): Boolean =
            prefs.cloudSyncEnabled && if (color) prefs.syncCourseColors else prefs.syncCourseNames

        /** Whether they go out: they sync, and no re-enable check of theirs is pending. */
        fun sends(prefs: AppPreferences, color: Boolean): Boolean =
            records(prefs, color) && category(color) !in prefs.pendingConflictCategories
    }
}
