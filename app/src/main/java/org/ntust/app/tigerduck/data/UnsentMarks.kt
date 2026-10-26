package org.ntust.app.tigerduck.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.UnsentMark
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.util.logSummary
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Sends one assignment's mark; see [PushApiClient.patchAssignmentOverride]. */
internal fun interface MarkSender {
    suspend fun send(assignmentId: Int, status: String, signIn: Int)
}

/**
 * Done and ignored marks tapped here that the backend has not confirmed, by
 * assignment id, with the status the tap set.
 *
 * A failed PATCH used to be only logged. Once the account's marks had been
 * through a sync here, the next one took the server — which never got the
 * mark — as the truth and wiped it (tigerduck-app-android#155 made an empty
 * server the truth). Now the mark waits on disk: a full sync keeps this
 * phone's value for it and sends it again first, until it lands. The iPhone
 * app's outbox does the same.
 *
 * With the Assignments switch or TigerSync off ([records] false) the marks
 * are this phone's own: a tap goes nowhere and is not kept, but replaces a
 * mark waiting for the same assignment. While the assignments re-enable
 * check is pending ([sends] false) a tap is kept but not sent, as the user's
 * answer decides the marks. What waits goes out once marks are sent again,
 * or is dropped when the server's marks are taken over this phone's
 * ([forget]). Account-scoped: wiped with the user data at sign-out.
 */
@Singleton
class UnsentMarks internal constructor(
    private val dataCache: DataCache,
    private val sender: MarkSender,
    private val records: () -> Boolean,
    private val sends: () -> Boolean,
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
        sender = MarkSender(pushApiClient::patchAssignmentOverride),
        records = { recordsMarks(prefs) },
        sends = { sendsMarks(prefs) },
        signInGeneration = { authTokenManager.signInGeneration },
    )

    private val mutex = Mutex()

    // Whether this process has given the parked marks their one more try.
    private val parkedRequeued = AtomicBoolean(false)

    // One PATCH at a time, so marks reach the backend in the order they were
    // made: the backend keeps whichever request lands last.
    private val sendMutex = Mutex()

    /** The ids whose last tap has not reached the server; a sync keeps this phone's value for them. */
    suspend fun ids(): Set<String> = waiting().keys

    /** The status each of those taps set, by assignment id. */
    suspend fun statuses(): Map<String, String> =
        waiting().mapNotNull { (id, mark) -> mark.status?.let { id to it } }.toMap()

    /**
     * A tap that set [assignmentId] to [status]: kept until the server has
     * it, and sent at once unless the re-enable check holds the marks. Made
     * while marks do not sync, it is this phone's own and goes nowhere, but
     * it replaces a mark still waiting for the same assignment, which would
     * otherwise go up later over it.
     */
    suspend fun send(assignmentId: String, status: String) {
        val generation = dataCache.writeGeneration()
        val signIn = signInGeneration()
        if (!records()) {
            mutex.withLock {
                val waiting = waiting()
                if (assignmentId in waiting) save(waiting - assignmentId, generation)
            }
            return
        }
        val kept = mutex.withLock {
            save(waiting() + (assignmentId to UnsentMark(assignmentId, status)), generation)
        }
        if (kept && sends()) trySend(assignmentId, status, generation, signIn)
    }

    /**
     * Keeps a mark Use Local could not send, whatever [sends] says: while the
     * answer is applied the re-enable check still holds marks, and dropped,
     * the mark would be lost to the next sync. Written only while the data is
     * still [generation]'s, and never over a mark already waiting for the
     * assignment: that is a tap made during the hold, newer than what Use
     * Local read.
     */
    suspend fun keep(assignmentId: String, status: String, generation: Int) {
        mutex.withLock {
            val waiting = waiting()
            if (assignmentId in waiting) return@withLock
            save(waiting + (assignmentId to UnsentMark(assignmentId, status)), generation)
        }
    }

    /** Sends every mark still waiting; each full sync starts with this. */
    suspend fun retry() {
        if (!sends()) return
        val generation = dataCache.writeGeneration()
        val signIn = signInGeneration()
        requeueParked(generation, firstInProcess = parkedRequeued.compareAndSet(false, true))
        for ((id, mark) in waiting()) {
            if (mark.attempts >= MAX_ATTEMPTS) continue
            trySend(id, mark.status ?: continue, generation, signIn)
        }
    }

    /** The server's marks were taken over this phone's, so what waited is no longer wanted. */
    suspend fun forget() {
        mutex.withLock { save(emptyMap(), null) }
    }

    /**
     * Sends [id]'s [status] if it is still the mark waiting for it, for the
     * account whose data [generation] and sign-in [signIn] were read with.
     * Checked under the send lock, right before the request: a newer tap of
     * the id, a send of it that landed meanwhile, or a sign-out each make it
     * stale, and sent it would undo the newer mark, repeat the request, or
     * land on the next account. The request itself carries [signIn], so a
     * sign-out after the check sends it without a token rather than with the
     * next account's.
     */
    private suspend fun trySend(id: String, status: String, generation: Int, signIn: Int) {
        val intId = id.toIntOrNull()
        val sent = sendMutex.withLock {
            val current = sends() && mutex.withLock {
                generation == dataCache.userDataGeneration && waiting()[id]?.status == status
            }
            if (!current) return
            if (intId == null) return@withLock true
            try {
                sender.send(intId, status, signIn)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "mark PATCH failed, kept for the next sync: ${e.logSummary()}")
                if (CourseIgnoreSync.refuses(e)) countRefusal(id, status, generation)
                false
            }
        }
        if (!sent) return
        // Only if no newer tap of the id replaced it meanwhile.
        mutex.withLock {
            val waiting = waiting()
            if (waiting[id]?.status == status) save(waiting - id, generation)
        }
    }

    // A mark the backend refused, as a 404 for an assignment it has no row
    // for, is parked after MAX_ATTEMPTS: still kept at this phone's value,
    // but no longer sent on every sync.
    private suspend fun countRefusal(id: String, status: String, generation: Int) = mutex.withLock {
        val waiting = waiting()
        val mark = waiting[id]?.takeIf { it.status == status } ?: return@withLock
        val counted = mark.copy(attempts = mark.attempts + 1, lastRefusedAtMs = clock())
        if (counted.attempts >= MAX_ATTEMPTS) Log.w(TAG, "mark parked after $MAX_ATTEMPTS refusals")
        save(waiting + (id to counted), generation)
    }

    // A parked mark gets one more try per process, and again once
    // PARKED_RETRY_MS has passed since its last refusal: the backend creates
    // an assignment's row in its own Moodle fetch, every eight hours, and the
    // app may stay open far longer than that.
    private suspend fun requeueParked(generation: Int, firstInProcess: Boolean) = mutex.withLock {
        val waiting = waiting()
        val now = clock()
        fun due(mark: UnsentMark) = mark.attempts >= MAX_ATTEMPTS &&
            (firstInProcess || now - mark.lastRefusedAtMs !in 0 until PARKED_RETRY_MS)
        if (waiting.values.none(::due)) return@withLock
        save(waiting.mapValues { (_, mark) -> if (due(mark)) mark.copy(attempts = MAX_ATTEMPTS - 1) else mark }, generation)
    }

    // By assignment id, in the order the marks were made.
    private suspend fun waiting(): Map<String, UnsentMark> =
        dataCache.loadUnsentMarks().associateByTo(LinkedHashMap()) { it.assignmentId.orEmpty() }

    private suspend fun save(waiting: Map<String, UnsentMark>, generation: Int?): Boolean =
        dataCache.saveUnsentMarks(waiting.values.toList(), generation)

    companion object {
        private const val TAG = "UnsentMarks"

        /** Refusals before a mark is parked, as on the iOS outbox. */
        const val MAX_ATTEMPTS = 5

        /** How long a parked mark waits for its next try in a process that stays alive. */
        const val PARKED_RETRY_MS = 8 * 60 * 60 * 1000L

        /** The re-enable conflict category the marks belong to. */
        private const val REENABLE_CATEGORY = "assignments"

        /** Whether marks sync at all: cloud and assignment sync on. */
        fun recordsMarks(prefs: AppPreferences): Boolean = prefs.cloudSyncEnabled && prefs.syncAssignments

        /** Whether marks go out: they sync, and no assignments re-enable check is pending. */
        fun sendsMarks(prefs: AppPreferences): Boolean =
            recordsMarks(prefs) && REENABLE_CATEGORY !in prefs.pendingConflictCategories
    }
}
