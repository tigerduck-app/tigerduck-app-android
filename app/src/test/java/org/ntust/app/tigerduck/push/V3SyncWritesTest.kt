package org.ntust.app.tigerduck.push

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.ApiVersionGate
import org.ntust.app.tigerduck.network.ApiVersionInterceptor
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn
import java.util.Date

/**
 * What each sync write sends on `/v3`, byte for byte: the method, the address, every header the
 * client builds, the body and its type, for each way a write can go (stamped or not, resent after
 * a `clock_ahead`), and what a refused one throws. Phase 3 moves these requests out of
 * [PushApiClient] and gives each a `/v4` form beside it; on `/v3` none of this may change.
 */
class V3SyncWritesTest {

    private val v3 = "http://127.0.0.1:9/v3"
    private val backend = RecordingBackend(ahead = listOf(ApiVersionInterceptor { v3 }))
    private val prefs = AppPreferences(JvmContext())
    private val client = pushApiClientAt(v3, backend.client, prefs)
    private val signIn = testSignIn()

    private val madeAtMs = 1_790_000_000_123L
    private val bearer = "Authorization" to "Bearer access"
    private val json = "application/json; charset=utf-8"
    private val clockAhead = """{"detail":"clock_ahead"}"""

    @Before
    fun openGate() {
        ApiVersionGate.resetForTesting()
        // TigerSync is off for good on F-Droid, where none of these goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
    }

    @After fun closeGate() = ApiVersionGate.resetForTesting()

    private fun RecordingBackend.Sent.shape() = listOf(method, url, headers, contentType, body)

    private fun shapes() = backend.sent.map { it.shape() }

    private suspend fun failure(block: suspend () -> Unit): Pair<String?, Int?> {
        val e = runCatching { block() }.exceptionOrNull()
        assertTrue("got $e", e is PushApiException)
        return e?.message to (e as PushApiException).statusCode
    }

    @Test
    fun `a holiday choice is a PUT of notify, stamped or not`() = runTest {
        client.putHolidayOverride(3, notify = true, signIn = signIn, madeAtMs = madeAtMs)
        client.putHolidayOverride(3, notify = false, signIn = signIn)

        assertEquals(
            listOf(
                listOf("PUT", "$v3/sync/holiday-overrides/3", listOf(bearer), json, """{"notify":true,"hlc":1790000000123000}"""),
                listOf("PUT", "$v3/sync/holiday-overrides/3", listOf(bearer), json, """{"notify":false}"""),
            ),
            shapes(),
        )
    }

    @Test
    fun `a mark is a PATCH of its status, stamped or not`() = runTest {
        client.patchAssignmentOverride(7, "ignored", signIn, madeAtMs = madeAtMs)
        client.patchAssignmentOverride(7, "none", signIn)

        val path = "$v3/sync/assignments/7/override"
        assertEquals(
            listOf(
                listOf("PATCH", path, listOf(bearer), json, """{"local_status":"ignored","hlc":1790000000123000}"""),
                listOf("PATCH", path, listOf(bearer), json, """{"local_status":"none"}"""),
            ),
            shapes(),
        )
    }

    @Test
    fun `a course color or name is a PATCH by its Moodle id`() = runTest {
        client.patchCourseOverride("1151CS1", signIn, colorHex = "#FF0000", madeAtMs = madeAtMs)
        client.patchCourseOverride("1151CS1", signIn, customName = "Algo", locale = "en", madeAtMs = madeAtMs)
        client.patchCourseOverride(42, signIn, colorHex = "#00FF00", customName = "Both", locale = "zh")

        val path = "$v3/sync/courses/1151CS1/override"
        assertEquals(
            listOf(
                listOf("PATCH", path, listOf(bearer), json, """{"color_hex":"#FF0000","hlc":1790000000123000}"""),
                listOf("PATCH", path, listOf(bearer), json, """{"custom_name":"Algo","locale":"en","hlc":1790000000123000}"""),
                listOf("PATCH", "$v3/sync/courses/42/override", listOf(bearer), json, """{"color_hex":"#00FF00","custom_name":"Both","locale":"zh"}"""),
            ),
            shapes(),
        )
    }

    @Test
    fun `a course ignore is a PATCH of the same override, with the numeric id when known`() = runTest {
        client.patchCourseIgnore("1151CS1", ignore = true, moodleCourseId = 7, signIn = signIn, madeAtMs = madeAtMs)
        client.patchCourseIgnore("1151CS1", ignore = null, moodleCourseId = 7, signIn = signIn)
        client.patchCourseIgnore("1151CS1", ignore = false, moodleCourseId = 0, signIn = signIn)
        // Nothing to say: nothing goes.
        client.patchCourseIgnore("1151CS1", ignore = null, moodleCourseId = null, signIn = signIn)

        val path = "$v3/sync/courses/1151CS1/override"
        assertEquals(
            listOf(
                listOf("PATCH", path, listOf(bearer), json, """{"ignore_assignments":true,"moodle_course_id":7,"hlc":1790000000123000}"""),
                listOf("PATCH", path, listOf(bearer), json, """{"moodle_course_id":7}"""),
                listOf("PATCH", path, listOf(bearer), json, """{"ignore_assignments":false}"""),
            ),
            shapes(),
        )
    }

    @Test
    fun `each stamped write refused as ahead goes once more without its stamp`() = runTest {
        backend.answer("/v3/sync/holiday-overrides/3", 409, clockAhead)
        backend.answer("/v3/sync/assignments/7/override", 409, clockAhead)
        backend.answer("/v3/sync/courses/1151CS1/override", 409, clockAhead)
        backend.answer("/v3/sync/courses/1151CS1/override", 200, "{}")
        backend.answer("/v3/sync/courses/1151CS1/override", 409, clockAhead)
        backend.answer("/v3/sync/courses/client:1151:CS1", 409, clockAhead)

        client.putHolidayOverride(3, notify = true, signIn = signIn, madeAtMs = madeAtMs)
        client.patchAssignmentOverride(7, "ignored", signIn, madeAtMs = madeAtMs)
        client.patchCourseOverride("1151CS1", signIn, colorHex = "#FF0000", madeAtMs = madeAtMs)
        client.patchCourseIgnore("1151CS1", ignore = true, moodleCourseId = null, signIn = signIn, madeAtMs = madeAtMs)
        client.deleteCourse("client:1151:CS1", signIn, hiddenAt = madeAtMs)

        val override = "$v3/sync/courses/1151CS1/override"
        assertEquals(
            listOf(
                listOf("PUT", "$v3/sync/holiday-overrides/3", listOf(bearer), json, """{"notify":true,"hlc":1790000000123000}"""),
                listOf("PUT", "$v3/sync/holiday-overrides/3", listOf(bearer), json, """{"notify":true}"""),
                listOf("PATCH", "$v3/sync/assignments/7/override", listOf(bearer), json, """{"local_status":"ignored","hlc":1790000000123000}"""),
                listOf("PATCH", "$v3/sync/assignments/7/override", listOf(bearer), json, """{"local_status":"ignored"}"""),
                listOf("PATCH", override, listOf(bearer), json, """{"color_hex":"#FF0000","hlc":1790000000123000}"""),
                listOf("PATCH", override, listOf(bearer), json, """{"color_hex":"#FF0000"}"""),
                listOf("PATCH", override, listOf(bearer), json, """{"ignore_assignments":true,"hlc":1790000000123000}"""),
                listOf("PATCH", override, listOf(bearer), json, """{"ignore_assignments":true}"""),
                listOf("DELETE", "$v3/sync/courses/client:1151:CS1?hlc=1790000000123000", listOf(bearer), null, ""),
                listOf("DELETE", "$v3/sync/courses/client:1151:CS1", listOf(bearer), null, ""),
            ),
            shapes(),
        )
    }

    @Test
    fun `a course delete goes by its key, with the hide's time when known`() = runTest {
        client.deleteCourse("client:1151:CS1", signIn, hiddenAt = madeAtMs)
        client.deleteCourse("client:1151:CS2", signIn)

        assertEquals(
            listOf(
                listOf("DELETE", "$v3/sync/courses/client:1151:CS1?hlc=1790000000123000", listOf(bearer), null, ""),
                listOf("DELETE", "$v3/sync/courses/client:1151:CS2", listOf(bearer), null, ""),
            ),
            shapes(),
        )
    }

    @Test
    fun `a reset is a DELETE of one term, its name form-encoded, or of every term`() = runTest {
        client.deleteAllCourses("1151", signIn)
        client.deleteAllCourses("1151 x/y", signIn)
        client.deleteAllCourses(null, signIn)

        assertEquals(
            listOf(
                listOf("DELETE", "$v3/sync/courses?semester=1151", listOf(bearer), null, ""),
                listOf("DELETE", "$v3/sync/courses?semester=1151+x%2Fy", listOf(bearer), null, ""),
                listOf("DELETE", "$v3/sync/courses", listOf(bearer), null, ""),
            ),
            shapes(),
        )
    }

    private val course = Course(
        courseNo = "CS1",
        courseName = "Algorithms",
        instructor = "A, B、C",
        classroom = "TR-313",
        credits = 3f,
        scheduleJson = """{"2":["3","4"]}""",
        moodleIdNumber = "1151CS1",
        customColorHex = "#FF0000",
    )

    private val courseJson =
        """{"semester":"1151","course_no":"CS1","course_name":"Algorithms","moodle_id":"1151CS1","credits":3.0,""" +
            """"classroom":"TR-313","instructors":["A","B","C"],"schedule_json":{"2":["3","4"]},"classroom_map":{}}"""

    @Test
    fun `a course upload carries the term's courses and their custom colors, with no protocol`() = runTest {
        client.uploadCourses(listOf(course), "1151", signIn, forceKeys = listOf("client:1151:CS1"), rosterTerms = listOf("1151"))

        assertEquals(
            listOf(
                listOf(
                    "POST", "$v3/sync/courses/upload", listOf(bearer), json,
                    """{"courses":[$courseJson],"course_overrides":[{"course_key":"client:1151:CS1","color_hex":"#FF0000"}],""" +
                        """"force_keys":["client:1151:CS1"],"roster_terms":["1151"]}""",
                ),
            ),
            shapes(),
        )
    }

    @Test
    fun `an upload with timed adds goes under protocol 2, and unstamped once refused as ahead`() = runTest {
        backend.answer("/v3/sync/courses/upload", 409, clockAhead)

        client.uploadCoursesAnswered(listOf(course), "1151", signIn, forceKeys = listOf("client:1151:CS1"), addedAt = mapOf("CS1" to madeAtMs))

        val plain = """{"courses":[$courseJson],"course_overrides":[{"course_key":"client:1151:CS1","color_hex":"#FF0000"}],""" +
            """"force_keys":["client:1151:CS1"]"""
        assertEquals(
            listOf(
                listOf(
                    "POST", "$v3/sync/courses/upload", listOf(bearer, "X-TigerSync-Protocol" to "2"), json,
                    """$plain,"adds":[{"course_key":"client:1151:CS1","hlc":1790000000123000}]}""",
                ),
                listOf("POST", "$v3/sync/courses/upload", listOf(bearer), json, "$plain}"),
            ),
            shapes(),
        )
    }

    @Test
    fun `an assignment upload carries the list as the backend stores it`() = runTest {
        client.uploadAssignments(listOf(Assignment("7", "CS1", "Algorithms", "HW1", Date(0))), signIn)

        assertEquals("POST", backend.sent.single().method)
        assertEquals("$v3/sync/assignments/upload", backend.sent.single().url)
        assertEquals(listOf(bearer), backend.sent.single().headers)
        assertEquals(json, backend.sent.single().contentType)
        assertEquals(
            """{"assignments":[{"moodle_assignment_id":7,"course_no":"CS1","course_name":"Algorithms","title":"HW1",""" +
                """"due_at":"1970-01-01T00:00:00Z","is_submitted":false}]}""",
            backend.sent.single().body,
        )
    }

    @Test
    fun `a refused write throws its status in its message, and its status where it always kept one`() = runTest {
        listOf(
            "/v3/sync/holiday-overrides/3", "/v3/sync/assignments/7/override", "/v3/sync/courses/1151CS1/override",
            "/v3/sync/courses/1151CS1/override", "/v3/sync/courses/client:1151:CS1", "/v3/sync/courses",
            "/v3/sync/courses/upload",
        ).forEach { backend.answer(it, 404, """{"detail":"course_not_found"}""") }

        assertEquals("holiday override failed: HTTP 404" to null, failure { client.putHolidayOverride(3, true, signIn) })
        assertEquals("patchAssignmentOverride failed: HTTP 404" to 404, failure { client.patchAssignmentOverride(7, "ignored", signIn) })
        assertEquals(
            "patchCourseOverride failed: HTTP 404" to 404,
            failure { client.patchCourseOverride("1151CS1", signIn, colorHex = "#FF0000") },
        )
        assertEquals(
            "patchCourseIgnore failed: HTTP 404" to 404,
            failure { client.patchCourseIgnore("1151CS1", true, null, signIn) },
        )
        assertEquals("deleteCourse failed: HTTP 404" to 404, failure { client.deleteCourse("client:1151:CS1", signIn) })
        assertEquals("deleteAllCourses failed: HTTP 404" to null, failure { client.deleteAllCourses("1151", signIn) })
        assertEquals("uploadCourses failed: HTTP 404" to 404, failure { client.uploadCourses(listOf(course), "1151", signIn) })
    }

    @Test
    fun `what a write says it did, from today's answers`() = runTest {
        backend.answer("/v3/sync/courses/1151CS1/override", 200, "{}")
        backend.answer("/v3/sync/courses/1151CS1/override", 200, """{"ignore_assignments":true}""")
        // A backend that predates the field drops it unread.
        backend.answer("/v3/sync/courses/1151CS1/override", 200, """{"color_hex":null}""")
        backend.answer("/v3/sync/courses/upload", 200, """{"accepted_keys":["client:1151:CS1"],"adds":[{"course_key":"client:1151:CS2","applied":false}]}""")

        assertEquals(true, client.putHolidayOverride(3, true, signIn))
        assertEquals(true, client.patchAssignmentOverride(7, "ignored", signIn))
        assertEquals(true, client.patchCourseOverride("1151CS1", signIn, colorHex = "#FF0000"))
        assertEquals(true, client.patchCourseIgnore("1151CS1", true, null, signIn))
        assertEquals(false, client.patchCourseIgnore("1151CS1", true, null, signIn))
        assertEquals(
            CourseUploadAnswer(listOf("CS1"), listOf("CS2")),
            client.uploadCoursesAnswered(listOf(course), "1151", signIn, addedAt = mapOf("CS2" to madeAtMs)),
        )
    }
}
