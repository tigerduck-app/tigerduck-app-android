package org.ntust.app.tigerduck.push

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.backend.BackendError
import org.ntust.app.tigerduck.network.backend.backendError
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.sync.EditTime
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn

/**
 * Every `/v4` write sends `age_ms`, where `hlc` went, and never an `hlc` (backend 4.3.0), and a
 * 409 is answered as any refusal: never sent again.
 */
class AgeMsWritesTest {

    private val v4 = "http://127.0.0.1:9/v4"
    private val backend = RecordingBackend()
    private val prefs = AppPreferences(JvmContext())
    private val client = pushApiClientAt(v4, backend.client, prefs)
    private val signIn = testSignIn()
    private val key = CourseRef(courseKey = "client:1151:CS1")
    private val madeAtMs = System.currentTimeMillis() - 60_000

    @Before
    fun syncs() {
        assumeTrue(prefs.cloudSyncEnabled)
    }

    // The request's age and stamp, from its body or its query.
    private fun stamps(sent: RecordingBackend.Sent): Pair<Long?, Long?> {
        val body = sent.body?.takeIf { it.isNotEmpty() }?.let(::JSONObject)
        fun of(name: String): Long? =
            (body?.opt(name) as? Number)?.toLong() ?: Regex("[?&]$name=(\\d+)").find(sent.url)?.groupValues?.get(1)?.toLong()
        return of("age_ms") to of("hlc")
    }

    @Test
    fun `each write sends its age and no hlc`() = runTest {
        client.putHolidayOverrideAnswered(3, notify = true, signIn = signIn, madeAt = EditTime(madeAtMs))
        client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = EditTime(madeAtMs))
        client.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000", madeAt = EditTime(madeAtMs))
        client.patchCourseOverrideAnswered(key, signIn, customName = "Algo", locale = "en", madeAt = EditTime(madeAtMs))
        client.patchCourseIgnoreAnswered(key, ignore = true, moodleCourseId = 7, signIn = signIn, madeAt = EditTime(madeAtMs))
        client.deleteCourseAnswered("client:1151:CS1", signIn, hiddenAt = EditTime(madeAtMs))
        client.deleteAllCoursesAnswered("1151", signIn, resetAt = EditTime(madeAtMs))

        assertEquals(7, backend.sent.size)
        for (sent in backend.sent) {
            val (age, hlc) = stamps(sent)
            assertEquals("${sent.method} ${sent.url}: no hlc", null, hlc)
            assertTrue("${sent.method} ${sent.url}: an age of a minute, got $age", age != null && age in 60_000L..120_000L)
        }
    }

    @Test
    fun `a course add goes with its age and no hlc`() = runTest {
        val added = Course(courseNo = "XA0000009", courseName = "Mine", isManual = true)

        client.uploadCoursesAnswered(listOf(added), "1151", signIn, addedAt = mapOf("XA0000009" to EditTime(madeAtMs)))

        val add = JSONObject(backend.sent.single().body!!).getJSONArray("adds").getJSONObject(0)
        assertEquals(setOf("course_key", "age_ms"), add.keys().asSequence().toSet())
        assertTrue(add.getLong("age_ms") in 60_000L..120_000L)
    }

    @Test
    fun `a 409 is a refusal like any other, never sent again`() = runTest {
        val writes: List<Pair<String, suspend () -> Any?>> = listOf(
            "/v4/sync/holiday-overrides/3" to { client.putHolidayOverrideAnswered(3, true, signIn, madeAt = EditTime(madeAtMs)) },
            "/v4/sync/assignments/7/override" to { client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = EditTime(madeAtMs)) },
            "/v4/sync/courses/client:1151:CS1/override" to { client.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000") },
            "/v4/sync/courses/client:1151:CS1" to { client.deleteCourseAnswered("client:1151:CS1", signIn, hiddenAt = EditTime(madeAtMs)) },
            "/v4/sync/courses" to { client.deleteAllCoursesAnswered("1151", signIn, resetAt = EditTime(madeAtMs)) },
            "/v4/sync/courses/upload" to {
                client.uploadCoursesAnswered(listOf(Course(courseNo = "XA1", courseName = "Mine", isManual = true)), "1151", signIn, addedAt = mapOf("XA1" to EditTime(madeAtMs)))
            },
        )
        val clock = mapOf("X-TigerSync-Clock" to "${(madeAtMs - 3_600_000) * 1_000}")
        for ((path, write) in writes) {
            backend.sent.clear()
            backend.answer(path, 409, syncFixture("v4/error-clock-ahead.json"), clock)

            val failure = runCatching { write() }.exceptionOrNull()

            assertEquals(path, 1, backend.sent.size)
            assertEquals(path, BackendError(409, "clock_ahead"), failure?.backendError())
        }
    }
}
