package org.ntust.app.tigerduck.push

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.sync.DeviceClocks
import org.ntust.app.tigerduck.sync.EditTime
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn

/**
 * The oldest value (LWW D13: a category's first sync, an add an upload names with no time, an
 * old-entry time of 0) goes as `hlc: 0`, with no `age_ms`, as before `age_ms`: it loses to every
 * stamp the backend holds, where an age would date it a year back. The backend keeps taking `hlc`
 * for zero stamps. Every other edit goes with its age, however old.
 */
class OldestWritesTest {

    private val v4 = "http://127.0.0.1:9/v4"
    private val backend = RecordingBackend()
    private val prefs = AppPreferences(JvmContext())
    private val client = pushApiClientAt(v4, backend.client, prefs, DeviceClocks.wallOnly { NOW_MS })
    private val signIn = testSignIn()
    private val key = CourseRef(courseKey = "client:1151:CS1")

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
    fun `a holiday choice of the oldest goes as hlc 0, with no age`() = runTest {
        client.putHolidayOverrideAnswered(3, notify = true, signIn = signIn, madeAt = EditTime.OLDEST)

        assertEquals(null to 0L, stamps(backend.sent.single()))
    }

    @Test
    fun `a mark of the oldest goes as hlc 0, with no age`() = runTest {
        client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = EditTime.OLDEST)

        assertEquals(null to 0L, stamps(backend.sent.single()))
    }

    @Test
    fun `a course color and a name of the oldest go as hlc 0, with no age`() = runTest {
        client.patchCourseOverrideAnswered(key, signIn, colorHex = "#FF0000", madeAt = EditTime.OLDEST)
        client.patchCourseOverrideAnswered(key, signIn, customName = "Calc", locale = "en", madeAt = EditTime.OLDEST)

        assertEquals(List(2) { null to 0L }, backend.sent.map(::stamps))
    }

    @Test
    fun `a course ignore of the oldest goes as hlc 0, with no age`() = runTest {
        client.patchCourseIgnoreAnswered(key, ignore = true, moodleCourseId = 7, signIn = signIn, madeAt = EditTime.OLDEST)

        assertEquals(null to 0L, stamps(backend.sent.single()))
    }

    @Test
    fun `a course delete of the oldest goes as hlc 0 in its query, with no age`() = runTest {
        client.deleteCourseAnswered("client:1151:CS1", signIn, hiddenAt = EditTime.OLDEST)

        assertEquals("$v4/sync/courses/client:1151:CS1?hlc=0", backend.sent.single().url)
    }

    @Test
    fun `an upload names an add of no time as the oldest, hlc 0, and a timed one with its age`() = runTest {
        val added = Course(courseNo = "XA0000009", courseName = "Mine", isManual = true)
        val before = Course(courseNo = "XA0000008", courseName = "Mine too", isManual = true)

        client.uploadCoursesAnswered(listOf(added, before), "1151", signIn, addedAt = mapOf("XA0000009" to EditTime(NOW_MS - 500)))

        val adds = JSONObject(backend.sent.single().body!!).getJSONArray("adds")
        assertEquals(
            listOf(mapOf("course_key" to "client:1151:XA0000009", "age_ms" to 500L), mapOf("course_key" to "client:1151:XA0000008", "hlc" to 0L)),
            (0 until adds.length()).map { i -> adds.getJSONObject(i).let { o -> o.keys().asSequence().associateWith { (o.get(it) as? Number)?.toLong() ?: o.get(it) } } },
        )
    }

    @Test
    fun `an edit at any other time, however old, goes with its age`() = runTest {
        client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = EditTime(1L))

        assertEquals(NOW_MS - 1 to null, stamps(backend.sent.single()))
    }

    private companion object {
        const val NOW_MS = 1_790_000_000_000L
    }
}
