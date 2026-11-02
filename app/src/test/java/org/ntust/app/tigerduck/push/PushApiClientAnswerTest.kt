package org.ntust.app.tigerduck.push

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.backend.BackendError
import org.ntust.app.tigerduck.network.backend.BackendException
import org.ntust.app.tigerduck.network.backend.backendError
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn

/**
 * Each sync write's answered form on `/v3` ([WriteAnswer]): the backend's clock from the reply's
 * header, and its verdict and values from the bodies `/v3` sends today (the backend's
 * `server/routes/overrides.py`, `holiday_overrides.py` and `sync/courses.py` on `main`). A write a
 * switch stopped answers not sent, and a refusal keeps the backend's code. What each sends is
 * [V3SyncWritesTest]'s.
 */
class PushApiClientAnswerTest {

    private val v3 = "http://127.0.0.1:9/v3"
    private val backend = RecordingBackend()
    private val prefs = AppPreferences(JvmContext())
    private val client = pushApiClientAt(v3, backend.client, prefs)
    private val signIn = testSignIn()

    private val clock = mapOf("X-TigerSync-Clock" to "1792900000000123")
    private val moodleId = CourseRef(moodleId = "1151CS1", courseKey = null)

    @Before
    fun syncs() {
        // TigerSync is off for good on F-Droid, where none of these goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
    }

    @Test
    fun `a mark's answer is its status, its stamp and whether the backend took it, a clear's too`() = runTest {
        val path = "/v3/sync/assignments/7/override"
        backend.answer(path, 200, """{"id":5,"updated_at":"2026-11-02T00:00:00+00:00","local_status":"ignored","local_status_hlc":1792900000000000,"applied":true}""", clock)
        backend.answer(path, 200, """{"deleted":true,"local_status":"archived","local_status_hlc":1792900000000500,"applied":false}""")

        val set = client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAtMs = null)
        val clear = client.patchAssignmentOverrideAnswered(7, "none", signIn, madeAtMs = null)

        assertEquals(WriteAnswer(true, 1792900000000123L, setOf("local_status"), mapOf(
            "id" to 5, "updated_at" to "2026-11-02T00:00:00+00:00", "local_status" to "ignored",
            "local_status_hlc" to 1792900000000000L, "applied" to true,
        )), set)
        assertEquals(WriteAnswer(true, null, emptySet(), mapOf(
            "deleted" to true, "local_status" to "archived", "local_status_hlc" to 1792900000000500L, "applied" to false,
        )), clear)
    }

    @Test
    fun `a course override's answer names the fields the backend took, and its values`() = runTest {
        backend.answer(
            "/v3/sync/courses/1151CS1/override", 200,
            """{"id":3,"color_hex":"#FF0000","custom_names":{"en":"Algo"},"ignore_assignments":false,"updated_at":"t",""" +
                """"color_hex_hlc":1792900000000000,"custom_names_hlc":{"en":1792800000000000},"ignore_assignments_hlc":null,"applied":["color_hex"]}""",
            clock,
        )

        val answer = client.patchCourseOverrideAnswered(moodleId, signIn, colorHex = "#FF0000")

        assertEquals(true, answer.sent)
        assertEquals(1792900000000123L, answer.serverClockUs)
        assertEquals(setOf("color_hex"), answer.applied)
        assertEquals(mapOf("en" to "Algo"), answer.values["custom_names"])
        assertEquals(mapOf("en" to 1792800000000000L), answer.values["custom_names_hlc"])
        // A JSON null is kept under its name.
        assertTrue(answer.values.containsKey("ignore_assignments_hlc"))
        assertEquals(null, answer.values["ignore_assignments_hlc"])
    }

    @Test
    fun `an ignore's answer keeps the field a backend that predates it leaves out`() = runTest {
        backend.answer("/v3/sync/courses/1151CS1/override", 200, """{"ignore_assignments":true,"applied":["ignore_assignments"]}""")
        backend.answer("/v3/sync/courses/1151CS1/override", 200, """{"color_hex":null}""")

        val stored = client.patchCourseIgnoreAnswered(moodleId, ignore = true, moodleCourseId = null, signIn = signIn, madeAtMs = null)
        val dropped = client.patchCourseIgnoreAnswered(moodleId, ignore = true, moodleCourseId = null, signIn = signIn, madeAtMs = null)

        assertEquals(setOf("ignore_assignments"), stored.applied)
        assertEquals(true, stored.values["ignore_assignments"])
        assertEquals(null, dropped.applied)
        assertEquals(false, "ignore_assignments" in dropped.values)
    }

    @Test
    fun `a holiday's, a delete's and a reset's answers, as v3 sends them`() = runTest {
        backend.answer("/v3/sync/holiday-overrides/3", 200, """{"holiday_id":3,"notify":false,"hlc":1792900000000000,"applied":false}""", clock)
        backend.answer("/v3/sync/courses/client:1151:CS1", 200, """{"deleted":1,"applied":true,"hlc":1792900000000000}""")
        backend.answer("/v3/sync/courses", 200, """{"deleted":2,"reset_hlc":1792900000000000}""")

        val holiday = client.putHolidayOverrideAnswered(3, notify = true, signIn = signIn, madeAtMs = null)
        val delete = client.deleteCourseAnswered("client:1151:CS1", signIn, hiddenAt = null, kind = "hide")
        val reset = client.deleteAllCoursesAnswered("1151", signIn, resetAtMs = 1_790_000_000_000L)

        assertEquals(WriteAnswer(true, 1792900000000123L, emptySet(), mapOf(
            "holiday_id" to 3, "notify" to false, "hlc" to 1792900000000000L, "applied" to false,
        )), holiday)
        assertEquals(setOf("membership"), delete.applied)
        assertEquals(1792900000000000L, delete.values["hlc"])
        // /v3's reset says nothing of whether it applied.
        // Typed, so the 2 stays an Int beside the Long, as the reply's are.
        assertEquals(WriteAnswer(true, null, null, mapOf<String, Any?>("deleted" to 2, "reset_hlc" to 1792900000000000L)), reset)
    }

    @Test
    fun `a reply with no clock, one that is no time, or a body that cannot be read still went`() = runTest {
        backend.answer("/v3/sync/assignments/7/override", 200, "<html>portal</html>", mapOf("X-TigerSync-Clock" to "12"))
        backend.answer("/v3/sync/assignments/7/override", 200, "", mapOf("X-TigerSync-Clock" to "soon"))

        assertEquals(WriteAnswer(true, null, null, emptyMap()), client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAtMs = null))
        assertEquals(WriteAnswer(true, null, null, emptyMap()), client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAtMs = null))
    }

    @Test
    fun `a write its switch stops answers not sent, and sends nothing`() = runTest {
        prefs.syncAssignments = false
        prefs.syncCourses = false
        prefs.syncCourseColors = false

        assertEquals(WriteAnswer.NOT_SENT, client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAtMs = null))
        assertEquals(WriteAnswer.NOT_SENT, client.patchCourseOverrideAnswered(moodleId, signIn, colorHex = "#FF0000"))
        assertEquals(WriteAnswer.NOT_SENT, client.patchCourseIgnoreAnswered(moodleId, true, null, signIn, madeAtMs = null))
        assertEquals(WriteAnswer.NOT_SENT, client.deleteCourseAnswered("client:1151:CS1", signIn, hiddenAt = null, kind = null))
        assertEquals(WriteAnswer.NOT_SENT, client.deleteAllCoursesAnswered("1151", signIn, resetAtMs = null))
        prefs.cloudSyncEnabled = false
        assertEquals(WriteAnswer.NOT_SENT, client.putHolidayOverrideAnswered(3, true, signIn, madeAtMs = null))
        assertEquals(emptyList<Any>(), backend.sent)
    }

    @Test
    fun `a refusal keeps the backend's code, where the Boolean forms keep only the status`() = runTest {
        backend.answer("/v3/sync/courses/1151CS1/override", 404, """{"detail":"course_not_found"}""")
        backend.answer("/v3/sync/courses/1151CS1/override", 404, """{"detail":"course_not_found"}""")

        val answered = runCatching { client.patchCourseOverrideAnswered(moodleId, signIn, colorHex = "#FF0000") }.exceptionOrNull()
        val plain = runCatching { client.patchCourseOverride("1151CS1", signIn, colorHex = "#FF0000") }.exceptionOrNull()

        assertTrue("got $answered", answered is BackendException)
        assertEquals(BackendError(404, "course_not_found"), answered?.backendError())
        assertEquals("patchCourseOverride failed: HTTP 404", answered?.message)
        assertTrue("got $plain", plain is PushApiException)
        assertEquals(BackendError(404, null), plain?.backendError())
    }

    @Test
    fun `a course with no Moodle id cannot be named on v3, and nothing goes`() = runTest {
        val unnamed = CourseRef(moodleId = null, courseKey = "client:1151:CS1")

        val override = runCatching { client.patchCourseOverrideAnswered(unnamed, signIn, colorHex = "#FF0000") }.exceptionOrNull()
        val ignore = runCatching { client.patchCourseIgnoreAnswered(unnamed, true, null, signIn, madeAtMs = null) }.exceptionOrNull()

        assertTrue("got $override", override is CourseKeyUnknownException)
        assertTrue("got $ignore", ignore is CourseKeyUnknownException)
        assertEquals(emptyList<Any>(), backend.sent)
    }

    @Test
    fun `an upload's answer carries the backend's clock too`() = runTest {
        backend.answer("/v3/sync/courses/upload", 200, """{"accepted_keys":["client:1151:CS1"]}""", clock)

        val answer = client.uploadCoursesAnswered(listOf(Course(courseNo = "CS1", courseName = "CS1")), "1151", signIn)

        assertEquals(CourseUploadAnswer(listOf("CS1"), emptyList(), 1792900000000123L), answer)
    }
}
