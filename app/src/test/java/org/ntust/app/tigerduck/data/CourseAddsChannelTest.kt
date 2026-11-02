package org.ntust.app.tigerduck.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.RecordingBackend
import org.ntust.app.tigerduck.push.pushApiClientAt
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The course adds as an outbox channel ([PendingCourseAdds]), over real files and the real backend
 * client, against a backend that records what reaches it.
 *
 * On `/v3` every request is pinned byte for byte as the queue sent it before it was a channel: its
 * method, address, headers and body, and what each answer leaves queued.
 */
class CourseAddsChannelTest {

    private val v3 = "http://127.0.0.1:9/v3"
    private val context = JvmContext()
    private val prefs = AppPreferences(context)
    private val backend = RecordingBackend()
    private val cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
    private val series = SignInSeries()
    private val signIn get() = series.current

    private val term = "1151"
    private val nextTerm = "1152"
    private val portal = Course(courseNo = "CS1010001", courseName = "Portal", moodleIdNumber = "1151CS1010001")
    private val typed = Course(courseNo = "MA1010002", courseName = "Typed", isManual = true, customColorHex = "#336699")
    private val untyped = Course(courseNo = "PE1010003", courseName = "Untimed", isManual = true)
    private val later = Course(courseNo = "XB0000002", courseName = "Next term", isManual = true)

    private val bearer = "Authorization" to "Bearer access"
    private val protocol2 = "X-TigerSync-Protocol" to "2"
    private val json = "application/json; charset=utf-8"

    @Before
    fun syncs() {
        // TigerSync is off for good on F-Droid, where none of these goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
    }

    private fun v3Adds() = PendingCourseAdds(cache, pushApiClientAt(v3, backend.client, prefs))

    private fun RecordingBackend.Sent.shape() = listOf(method, url, headers, contentType, body)

    private fun row(course: Course, semester: String = term) =
        """{"semester":"$semester","course_no":"${course.courseNo}","course_name":"${course.courseName}",""" +
            (course.moodleIdNumber?.let { """"moodle_id":"$it",""" } ?: "") +
            """"credits":0.0,"classroom":"","instructors":[],"schedule_json":{},"classroom_map":{}}"""

    private fun key(course: Course, semester: String = term) = "client:$semester:${course.courseNo}"

    @Test
    fun `on v3 an add goes alone with its time under protocol 2, its color and its force key`() = runTest {
        cache.saveCourses(listOf(portal, typed), term, signIn)
        backend.answer("/v3/sync/courses/upload", 200, """{"accepted_keys":["${key(typed)}"]}""")

        v3Adds().send(listOf(portal, typed), term, typed.courseNo, addedAt = 1_790_000_000_123L, signIn = signIn)

        assertEquals(
            listOf(
                listOf(
                    "POST", "$v3/sync/courses/upload", listOf(bearer, protocol2), json,
                    """{"courses":[${row(typed)}],"course_overrides":[{"course_key":"${key(typed)}","color_hex":"#336699"}],""" +
                        """"force_keys":["${key(typed)}"],"adds":[{"course_key":"${key(typed)}","hlc":1790000000123000}]}""",
                ),
            ),
            backend.sent.map { it.shape() },
        )
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
        assertEquals(emptyMap<String, Map<String, Long>>(), cache.loadPendingCourseAddTimes())
    }

    /** An add with no time goes with the whole list it was made from, as it always went. */
    @Test
    fun `on v3 an add with no time goes with the list it came from, its force key and no protocol`() = runTest {
        cache.saveCourses(listOf(portal, untyped), term, signIn)

        v3Adds().send(listOf(portal, untyped), term, untyped.courseNo, signIn = signIn)

        assertEquals(
            listOf(
                listOf(
                    "POST", "$v3/sync/courses/upload", listOf(bearer), json,
                    """{"courses":[${row(portal)},${row(untyped)}],"course_overrides":[],"force_keys":["${key(untyped)}"]}""",
                ),
            ),
            backend.sent.map { it.shape() },
        )
        // A 200 that names no course taken: kept for the next sync.
        assertEquals(mapOf(term to listOf(untyped.courseNo)), cache.loadPendingCourseAdds())
    }

    /**
     * A sync's retry: each term's timed adds in one upload, then its untimed ones in another, the
     * course as this phone holds it; a term whose upload fails leaves the next one to go.
     */
    @Test
    fun `on v3 a retry sends each term's timed adds, then its untimed ones, and goes on past a failed term`() = runTest {
        cache.saveCourses(listOf(portal, typed, untyped), term, signIn)
        cache.saveCourses(listOf(later), nextTerm, signIn)
        cache.savePendingCourseAdds(mapOf(term to listOf(untyped.courseNo, typed.courseNo), nextTerm to listOf(later.courseNo)), signIn)
        cache.savePendingCourseAddTimes(mapOf(term to mapOf(typed.courseNo to 5_000L), nextTerm to mapOf(later.courseNo to 6_000L)), signIn)
        backend.answer("/v3/sync/courses/upload", 500, "{}")
        backend.answer("/v3/sync/courses/upload", 200, """{"accepted_keys":["${key(untyped)}"]}""")
        backend.answer("/v3/sync/courses/upload", 200, """{"accepted_keys":["${key(later, nextTerm)}"]}""")

        v3Adds().retry(signIn)

        assertEquals(
            listOf(
                listOf(
                    "POST", "$v3/sync/courses/upload", listOf(bearer, protocol2), json,
                    """{"courses":[${row(typed)}],"course_overrides":[{"course_key":"${key(typed)}","color_hex":"#336699"}],""" +
                        """"force_keys":["${key(typed)}"],"adds":[{"course_key":"${key(typed)}","hlc":5000000}]}""",
                ),
                listOf(
                    "POST", "$v3/sync/courses/upload", listOf(bearer), json,
                    """{"courses":[${row(untyped)}],"course_overrides":[],"force_keys":["${key(untyped)}"]}""",
                ),
                listOf(
                    "POST", "$v3/sync/courses/upload", listOf(bearer, protocol2), json,
                    """{"courses":[${row(later, nextTerm)}],"course_overrides":[],"force_keys":["${key(later, nextTerm)}"],""" +
                        """"adds":[{"course_key":"${key(later, nextTerm)}","hlc":6000000}]}""",
                ),
            ),
            backend.sent.map { it.shape() },
        )
        assertEquals(mapOf(term to listOf(typed.courseNo)), cache.loadPendingCourseAdds())
        assertEquals(mapOf(term to mapOf(typed.courseNo to 5_000L)), cache.loadPendingCourseAddTimes())
    }

    /** Refused as ahead of the backend's clock, an add goes once more without its time. */
    @Test
    fun `on v3 an add refused as ahead goes again unstamped`() = runTest {
        cache.saveCourses(listOf(typed), term, signIn)
        backend.answer("/v3/sync/courses/upload", 409, """{"detail":"clock_ahead"}""")
        backend.answer("/v3/sync/courses/upload", 200, """{"accepted_keys":["${key(typed)}"]}""")

        v3Adds().send(listOf(typed), term, typed.courseNo, addedAt = 1_790_000_000_123L, signIn = signIn)

        val plain = """{"courses":[${row(typed)}],"course_overrides":[{"course_key":"${key(typed)}","color_hex":"#336699"}],""" +
            """"force_keys":["${key(typed)}"]"""
        assertEquals(
            listOf(
                listOf("POST", "$v3/sync/courses/upload", listOf(bearer, protocol2), json, "$plain,\"adds\":[{\"course_key\":\"${key(typed)}\",\"hlc\":1790000000123000}]}"),
                listOf("POST", "$v3/sync/courses/upload", listOf(bearer), json, "$plain}"),
            ),
            backend.sent.map { it.shape() },
        )
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
    }

    /** Nothing goes while course sync is off, and the add is let go: the re-enable check answers for it. */
    @Test
    fun `on v3 an add made with course sync off sends nothing and is let go`() = runTest {
        cache.saveCourses(listOf(typed), term, signIn)
        prefs.syncCourses = false

        v3Adds().send(listOf(typed), term, typed.courseNo, addedAt = 1_790_000_000_123L, signIn = signIn)

        assertEquals(emptyList<RecordingBackend.Sent>(), backend.sent.toList())
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
        assertEquals(emptyMap<String, Map<String, Long>>(), cache.loadPendingCourseAddTimes())
    }
}
