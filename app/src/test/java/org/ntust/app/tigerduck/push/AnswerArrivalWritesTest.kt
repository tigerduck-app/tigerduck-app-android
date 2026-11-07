package org.ntust.app.tigerduck.push

import kotlinx.coroutines.launch
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
import org.ntust.app.tigerduck.sync.outbox.AnswerArrivals
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn

/**
 * The per-key rule of `age_ms` at the client, where every write of a synced field goes, from an
 * outbox lane or straight from a screen (Use Local, a conflict's keep-local): the answer to each
 * write keeps its arrival under the field the write names, its resolved course key's, before the
 * call returns, and so before a hold its caller releases; and an edit of that field made while the
 * write was out counts its age from that arrival, whichever write sends it.
 */
class AnswerArrivalWritesTest {

    private val v4 = "http://127.0.0.1:9/v4"

    private val backend = RecordingBackend()
    private val prefs = AppPreferences(JvmContext())
    @Volatile private var elapsedMs = 1_000L
    private val clocks = DeviceClocks({ 1_790_000_000_000L + elapsedMs }, { elapsedMs }, { 7 })
    private val client = pushApiClientAt(v4, backend.client, prefs, clocks, arrivals = AnswerArrivals.inMemory(clocks))
    private val signIn = testSignIn()
    private val course = CourseRef(courseKey = "client:1151:CS1")
    private val added = Course(courseNo = "XA1", courseName = "Mine", isManual = true)

    @Before
    fun syncs() {
        assumeTrue(prefs.cloudSyncEnabled)
    }

    // The age the last request sent: in its body, in its query, or in its one add.
    private fun lastAge(): Long? {
        val sent = backend.sent.last()
        val body = sent.body?.takeIf { it.isNotEmpty() }?.let(::JSONObject)
        val add = body?.optJSONArray("adds")?.optJSONObject(0)
        return ((add ?: body)?.opt("age_ms") as? Number)?.toLong()
            ?: Regex("[?&]age_ms=(\\d+)").find(sent.url)?.groupValues?.get(1)?.toLong()
    }

    /**
     * [first] goes out made now, as Use Local's writes do, and is held; an edit is made 0.2 s into it,
     * its answer arrives 3 s after that, and [second] sends the edit half a second after the answer.
     * The age [second] sent.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.secondAge(first: suspend () -> Unit, second: suspend (EditTime) -> Unit): Long? {
        val held = backend.holdNext()
        val sending = backgroundScope.launch { first() }
        held.out.await()
        elapsedMs += 200
        val edit = clocks.now()
        elapsedMs += 3_000
        held.release()
        sending.join()
        elapsedMs += 500
        second(edit)
        return lastAge()
    }

    @Test
    fun `a mark tapped while Use Local's PATCH of it is out counts its age from that answer`() = runTest {
        val age = secondAge(
            first = { client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = null) },
            second = { client.patchAssignmentOverrideAnswered(7, "none", signIn, madeAt = it) },
        )

        assertEquals("from the answer, half a second ago, not the tap's 3.5 s", 500L, age)
    }

    @Test
    fun `every write of a field keeps its answer's arrival for the next edit of it`() = runTest {
        val writes: List<Pair<String, Pair<suspend () -> Unit, suspend (EditTime) -> Unit>>> = listOf(
            "holiday" to Pair(
                { client.putHolidayOverrideAnswered(3, true, signIn, madeAt = null) },
                { client.putHolidayOverrideAnswered(3, false, signIn, madeAt = it) },
            ),
            "color" to Pair(
                { client.patchCourseOverrideAnswered(course, signIn, colorHex = "#FF0000") },
                { client.patchCourseOverrideAnswered(course, signIn, colorHex = "#00FF00", madeAt = it) },
            ),
            "name" to Pair(
                { client.patchCourseOverrideAnswered(course, signIn, customName = "Algo", locale = "en") },
                { client.patchCourseOverrideAnswered(course, signIn, customName = "Algorithms", locale = "en", madeAt = it) },
            ),
            "ignore" to Pair(
                { client.patchCourseIgnoreAnswered(course, ignore = true, moodleCourseId = null, signIn = signIn, madeAt = null) },
                { client.patchCourseIgnoreAnswered(course, ignore = false, moodleCourseId = null, signIn = signIn, madeAt = it) },
            ),
            "a hide after Use Local's add" to Pair(
                { client.uploadCoursesAnswered(listOf(added), "1151", signIn, addedAt = mapOf("XA1" to client.editedNow())) },
                { client.deleteCourseAnswered("client:1151:XA1", signIn, hiddenAt = it) },
            ),
            "an add after a hide" to Pair(
                { client.deleteCourseAnswered("client:1151:XA1", signIn, hiddenAt = null) },
                { client.uploadCoursesAnswered(listOf(added), "1151", signIn, forceKeys = listOf("XA1"), addedAt = mapOf("XA1" to it)) },
            ),
        )
        for ((name, write) in writes) {
            assertEquals(name, 500L, secondAge(write.first, write.second))
        }
    }

    @Test
    fun `an edit of another field, or made after the answer, keeps its own time`() = runTest {
        val other = secondAge(
            first = { client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = null) },
            second = { client.patchAssignmentOverrideAnswered(8, "none", signIn, madeAt = it) },
        )
        val name = secondAge(
            first = { client.patchCourseOverrideAnswered(course, signIn, customName = "Algo", locale = "en") },
            second = { client.patchCourseOverrideAnswered(course, signIn, customName = "演算法", locale = "zh-Hant", madeAt = it) },
        )
        client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = null)
        val after = clocks.now()
        elapsedMs += 700
        client.patchAssignmentOverrideAnswered(7, "none", signIn, madeAt = after)

        assertEquals(3_500L, other)
        assertEquals("another locale's name is another field", 3_500L, name)
        assertEquals(700L, lastAge())
    }

    @Test
    fun `the oldest value's write keeps no arrival, its hlc 0 never landing after a later edit`() = runTest {
        val mark = secondAge(
            first = { client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = EditTime.OLDEST) },
            second = { client.patchAssignmentOverrideAnswered(7, "none", signIn, madeAt = it) },
        )
        // A school roster names a hand-added course as the oldest.
        val add = secondAge(
            first = { client.uploadCoursesAnswered(listOf(added), "1151", signIn, rosterTerms = listOf("1151")) },
            second = { client.deleteCourseAnswered("client:1151:XA1", signIn, hiddenAt = it) },
        )

        assertEquals(3_500L, mark)
        assertEquals(3_500L, add)
    }

    @Test
    fun `a refused write keeps no arrival`() = runTest {
        backend.answer("/v4/sync/assignments/7/override", 422, """{"detail":{"code":"bad_status"}}""")
        val age = secondAge(
            first = { runCatching { client.patchAssignmentOverrideAnswered(7, "bogus", signIn, madeAt = null) } },
            second = { client.patchAssignmentOverrideAnswered(7, "none", signIn, madeAt = it) },
        )

        assertEquals(3_500L, age)
    }
}
