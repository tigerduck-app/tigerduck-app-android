package org.ntust.app.tigerduck.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
 * The per-key rule of `age_ms` as the iPhone's (tigerduck-app#1008), at the client, where every
 * write of a synced field goes. A write's span runs from its first age reading, the token in hand,
 * to its 2xx answer; an edit ages from the latest end among the spans of its key that started at or
 * before it was made, and an edit made before a span went out keeps its own age. One key per
 * assignment and per holiday, and one per term for every course write of it and its reset. The
 * writes of one key go one at a time, from the age read to the end kept.
 */
class WriteSpansTest {

    private val backend = RecordingBackend()
    private val prefs = AppPreferences(JvmContext())
    @Volatile private var elapsedMs = 1_000L
    private val clocks = DeviceClocks({ 1_790_000_000_000L + elapsedMs }, { elapsedMs }, { 7 })
    private val client = pushApiClientAt("http://127.0.0.1:9/v4", backend.client, prefs, clocks)
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
        return ((add ?: body)?.opt("age_ms") as? Number)?.toLong() ?: Regex("[?&]age_ms=(\\d+)").find(sent.url)?.groupValues?.get(1)?.toLong()
    }

    private fun RecordingBackend.Sent.path(): String = url.substringAfter("127.0.0.1:9").substringBefore('?')

    /**
     * [first] goes out made now and is held; an edit is made 0.2 s into it, its answer arrives 3 s
     * after that, and [second] sends the edit half a second after the answer. The age [second] sent.
     */
    private suspend fun TestScope.secondAge(first: suspend () -> Unit, second: suspend (EditTime) -> Unit): Long? {
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
    fun `an edit queued before a later write of its key went out keeps its own age, and loses to it`() = runTest {
        val offline = clocks.now()
        elapsedMs += 5_000
        val held = backend.holdNext()
        val direct = backgroundScope.launch { client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = null) }
        held.out.await()
        elapsedMs += 1_000
        held.release()
        direct.join()
        elapsedMs += 1_000

        client.patchAssignmentOverrideAnswered(7, "none", signIn, madeAt = offline)

        assertEquals("from its own time, 7 s, so older than the write sent at 5 s", 7_000L, lastAge())
    }

    @Test
    fun `a color made while its term's reset is out ages from the reset's end`() = runTest {
        val age = secondAge(
            first = { client.deleteAllCoursesAnswered("1151", signIn, resetAt = null) },
            second = { client.patchCourseOverrideAnswered(course, signIn, colorHex = "#00FF00", madeAt = it) },
        )

        assertEquals(500L, age)
    }

    @Test
    fun `a name made while a color of its term is out ages from that color's end`() = runTest {
        val age = secondAge(
            first = { client.patchCourseOverrideAnswered(CourseRef("client:1151:CS2"), signIn, colorHex = "#FF0000") },
            second = { client.patchCourseOverrideAnswered(course, signIn, customName = "演算法", locale = "zh-Hant", madeAt = it) },
        )

        assertEquals(500L, age)
    }

    @Test
    fun `one term's writes go one at a time, another term's and a mark at once`() = runTest {
        val held = backend.holdNext()
        val color = backgroundScope.launch { client.patchCourseOverrideAnswered(course, signIn, colorHex = "#FF0000") }
        held.out.await()
        val name = backgroundScope.launch { client.patchCourseOverrideAnswered(course, signIn, customName = "Algo", locale = "en") }

        client.patchCourseOverrideAnswered(CourseRef("client:1142:CS1"), signIn, colorHex = "#0000FF")
        client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = null)
        withContext(Dispatchers.Default) { delay(200) }

        assertEquals("the name waits for its term's color", listOf("/v4/sync/courses/client:1142:CS1/override", "/v4/sync/assignments/7/override"), backend.sent.map { it.path() })
        held.release()
        color.join()
        name.join()
        assertEquals(4, backend.sent.size)
    }

    @Test
    fun `a write answered 401 ends no span, so an edit made while it was out keeps its own age`() = runTest {
        // No write replays itself after a 401 here: the sync signs in again and sends anew, a span of
        // its own, its age read after the new token.
        backend.answer("/v4/sync/assignments/7/override", 401, """{"detail":{"code":"invalid_token"}}""")
        val age = secondAge(
            first = { runCatching { client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = null) } },
            second = { client.patchAssignmentOverrideAnswered(7, "none", signIn, madeAt = it) },
        )

        assertEquals(3_500L, age)
    }

    @Test
    fun `a mark tapped while Use Local's PATCH of it is out ages from that PATCH's end`() = runTest {
        val age = secondAge(
            first = { client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = null) },
            second = { client.patchAssignmentOverrideAnswered(7, "none", signIn, madeAt = it) },
        )

        assertEquals("from the end, half a second ago, not the tap's 3.5 s", 500L, age)
    }

    @Test
    fun `every write of a key ends a span for the next edit of it`() = runTest {
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
            "an add during its term's reset" to Pair(
                { client.deleteAllCoursesAnswered("1151", signIn, resetAt = null) },
                { client.uploadCoursesAnswered(listOf(added), "1151", signIn, forceKeys = listOf("XA1"), addedAt = mapOf("XA1" to it)) },
            ),
            "a hide during its term's reset" to Pair(
                { client.deleteAllCoursesAnswered("1151", signIn, resetAt = null) },
                { client.deleteCourseAnswered("client:1151:XA2", signIn, hiddenAt = it) },
            ),
        )
        for ((name, write) in writes) {
            assertEquals(name, 500L, secondAge(write.first, write.second))
        }
    }

    @Test
    fun `an edit of another key, or made after the end, keeps its own time`() = runTest {
        val otherMark = secondAge(
            first = { client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = null) },
            second = { client.patchAssignmentOverrideAnswered(8, "none", signIn, madeAt = it) },
        )
        val otherTerm = secondAge(
            first = { client.deleteAllCoursesAnswered("1151", signIn, resetAt = null) },
            second = { client.deleteCourseAnswered("client:1142:XA2", signIn, hiddenAt = it) },
        )
        client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = null)
        val after = clocks.now()
        elapsedMs += 700
        client.patchAssignmentOverrideAnswered(7, "none", signIn, madeAt = after)

        assertEquals(3_500L, otherMark)
        assertEquals("another term's reset is not this one's", 3_500L, otherTerm)
        assertEquals(700L, lastAge())
    }

    @Test
    fun `a refused write, and the oldest value's, end no span`() = runTest {
        backend.answer("/v4/sync/assignments/7/override", 422, """{"detail":{"code":"bad_status"}}""")
        val refused = secondAge(
            first = { runCatching { client.patchAssignmentOverrideAnswered(7, "bogus", signIn, madeAt = null) } },
            second = { client.patchAssignmentOverrideAnswered(7, "none", signIn, madeAt = it) },
        )
        val oldest = secondAge(
            first = { client.patchAssignmentOverrideAnswered(8, "ignored", signIn, madeAt = EditTime.OLDEST) },
            second = { client.patchAssignmentOverrideAnswered(8, "none", signIn, madeAt = it) },
        )
        // A school roster names a hand-added course as the oldest.
        val roster = secondAge(
            first = { client.uploadCoursesAnswered(listOf(added), "1151", signIn, rosterTerms = listOf("1151")) },
            second = { client.deleteCourseAnswered("client:1151:XA1", signIn, hiddenAt = it) },
        )

        assertEquals(3_500L, refused)
        assertEquals("its hlc 0 never lands after a later edit", 3_500L, oldest)
        assertEquals(3_500L, roster)
    }

    @Test
    fun `a lane's write sent while a screen's write of its key is out goes after its end, aged from it`() = runTest {
        val held = backend.holdNext()
        val useLocal = backgroundScope.launch { client.patchCourseOverrideAnswered(course, signIn, colorHex = "#FF0000") }
        held.out.await()
        elapsedMs += 200
        val pick = clocks.now()
        elapsedMs += 1_000
        val lane = backgroundScope.launch { client.patchCourseOverrideAnswered(course, signIn, colorHex = "#00FF00", madeAt = pick) }
        // Time for the pick to go, were nothing holding it.
        withContext(Dispatchers.Default) { delay(200) }
        elapsedMs += 3_000
        held.release()
        useLocal.join()
        lane.join()

        val colors = backend.sent.map { JSONObject(it.body!!).getString("color_hex") }
        assertEquals("the pick after Use Local's color", listOf("#FF0000", "#00FF00"), colors)
        assertEquals("from Use Local's end, not the pick's own 1 s", 0L, lastAge())
    }

    @Test
    fun `a hide sent while its term's reset is out goes after the reset's end`() = runTest {
        val held = backend.holdNext()
        val reset = backgroundScope.launch { client.deleteAllCoursesAnswered("1151", signIn, resetAt = null) }
        held.out.await()
        elapsedMs += 200
        val hidden = clocks.now()
        val hide = backgroundScope.launch { client.deleteCourseAnswered("client:1151:XA2", signIn, hiddenAt = hidden) }
        withContext(Dispatchers.Default) { delay(200) }
        elapsedMs += 3_000
        held.release()
        reset.join()
        hide.join()

        assertEquals(listOf("/v4/sync/courses", "/v4/sync/courses/client:1151:XA2"), backend.sent.map { it.path() })
        assertEquals(0L, lastAge())
    }
}
