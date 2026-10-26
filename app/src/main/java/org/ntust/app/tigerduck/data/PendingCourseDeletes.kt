package org.ntust.app.tigerduck.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.util.logSummary
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Deletes one course's row from the backend by its key, as sign-in [signIn]; see [PushApiClient.deleteCourse]. */
internal fun interface CourseDeleter {
    suspend fun delete(courseKey: String, signIn: Int)
}

/**
 * Courses deleted on the class table whose backend DELETE has not landed,
 * by term, each with the moment it was asked ([stamp]).
 *
 * A delete went once. A course taken back out of Add course
 * (`ClassTableViewModel.removeAddedCourse`) writes no tombstone here, so a
 * failed DELETE left its row on the server for the next full sync to merge
 * back as a hand-added course; a regular delete's tombstone did not help
 * either, as the reconcile un-hides a course the server still lists. Now the
 * delete waits on disk, kept before anything else so a process killed while
 * it waits for the lock or during the request keeps it too, every full sync
 * sends it again first, as [PendingCourseAdds] does for adds, and the
 * reconcile keeps the course out of the term until it lands
 * (`CourseSyncReconciler.reconcileSemester`'s `graceCourseNos`). It goes
 * once the DELETE lands, once the course is added back (the add's force key
 * would otherwise lose to a DELETE landing after it), once its term is
 * reset, which deletes the term's rows itself, or once a full sync reads a
 * course reset made on another device, which brought the course back
 * ([forgetAskedBefore]). Account-scoped: kept with the
 * user data and wiped with it at sign-out, and each request pinned to the
 * sign-in it was read under, as the adds are, so a sign-out and another
 * sign-in while it waits for its header cannot send it as the next account.
 *
 * Every request holds the lock the adds and term resets take,
 * [PendingCourseAdds.removing], and reads the delete again under it right
 * before sending: a retry that read a delete, then waited behind a re-add's
 * upload or a reset, must not take the restored course off the server
 * afterwards. So the re-add's [forget] takes that lock too, and a reset
 * calls [forgetTerm] inside the lock its wipe holds. A re-add and a removal
 * compare by when each was asked, not by when each reached the lock: [forget]
 * lets go only of a delete asked before the re-add, so a course removed again
 * while its first DELETE is still under way stays queued once that DELETE
 * fails, and so a delete can be kept on disk before its wait for the lock.
 * A new action outranks every delete already queued, a process restart and a
 * clock set back included: the stamps start above the largest one kept.
 *
 * The iPhone's `CloudSyncCoordinator` queues its course deletes in its outbox
 * the same way, hides them with `pendingCourseDeleteKeys` meanwhile and drops
 * them with `discardCourseDeletes` on a re-add or a reset.
 */
@Singleton
class PendingCourseDeletes internal constructor(
    private val dataCache: DataCache,
    private val pendingCourseAdds: PendingCourseAdds,
    private val deleter: CourseDeleter,
    private val signInGeneration: () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
    // The file's read; a test holds it to order two callers around it.
    private val load: suspend () -> Map<String, Map<String, Long>> = dataCache::loadPendingCourseDeletes,
) {
    @Inject constructor(
        dataCache: DataCache,
        pendingCourseAdds: PendingCourseAdds,
        pushApiClient: PushApiClient,
        authTokenManager: AuthTokenManager,
    ) : this(
        dataCache = dataCache,
        pendingCourseAdds = pendingCourseAdds,
        deleter = CourseDeleter(pushApiClient::deleteCourse),
        signInGeneration = { authTokenManager.signInGeneration },
    )

    // The file's lock: every read that a write follows, and the stamps' seed.
    private val mutex = Mutex()

    private val lastStamp = AtomicLong(0)

    // Whether [lastStamp] has been raised above every stamp the file held when
    // this process first needed one; read and set under [mutex].
    private var seeded = false

    // Every delete asked before this a course reset let go of ([forgetAskedBefore]),
    // so one asked before it and kept only after is not kept; read and set under [mutex].
    private var forgottenBefore = Long.MIN_VALUE

    /**
     * The moment of a class-table action, for [send] and [forget]: the clock,
     * and later than every earlier one's whatever the clock does, the deletes
     * still queued from before this process included. Take it as the user
     * acts, before the work that follows suspends, so a re-add and a removal
     * compare by the order they were asked in, not by when each reached the
     * lock behind a slow request. Waits for the file's lock, the first time
     * to read the stamps already kept, and is taken before that lock goes:
     * the lock hands over in the order asked, so a second stamp asked while
     * the first reads gets the later one, even when it resumes inline as the
     * lock goes, as on the main thread's immediate dispatcher.
     */
    suspend fun stamp(): Long = mutex.withLock {
        if (!seeded) {
            noteStamps(waiting())
            seeded = true
        }
        lastStamp.updateAndGet { last -> maxOf(last + 1, clock()) }
    }

    /**
     * Keeps the delete of [courseNo] in [semester], asked at [queuedAt], on
     * disk without sending it, for [send] or the next full sync to send: the
     * class table keeps it before its hide is marked, so a course selection
     * answer never finds the mark without the delete
     * (`DataCache.hideDeletedCourse`). False once the data was wiped since.
     */
    suspend fun keep(semester: String, courseNo: String, queuedAt: Long): Boolean =
        edit(dataCache.writeGeneration()) { it.with(semester, courseNo, queuedAt) }

    /**
     * Sends the delete of [courseNo] in [semester] that [keep] kept, without
     * keeping it again: a full sync's retry may have sent it since, and a
     * course selection answer brought the course back with the roster after
     * it landed, which a second DELETE would take off the server again. Sent
     * only while it is still queued, as [retry] sends. Takes
     * [PendingCourseAdds.removing] itself, so call it outside that lock.
     */
    suspend fun sendKept(semester: String, courseNo: String) {
        val generation = dataCache.writeGeneration()
        val signIn = signInGeneration()
        pendingCourseAdds.removing { sendHeld(semester, courseNo, generation, signIn) }
    }

    /** [send] with the delete asked now. */
    suspend fun send(semester: String, courseNo: String) = send(semester, courseNo, stamp())

    /**
     * Deletes [courseNo]'s row in [semester] from the backend, keeping the
     * delete, asked at [queuedAt], until it lands. Takes
     * [PendingCourseAdds.removing] itself, so call it outside that lock.
     */
    suspend fun send(semester: String, courseNo: String, queuedAt: Long) {
        val generation = dataCache.writeGeneration()
        val signIn = signInGeneration()
        // On disk before the wait for the lock, which a slow request may hold
        // for a while: a process killed meanwhile keeps the delete for its
        // next sync. A re-add's forget waiting ahead of this cannot let it go,
        // as the delete was asked after the re-add; see [forget].
        if (!edit(generation) { it.with(semester, courseNo, queuedAt) }) return
        pendingCourseAdds.removing { sendHeld(semester, courseNo, generation, signIn) }
    }

    /**
     * Sends every delete still waiting, each under the lock on its own, so an
     * add or a reset can come between two; each full sync starts with this,
     * so the snapshot lacks them.
     */
    suspend fun retry() {
        val generation = dataCache.writeGeneration()
        val signIn = signInGeneration()
        for ((semester, entries) in waiting()) {
            for (courseNo in entries.keys) {
                val goOn = pendingCourseAdds.removing { sendHeld(semester, courseNo, generation, signIn) }
                if (!goOn) return
            }
        }
    }

    /** The course numbers whose delete in [semester] is still waiting, for the reconcile to keep out. */
    suspend fun waitingIn(semester: String): Set<String> = waiting()[semester].orEmpty().keys

    /** [forget] for a course added back now. */
    suspend fun forget(semester: String, courseNo: String) = forget(semester, courseNo, stamp())

    /**
     * Lets go of [courseNo]'s delete in [semester] asked before [askedAt], the
     * moment the course was added back: one asked after the re-add is a newer
     * removal and stays. Under the adds lock, after any send of it under way:
     * a retry that read it is then either done or finds it gone. Call it
     * outside that lock, before the add's own upload.
     */
    suspend fun forget(semester: String, courseNo: String, askedAt: Long) {
        val generation = dataCache.writeGeneration()
        pendingCourseAdds.removing {
            edit(generation) { it.without(semester, courseNo) { queuedAt -> queuedAt <= askedAt } }
        }
    }

    /**
     * Lets go of every delete in [semester]: the term was reset, which deleted
     * its rows. Call it inside the [PendingCourseAdds.removing] block whose
     * wipe just landed, before that lock goes: a retry waiting behind the
     * wipe would otherwise send a delete the reset's refetch then re-uploads
     * past, taking the course off the server again and tombstoning it.
     */
    suspend fun forgetTerm(semester: String) {
        edit(dataCache.writeGeneration()) { it - semester }
    }

    /**
     * Lets go of every delete asked before [stamp], one [stamp] took before a full sync's fetch:
     * that snapshot read a course reset made on another device, which brought back the courses
     * they took (`HomeBackendSync.applyCourseReset`). One asked after it is newer than the reset
     * and stays. Call it inside [PendingCourseAdds.removing], as [forgetTerm]: a retry waiting
     * behind it then finds them gone, rather than taking a restored course off the server again.
     * A removal stamped before [stamp] whose own writes outlast this, as the class table's may
     * before it keeps its delete, finds it let go too: [keep] and [send] then keep nothing.
     */
    suspend fun forgetAskedBefore(stamp: Long) {
        mutex.withLock { forgottenBefore = maxOf(forgottenBefore, stamp) }
        edit(dataCache.writeGeneration()) { pending ->
            pending.mapValues { (_, entries) -> entries.filterValues { queuedAt -> queuedAt > stamp } }
                .filterValues { it.isNotEmpty() }
        }
    }

    // Under the adds lock, with the delete read again there: false once the
    // data was wiped or the account changed since [generation] and [signIn]
    // were read, when the delete was another account's and is not sent as
    // this one's; true otherwise, sent or not.
    private suspend fun sendHeld(semester: String, courseNo: String, generation: Int, signIn: Int): Boolean {
        if (generation != dataCache.userDataGeneration || signIn != signInGeneration()) return false
        // Only a delete still queued: one a re-add or a reset let go
        // meanwhile must not go, as it would take the restored course off.
        val queuedAt = waiting()[semester]?.get(courseNo) ?: return true
        try {
            // The request itself carries [signIn]: a sign-out after the
            // check sends it without a token rather than with the next
            // account's.
            deleter.delete(PendingCourseAdds.forceKey(semester, courseNo), signIn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "course delete failed, kept for the next sync: ${e.logSummary()}")
            return true
        }
        // The delete sent, and only that one: a removal asked during the
        // request kept its own, newer stamp on disk and is sent again.
        edit(generation) { it.without(semester, courseNo) { it == queuedAt } }
        return true
    }

    private suspend fun waiting(): Map<String, Map<String, Long>> = load()

    // False, and nothing written, once the data was wiped since [generation].
    private suspend fun edit(
        generation: Int,
        change: (Map<String, Map<String, Long>>) -> Map<String, Map<String, Long>>,
    ): Boolean = mutex.withLock {
        val before = waiting()
        noteStamps(before)
        val after = change(before)
        after == before || dataCache.savePendingCourseDeletes(after, generation)
    }

    // Keeps the next stamp above every one [pending] holds.
    private fun noteStamps(pending: Map<String, Map<String, Long>>) {
        val largest = pending.values.maxOfOrNull { entries -> entries.values.maxOrNull() ?: 0L } ?: return
        lastStamp.updateAndGet { last -> maxOf(last, largest) }
    }

    // A delete asked again keeps the later moment: it is the newer removal. One
    // asked before a course reset let go of the deletes is not kept at all;
    // called under [mutex], by [edit].
    private fun Map<String, Map<String, Long>>.with(semester: String, courseNo: String, queuedAt: Long) =
        if (queuedAt < forgottenBefore) {
            this
        } else {
            this + (semester to this[semester].orEmpty().let { it + (courseNo to maxOf(it[courseNo] ?: 0L, queuedAt)) })
        }

    private fun Map<String, Map<String, Long>>.without(
        semester: String,
        courseNo: String,
        asked: (queuedAt: Long) -> Boolean,
    ): Map<String, Map<String, Long>> {
        val entries = this[semester].orEmpty()
        val queuedAt = entries[courseNo] ?: return this
        if (!asked(queuedAt)) return this
        val kept = entries - courseNo
        return if (kept.isEmpty()) this - semester else this + (semester to kept)
    }

    companion object {
        private const val TAG = "PendingCourseDeletes"
    }
}
