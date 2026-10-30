package org.ntust.app.tigerduck.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.ServerCourse
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uploads one term's courses as sign-in [signIn], each add in [addedAt] with its time; see
 * [PushApiClient.uploadCourses].
 */
internal fun interface CourseUploader {
    suspend fun upload(
        courses: List<Course>,
        semester: String,
        forceKeys: List<String>,
        signIn: Int,
        addedAt: Map<String, Long>,
    ): List<String>?
}

/**
 * Courses added by hand on the class table whose upload the backend has not
 * taken, by term.
 *
 * An add went up once. A sync re-uploads a term's courses only when the
 * server has none for it (`CourseSyncReconciler.reconcileSemester`), so in a
 * term the server already had, a failed upload left the course on this phone
 * alone: never on another device, and lost at sign-out. Now the add waits on
 * disk, kept before the request so a process killed during it keeps it too,
 * and every full sync sends it again first with the same force key, as
 * [UnsentMarks] does for marks. It goes once an upload's answer accepts the
 * course or a snapshot lists it, or once the course has left this phone's
 * cache or been deleted (by hand, a reset, Use Server). Account-scoped: sent
 * only as the account that kept it, and wiped with the user data at sign-out.
 * Meanwhile the reconcile keeps the course ([waitingIn]), past a tombstone or
 * the server's silence, and the sign-out warning counts it.
 *
 * As the iPhone's outbox since tigerduck-app#727.
 */
@Singleton
class PendingCourseAdds internal constructor(
    private val dataCache: DataCache,
    private val uploader: CourseUploader,
    private val signInGeneration: () -> Int,
) {
    @Inject constructor(dataCache: DataCache, pushApiClient: PushApiClient, authTokenManager: AuthTokenManager) : this(
        dataCache = dataCache,
        uploader = CourseUploader { courses, semester, forceKeys, signIn, addedAt ->
            pushApiClient.uploadCourses(courses, semester, forceKeys, signIn, addedAt = addedAt)
        },
        signInGeneration = { authTokenManager.signInGeneration },
    )

    private val mutex = Mutex()

    // One upload at a time, and none while courses are taken off the backend;
    // see [removing].
    private val sendMutex = Mutex()

    /**
     * Uploads [courseNo], just added to [semester]'s list [courses], forcing it
     * past a tombstone as an add does, with [addedAt], the add's moment
     * ([PendingCourseDeletes.stamp]): the backend applies it only when later
     * than the course's last add, hide and reset, so an add kept offline loses
     * to a hide or a reset made since on another device (#591). Kept until the
     * backend accepts or settles it. Let go as well when nothing was sent
     * because course sync is off: the re-enable check answers for the courses
     * when it comes back on.
     */
    suspend fun send(courses: List<Course>, semester: String, courseNo: String, addedAt: Long? = null) {
        val generation = dataCache.writeGeneration()
        val signIn = signInGeneration()
        if (!edit(generation) { it.with(semester, listOf(courseNo)) }) return
        if (addedAt != null) editTimes(generation) { it.withTime(semester, courseNo, addedAt) }
        trySend(semester, listOf(courseNo), generation, signIn, courses)
    }

    /**
     * Sends every add still waiting, with the course as this phone has it
     * now; each full sync starts with this, so the snapshot it fetches has
     * them. An add whose course this phone no longer holds is let go unsent.
     */
    suspend fun retry() {
        val generation = dataCache.writeGeneration()
        val signIn = signInGeneration()
        for ((semester, nos) in waiting()) {
            if (!trySend(semester, nos, generation, signIn)) return
        }
    }

    /**
     * Runs [block], which takes courses off the backend (a course deleted by
     * hand, a term reset), with no add being sent meanwhile. An add already
     * under way lands first, and the DELETE after it; one that waits finds
     * its course gone from this phone and is not sent. The other way round,
     * its force key would bring the course back past the DELETE. The courses
     * have left this phone's cache by the time [block] ends.
     */
    suspend fun <T> removing(block: suspend () -> T): T = sendMutex.withLock { block() }

    /**
     * Runs [block], which puts a term's courses on the backend as this phone
     * holds them — a fetch's upload, with the save it goes with — in the
     * order [removing]'s DELETEs run in: begun before one, it lands first and
     * the DELETE takes the courses after; begun after one, it finds what the
     * DELETE left. An upload past a DELETE of its term would bring the
     * courses back, as an add's force key would, and with no tombstone to
     * stop it.
     */
    suspend fun <T> uploading(block: suspend () -> T): T = sendMutex.withLock { block() }

    /** Lets go of the adds [serverCourses], a snapshot's rows, list: the server has them. */
    suspend fun listed(serverCourses: List<ServerCourse>) {
        val generation = dataCache.writeGeneration()
        val waiting = waiting()
        if (waiting.isEmpty()) return
        val listed = serverCourses.groupBy({ it.semester }, { it.courseNo })
        if (waiting.none { (semester, nos) -> listed[semester].orEmpty().any { it in nos } }) return
        edit(generation) { pending ->
            listed.entries.fold(pending) { acc, (semester, nos) -> acc.without(semester, nos) }
        }
        editTimes(generation) { times ->
            listed.entries.fold(times) { acc, (semester, nos) -> acc.withoutTimes(semester, nos) }
        }
    }

    /**
     * Sends [courseNos]' adds in [semester], [courses] going up (or the
     * courses themselves as this phone holds them), for the account whose
     * data [generation] and sign-in [signIn] were read with. Checked under the
     * send lock, right before the request: an add whose course has left this
     * phone's cache or been deleted since is let go unsent, and a sign-out
     * since stops the send, false. The request itself carries [signIn], so a
     * sign-out after the check sends it without a token rather than with the
     * next account's.
     */
    private suspend fun trySend(
        semester: String,
        courseNos: List<String>,
        generation: Int,
        signIn: Int,
        courses: List<Course>? = null,
    ): Boolean = sendMutex.withLock {
        if (generation != dataCache.userDataGeneration || signIn != signInGeneration()) return@withLock false
        // Only adds still waiting: one an upload ahead of this one retired meanwhile must not go
        // again, its force key lifting a delete made since.
        val stillWaiting = waiting()[semester].orEmpty()
        val requested = courseNos.filter { it in stillWaiting }
        val cached = dataCache.loadCourses(semester)
        val deleted = dataCache.loadDeletedCourseNos()
        val held = requested.filter { no ->
            cached.any { it.courseNo == no } && !CourseTombstoneKeys.isHidden(no, semester, deleted)
        }
        // An add with its time goes under protocol 2, alone with the others timed: there every
        // other course of the upload counts as the school's roster. One an older install kept
        // has no time and goes as before.
        val times = waitingTimes()[semester].orEmpty()
        val (timed, untimed) = held.partition { it in times }
        val source = courses ?: cached
        val accepted = (if (timed.isEmpty()) emptyList() else {
            upload(source.filter { it.courseNo in timed }, semester, timed, signIn, times.filterKeys { it in timed })
        }) + (if (untimed.isEmpty()) emptyList() else {
            upload(courses ?: cached.filter { it.courseNo in untimed }, semester, untimed, signIn, emptyMap())
        })
        val done = requested - held.toSet() + accepted
        if (done.isNotEmpty()) {
            edit(generation) { it.without(semester, done) }
            editTimes(generation) { it.withoutTimes(semester, done) }
        }
        true
    }

    // The course numbers the backend took: those its answer accepts (a 200
    // alone is not acceptance, see PushApiClient.uploadCourses), or all of
    // them when nothing was sent because course sync is off. None on a failure.
    private suspend fun upload(
        courses: List<Course>,
        semester: String,
        courseNos: List<String>,
        signIn: Int,
        addedAt: Map<String, Long>,
    ): List<String> =
        try {
            val accepted = uploader.upload(courses, semester, courseNos.map { forceKey(semester, it) }, signIn, addedAt)
            val taken = if (accepted == null) courseNos else courseNos.filter { it in accepted }
            if (taken.size < courseNos.size) Log.w(TAG, "course add not accepted, kept for the next sync")
            taken
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "course add upload failed, kept for the next sync: ${e.logSummary()}")
            emptyList()
        }

    /**
     * Lets go of every waiting add: a course reset made on another device has
     * taken the hand-added courses (`HomeBackendSync.applyCourseReset`), and an
     * add kept past it would put its course back on every device.
     */
    suspend fun forgetAll() {
        val generation = dataCache.writeGeneration()
        edit(generation) { emptyMap() }
        editTimes(generation) { emptyMap() }
    }

    /** The course numbers whose add in [semester] is still waiting, for the reconcile to keep. */
    suspend fun waitingIn(semester: String): Set<String> = waiting()[semester].orEmpty().toSet()

    private suspend fun waiting(): Map<String, List<String>> = dataCache.loadPendingCourseAdds()

    // When each waiting add was asked; an add without one goes unstamped.
    private suspend fun waitingTimes(): Map<String, Map<String, Long>> = dataCache.loadPendingCourseAddTimes()

    // As [edit], for the times. Under the same lock: a time is read and written with its add.
    private suspend fun editTimes(
        generation: Int,
        change: (Map<String, Map<String, Long>>) -> Map<String, Map<String, Long>>,
    ): Boolean = mutex.withLock {
        val before = waitingTimes()
        val after = change(before)
        after == before || dataCache.savePendingCourseAddTimes(after, generation)
    }

    // An add asked again keeps the later moment: it is the newer add.
    private fun Map<String, Map<String, Long>>.withTime(semester: String, courseNo: String, addedAt: Long) =
        this + (semester to this[semester].orEmpty().let { it + (courseNo to maxOf(it[courseNo] ?: 0L, addedAt)) })

    private fun Map<String, Map<String, Long>>.withoutTimes(semester: String, courseNos: Collection<String>): Map<String, Map<String, Long>> {
        val kept = this[semester].orEmpty() - courseNos.toSet()
        return if (kept.isEmpty()) this - semester else this + (semester to kept)
    }

    // False, and nothing written, once the data was wiped since [generation].
    private suspend fun edit(
        generation: Int,
        change: (Map<String, List<String>>) -> Map<String, List<String>>,
    ): Boolean = mutex.withLock {
        val before = waiting()
        val after = change(before)
        after == before || dataCache.savePendingCourseAdds(after, generation)
    }

    private fun Map<String, List<String>>.with(semester: String, courseNos: List<String>) =
        this + (semester to (this[semester].orEmpty() + courseNos).distinct())

    private fun Map<String, List<String>>.without(semester: String, courseNos: Collection<String>): Map<String, List<String>> {
        val kept = this[semester].orEmpty() - courseNos.toSet()
        return if (kept.isEmpty()) this - semester else this + (semester to kept)
    }

    companion object {
        private const val TAG = "PendingCourseAdds"

        /** The key an upload forces past a tombstone, as the class table's add sends it. */
        fun forceKey(semester: String, courseNo: String): String = "client:$semester:$courseNo"
    }
}
