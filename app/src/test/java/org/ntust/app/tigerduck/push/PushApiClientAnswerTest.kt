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
 * Each sync write's answered form ([WriteAnswer]): the backend's clock from the reply's header, and
 * its verdict and values from the reply's body (the backend's `server/routes/overrides.py` on
 * `main`, whose answer `/v4` keeps). A write a switch stopped answers not sent, and a refusal
 * keeps the backend's code. What each sends, and the contract's other answers, are
 * [V4WritesTest]'s.
 */
class PushApiClientAnswerTest {

    private val v4 = "http://127.0.0.1:9/v4"
    private val backend = RecordingBackend()
    private val prefs = AppPreferences(JvmContext())
    private val client = pushApiClientAt(v4, backend.client, prefs)
    private val signIn = testSignIn()

    private val clock = mapOf("X-TigerSync-Clock" to "1792900000000123")
    private val key = CourseRef(courseKey = "client:1151:CS1")
    private val override = "/v4/sync/courses/client:1151:CS1/override"

    @Before
    fun syncs() {
        // TigerSync is off for good on F-Droid, where none of these goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
    }

    @Test
    fun `a course override's answer names the fields the backend took, and its values`() = runTest {
        backend.answer(
            override, 200,
            """{"id":3,"color_hex":"#FF0000","custom_names":{"en":"Algo"},"ignore_assignments":false,"updated_at":"t",""" +
                """"color_hex_hlc":1792900000000000,"custom_names_hlc":{"en":1792800000000000},"ignore_assignments_hlc":null,"applied":["color_hex"]}""",
            clock,
        )

        val answer = client.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000")

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
        backend.answer(override, 200, """{"ignore_assignments":true,"applied":["ignore_assignments"]}""")
        backend.answer(override, 200, """{"color_hex":null}""")

        val stored = client.patchCourseIgnoreAnswered(key, ignore = true, moodleCourseId = null, signIn = signIn, madeAtMs = null)
        val dropped = client.patchCourseIgnoreAnswered(key, ignore = true, moodleCourseId = null, signIn = signIn, madeAtMs = null)

        assertEquals(setOf("ignore_assignments"), stored.applied)
        assertEquals(true, stored.values["ignore_assignments"])
        assertEquals(null, dropped.applied)
        assertEquals(false, "ignore_assignments" in dropped.values)
    }

    @Test
    fun `a reply with no clock, one that is no time, or a body that cannot be read still went`() = runTest {
        backend.answer("/v4/sync/assignments/7/override", 200, "<html>portal</html>", mapOf("X-TigerSync-Clock" to "12"))
        backend.answer("/v4/sync/assignments/7/override", 200, "", mapOf("X-TigerSync-Clock" to "soon"))

        assertEquals(WriteAnswer(true, null, null, emptyMap()), client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAtMs = null))
        assertEquals(WriteAnswer(true, null, null, emptyMap()), client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAtMs = null))
    }

    @Test
    fun `a write its switch stops answers not sent, and sends nothing`() = runTest {
        prefs.syncAssignments = false
        prefs.syncCourses = false
        prefs.syncCourseColors = false

        assertEquals(WriteAnswer.NOT_SENT, client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAtMs = null))
        assertEquals(WriteAnswer.NOT_SENT, client.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000"))
        assertEquals(WriteAnswer.NOT_SENT, client.patchCourseIgnoreAnswered(key, true, null, signIn, madeAtMs = null))
        assertEquals(WriteAnswer.NOT_SENT, client.deleteCourseAnswered("client:1151:CS1", signIn, hiddenAt = null, kind = null))
        assertEquals(WriteAnswer.NOT_SENT, client.deleteAllCoursesAnswered("1151", signIn, resetAtMs = null))
        prefs.cloudSyncEnabled = false
        assertEquals(WriteAnswer.NOT_SENT, client.putHolidayOverrideAnswered(3, true, signIn, madeAtMs = null))
        assertEquals(emptyList<Any>(), backend.sent)
    }

    @Test
    fun `a refusal keeps the backend's code`() = runTest {
        backend.answer(override, 404, """{"error":"course_not_found","message":"No course has this key."}""")

        val answered = runCatching { client.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000") }.exceptionOrNull()

        assertTrue("got $answered", answered is BackendException)
        assertEquals(BackendError(404, "course_not_found"), answered?.backendError())
        assertEquals("patchCourseOverride failed: HTTP 404", answered?.message)
    }

    @Test
    fun `an upload's answer carries the backend's clock too`() = runTest {
        backend.answer("/v4/sync/courses/upload", 200, """{"accepted_keys":["client:1151:CS1"]}""", clock)

        val answer = client.uploadCoursesAnswered(listOf(Course(courseNo = "CS1", courseName = "CS1")), "1151", signIn)

        assertEquals(CourseUploadAnswer(listOf("CS1"), emptyList(), 1792900000000123L), answer)
    }
}
