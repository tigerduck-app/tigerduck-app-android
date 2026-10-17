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

/** Uploads one term's courses as sign-in [signIn]; see [PushApiClient.uploadCourses]. */
internal fun interface CourseUploader {
    suspend fun upload(courses: List<Course>, semester: String, forceKeys: List<String>, signIn: Int): List<String>?
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
 *
 * iOS sends an add once and keeps nothing; its next sync drops a hand-added
 * course a populated server term does not list.
 */
@Singleton
class PendingCourseAdds internal constructor(
    private val dataCache: DataCache,
    private val uploader: CourseUploader,
    private val signInGeneration: () -> Int,
) {
    @Inject constructor(dataCache: DataCache, pushApiClient: PushApiClient, authTokenManager: AuthTokenManager) : this(
        dataCache = dataCache,
        uploader = CourseUploader(pushApiClient::uploadCourses),
        signInGeneration = { authTokenManager.signInGeneration },
    )

    private val mutex = Mutex()

    // One upload at a time, and none while courses are taken off the backend;
    // see [removing].
    private val sendMutex = Mutex()

    /**
     * Uploads [courses], [semester]'s list with [courseNo] just added to it,
     * forcing [courseNo] past a tombstone as an add does. Kept until the
     * backend accepts it. Let go as well when nothing was sent because course
     * sync is off: the re-enable check answers for the courses when it comes
     * back on.
     */
    suspend fun send(courses: List<Course>, semester: String, courseNo: String) {
        val generation = dataCache.userDataGeneration
        val signIn = signInGeneration()
        if (!edit(generation) { it.with(semester, listOf(courseNo)) }) return
        trySend(semester, listOf(courseNo), generation, signIn, courses)
    }

    /**
     * Sends every add still waiting, with the course as this phone has it
     * now; each full sync starts with this, so the snapshot it fetches has
     * them. An add whose course this phone no longer holds is let go unsent.
     */
    suspend fun retry() {
        val generation = dataCache.userDataGeneration
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
        val generation = dataCache.userDataGeneration
        val waiting = waiting()
        if (waiting.isEmpty()) return
        val listed = serverCourses.groupBy({ it.semester }, { it.courseNo })
        if (waiting.none { (semester, nos) -> listed[semester].orEmpty().any { it in nos } }) return
        edit(generation) { pending ->
            listed.entries.fold(pending) { acc, (semester, nos) -> acc.without(semester, nos) }
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
        val accepted = if (held.isEmpty()) emptyList() else {
            upload(courses ?: cached.filter { it.courseNo in held }, semester, held, signIn)
        }
        val done = requested - held.toSet() + accepted
        if (done.isNotEmpty()) edit(generation) { it.without(semester, done) }
        true
    }

    // The course numbers the backend took: those its answer accepts (a 200
    // alone is not acceptance, see PushApiClient.uploadCourses), or all of
    // them when nothing was sent because course sync is off. None on a failure.
    private suspend fun upload(courses: List<Course>, semester: String, courseNos: List<String>, signIn: Int): List<String> =
        try {
            val accepted = uploader.upload(courses, semester, courseNos.map { forceKey(semester, it) }, signIn)
            val taken = if (accepted == null) courseNos else courseNos.filter { it in accepted }
            if (taken.size < courseNos.size) Log.w(TAG, "course add not accepted, kept for the next sync")
            taken
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "course add upload failed, kept for the next sync: ${e.logSummary()}")
            emptyList()
        }

    private suspend fun waiting(): Map<String, List<String>> = dataCache.loadPendingCourseAdds()

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
