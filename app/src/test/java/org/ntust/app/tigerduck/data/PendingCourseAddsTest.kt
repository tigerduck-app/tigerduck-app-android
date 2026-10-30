@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.push.CourseUploadAnswer
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.auth.NotSignedInException
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.PushApiException
import org.ntust.app.tigerduck.push.ServerCourse
import org.ntust.app.tigerduck.shared.Course
import java.io.IOException
import org.ntust.app.tigerduck.util.JvmContext

/**
 * A course added by hand whose upload failed ([PendingCourseAdds]), over real files
 * (tigerduck-app-android#347). It used to go up once: in a term the server already had, a
 * failed upload left the course on this phone alone, never to reach another device, and lost
 * at sign-out.
 */
class PendingCourseAddsTest {

    private lateinit var cache: DataCache
    private lateinit var adds: PendingCourseAdds

    private data class Upload(
        val courseNos: List<String>,
        val semester: String,
        val forceKeys: List<String>,
        val account: Int = 1,
        val addedAt: Map<String, Long> = emptyMap(),
    )

    private val uploads = mutableListOf<Upload>()
    private var answer: suspend () -> List<String>? = { emptyList() }

    // Of what [answer] settles, the timed adds the backend refused, a hide elsewhere being newer.
    private var refused: List<String> = emptyList()

    // AuthTokenManager.signInGeneration, and what happens before the request's header is made.
    private var signIn = 1
    private var beforeHeader: suspend () -> Unit = {}

    private val term = "1151"
    private val portal = Course(courseNo = "CS1010001", courseName = "Portal")
    private val typed = Course(courseNo = "MA1010002", courseName = "Typed", isManual = true)

    @Before
    fun setUp() {
        val context = JvmContext()
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
        // As PushApiClient.uploadCourses: pinned to a sign-in, the header is that account's or none.
        adds = PendingCourseAdds(cache, { courses, semester, forceKeys, pinned, addedAt ->
            beforeHeader()
            if (pinned != signIn) throw NotSignedInException()
            uploads += Upload(courses.map { it.courseNo }, semester, forceKeys, account = signIn, addedAt = addedAt)
            answer()?.let { accepted -> CourseUploadAnswer(accepted - refused, refused.filter { it in accepted }) }
        }, signInGeneration = { signIn })
    }

    private fun row(courseNo: String, semester: String = term) =
        ServerCourse(courseNo = courseNo, courseName = courseNo, semester = semester)

    @Test
    fun `an add whose upload fails is kept, and the next sync sends it again with its force key`() = runTest {
        cache.saveCourses(listOf(portal, typed), term)
        answer = { throw IOException("offline") }
        adds.send(listOf(portal, typed), term, typed.courseNo)
        assertEquals(mapOf(term to listOf("MA1010002")), cache.loadPendingCourseAdds())

        answer = { listOf("MA1010002") }
        adds.retry()

        assertEquals(Upload(listOf("MA1010002"), term, listOf("client:1151:MA1010002")), uploads.last())
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
    }

    /**
     * An add goes with the moment it was made, alone (#591): the backend applies it only when
     * later than the course's last add, hide and reset, and under protocol 2 every other course
     * of the upload counts as the school's roster. Kept with its add, the moment goes out again
     * on the next sync's retry, and leaves once the add lands.
     */
    @Test
    fun `an add goes alone with its moment, again on a retry, and the moment leaves with it`() = runTest {
        cache.saveCourses(listOf(portal, typed), term)
        answer = { throw IOException("offline") }
        adds.send(listOf(portal, typed), term, typed.courseNo, addedAt = 5_000L)

        assertEquals(Upload(listOf("MA1010002"), term, listOf("client:1151:MA1010002"), addedAt = mapOf("MA1010002" to 5_000L)), uploads.last())
        assertEquals(mapOf(term to mapOf("MA1010002" to 5_000L)), cache.loadPendingCourseAddTimes())

        answer = { listOf("MA1010002") }
        adds.retry()

        assertEquals(mapOf("MA1010002" to 5_000L), uploads.last().addedAt)
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
        assertEquals(emptyMap<String, Map<String, Long>>(), cache.loadPendingCourseAddTimes())
    }

    /**
     * The course added again while its first add's request is out: that request's answer is to
     * the older add, and leaves the newer one, with its later moment, to go after it.
     */
    @Test
    fun `an answer to an older add leaves a newer re-add of it waiting`() = runTest {
        cache.saveCourses(listOf(typed), term)
        val firstOut = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        beforeHeader = {
            beforeHeader = {}
            firstOut.complete(Unit)
            releaseFirst.await()
        }
        answer = { listOf("MA1010002") }

        val first = launch { adds.send(listOf(typed), term, typed.courseNo, addedAt = 5_000L) }
        firstOut.await()
        val second = launch { adds.send(listOf(typed), term, typed.courseNo, addedAt = 6_000L) }
        runCurrent()
        releaseFirst.complete(Unit)
        first.join()
        second.join()

        assertEquals(listOf(mapOf("MA1010002" to 5_000L), mapOf("MA1010002" to 6_000L)), uploads.map { it.addedAt })
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
        assertEquals(emptyMap<String, Map<String, Long>>(), cache.loadPendingCourseAddTimes())
    }

    /**
     * A course this phone typed, whose timed add the backend answered (here, not applied: a hide
     * elsewhere was newer), is one the server has settled: a snapshot's tombstone may hide it,
     * as the reconcile never does a course the server has not seen.
     */
    @Test
    fun `a timed add the backend refused counts as one the server knows, one it applied does not`() = runTest {
        cache.saveCourses(listOf(typed, portal), term)
        answer = { listOf("MA1010002") }
        refused = listOf("MA1010002")

        adds.send(listOf(typed), term, typed.courseNo, addedAt = 5_000L)

        assertEquals(listOf("MA1010002"), cache.loadServerKnownNos()[term])
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())

        // Applied: a snapshot taken before it landed would read its absence as a delete.
        answer = { listOf("CS1010001") }
        refused = emptyList()
        adds.send(listOf(portal), term, portal.courseNo, addedAt = 6_000L)

        assertEquals(listOf("MA1010002"), cache.loadServerKnownNos()[term])
    }

    /** One an older install kept has no moment: it goes as it did, unstamped. */
    @Test
    fun `an add kept with no moment goes unstamped, as before`() = runTest {
        cache.saveCourses(listOf(portal, typed), term)
        cache.savePendingCourseAdds(mapOf(term to listOf("MA1010002")))
        answer = { listOf("MA1010002") }

        adds.retry()

        assertEquals(Upload(listOf("MA1010002"), term, listOf("client:1151:MA1010002")), uploads.single())
    }

    /**
     * A full sync for an account that has since signed out runs under the generation it began
     * with; the snapshot it brings back lists that account's courses, and must not take the
     * next account's waiting add of the same course as landed.
     */
    @Test
    fun `a snapshot for an account that signed out leaves the next account's waiting adds alone`() = runTest {
        val departed = cache.userDataGeneration
        cache.clearAllUserData()
        signIn = 2
        cache.saveCourses(listOf(typed), term)
        answer = { throw IOException("offline") }
        adds.send(listOf(typed), term, typed.courseNo)

        withContext(DataCache.Generation(departed)) {
            adds.listed(listOf(row("MA1010002")))
            adds.forgetAll()
        }

        assertEquals(mapOf(term to listOf("MA1010002")), cache.loadPendingCourseAdds())
    }

    @Test
    fun `an add that lands is not kept, and one refused by the server waits for the next sync`() = runTest {
        cache.saveCourses(listOf(typed), term)
        answer = { listOf("MA1010002") }
        adds.send(listOf(typed), term, typed.courseNo)
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())

        answer = { throw PushApiException("uploadCourses failed: HTTP 500") }
        adds.send(listOf(typed), term, typed.courseNo)
        adds.retry()
        assertEquals(mapOf(term to listOf("MA1010002")), cache.loadPendingCourseAdds())
    }

    @Test
    fun `an answer that accepts no course keeps the add, and one that accepts some keeps the rest`() = runTest {
        val other = Course(courseNo = "PE1010003", courseName = "Other", isManual = true)
        cache.saveCourses(listOf(typed, other), term)
        // A 200 that says nothing was taken: a captive portal, or a body that could not be read.
        answer = { emptyList() }
        adds.send(listOf(typed, other), term, typed.courseNo)
        adds.send(listOf(typed, other), term, other.courseNo)
        assertEquals(mapOf(term to listOf("MA1010002", "PE1010003")), cache.loadPendingCourseAdds())

        answer = { listOf("PE1010003") }
        adds.retry()
        assertEquals(mapOf(term to listOf("MA1010002")), cache.loadPendingCourseAdds())

        // Nothing sent, course sync being off: the re-enable check answers for it.
        answer = { null }
        adds.retry()
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
    }

    @Test
    fun `an add read as one account is not sent as the next one`() = runTest {
        cache.saveCourses(listOf(typed), term)
        answer = { throw IOException("offline") }
        adds.send(listOf(typed), term, typed.courseNo)

        // The account signs out, and another signs in, before the retry's request has its header.
        answer = { listOf("MA1010002") }
        beforeHeader = {
            beforeHeader = {}
            cache.clearAllUserData()
            signIn++
        }
        adds.retry()

        assertEquals(emptyList<Upload>(), uploads.filter { it.account == 2 })
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
    }

    @Test
    fun `a course deleted or reset while its add is sent leaves the server after the add lands`() = runTest {
        cache.saveCourses(listOf(typed), term)
        answer = { throw IOException("offline") }
        adds.send(listOf(typed), term, typed.courseNo)

        val events = mutableListOf<String>()
        val sending = CompletableDeferred<Unit>()
        val landed = CompletableDeferred<Unit>()
        answer = {
            sending.complete(Unit)
            landed.await()
            events += "upload"
            listOf("MA1010002")
        }
        val retry = launch { adds.retry() }
        sending.await()
        // Deleted by hand meanwhile: off this phone, then off the server.
        cache.saveCourses(emptyList(), term)
        val delete = launch { adds.removing { events += "DELETE" } }
        runCurrent()
        landed.complete(Unit)
        retry.join()
        delete.join()

        // The add's force key would have brought the course back past the DELETE.
        assertEquals(listOf("upload", "DELETE"), events)
    }

    @Test
    fun `a retry waiting behind an upload sends nothing once that upload retired the add`() = runTest {
        cache.saveCourses(listOf(typed), term)
        answer = { throw IOException("offline") }
        adds.send(listOf(typed), term, typed.courseNo)
        uploads.clear()

        val sending = CompletableDeferred<Unit>()
        val landed = CompletableDeferred<Unit>()
        answer = {
            sending.complete(Unit)
            landed.await()
            listOf("MA1010002")
        }
        val first = launch { adds.retry() }
        sending.await()
        // Read the add while it still waited, then queued behind the upload under way.
        val second = launch { adds.retry() }
        runCurrent()
        answer = { listOf("MA1010002") }
        landed.complete(Unit)
        first.join()
        second.join()

        // Its stale force key would bring the course back past a delete made elsewhere meanwhile.
        assertEquals(1, uploads.size)
    }

    @Test
    fun `an add whose course was deleted since is not sent again, though the cache still has it`() = runTest {
        cache.saveCourses(listOf(typed), term)
        answer = { throw IOException("offline") }
        adds.send(listOf(typed), term, typed.courseNo)
        cache.saveDeletedCourseNos(CourseTombstoneKeys.hide(typed.courseNo, term, emptySet()))
        uploads.clear()

        answer = { listOf("MA1010002") }
        adds.retry()

        assertEquals(emptyList<Upload>(), uploads)
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
    }

    @Test
    fun `an add the server lists in a snapshot is let go`() = runTest {
        cache.saveCourses(listOf(typed), term)
        answer = { throw IOException("timed out after the server took it") }
        adds.send(listOf(typed), term, typed.courseNo)

        adds.listed(listOf(row("MA1010002", semester = "1142")))
        assertEquals("only the same term's row counts", mapOf(term to listOf("MA1010002")), cache.loadPendingCourseAdds())

        adds.listed(listOf(row("MA1010002")))
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
    }

    /** What the reconcile keeps meanwhile; see CourseSyncReconciler.reconcileSemester's pendingAddNos. */
    @Test
    fun `the adds still waiting are read per term`() = runTest {
        cache.saveCourses(listOf(typed), term)
        answer = { throw IOException("offline") }
        adds.send(listOf(typed), term, typed.courseNo)

        assertEquals(setOf("MA1010002"), adds.waitingIn(term))
        assertEquals(emptySet<String>(), adds.waitingIn("1142"))

        answer = { listOf("MA1010002") }
        adds.retry()
        assertEquals(emptySet<String>(), adds.waitingIn(term))
    }

    /** HomeBackendSync.applyCourseReset: a course reset made on another device takes the waiting adds' courses. */
    @Test
    fun `a reset made elsewhere lets every waiting add go`() = runTest {
        val other = Course(courseNo = "PE1010003", courseName = "Other", isManual = true)
        cache.saveCourses(listOf(typed), term)
        cache.saveCourses(listOf(other), "1142")
        answer = { throw IOException("offline") }
        adds.send(listOf(typed), term, typed.courseNo)
        adds.send(listOf(other), "1142", other.courseNo)
        uploads.clear()

        adds.forgetAll()

        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
        answer = { emptyList() }
        adds.retry()
        assertEquals(emptyList<Upload>(), uploads)
    }

    @Test
    fun `an add deleted or reset away since is not sent again`() = runTest {
        cache.saveCourses(listOf(typed), term)
        answer = { throw IOException("offline") }
        adds.send(listOf(typed), term, typed.courseNo)
        cache.saveCourses(emptyList(), term)
        uploads.clear()

        answer = { emptyList() }
        adds.retry()

        assertEquals(emptyList<Upload>(), uploads)
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
    }

    @Test
    fun `nothing is kept for the next account`() = runTest {
        cache.saveCourses(listOf(typed), term)
        answer = { throw IOException("offline") }
        adds.send(listOf(typed), term, typed.courseNo)

        cache.clearAllUserData()

        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
    }
}
