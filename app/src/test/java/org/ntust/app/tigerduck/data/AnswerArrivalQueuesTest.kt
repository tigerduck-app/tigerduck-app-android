package org.ntust.app.tigerduck.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.EditClocks
import org.ntust.app.tigerduck.data.model.PendingCourseOverride
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.ApiVersionGate
import org.ntust.app.tigerduck.push.RecordingBackend
import org.ntust.app.tigerduck.push.pushApiClientAt
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.sync.DeviceClocks
import org.ntust.app.tigerduck.sync.outbox.AnswerArrivals
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The per-key rule of `age_ms` through the queues and the real client: an edit made while the
 * last send of its field was out counts its age from that send's answer, the field read from where
 * the write went, a course's resolved key; and one made after the answer keeps its own time.
 */
class AnswerArrivalQueuesTest {

    private val context = JvmContext()
    private val prefs = AppPreferences(context)
    private val cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
    private val series = SignInSeries()
    private val signIn get() = series.current
    private val term = "1151"

    // The device's clocks, which a test moves, in one boot.
    @Volatile private var elapsedMs = 1_000L
    private val clocks = DeviceClocks({ 1_790_000_000_000L + elapsedMs }, { elapsedMs }, { 7 })
    private val backend = RecordingBackend()
    private val client = pushApiClientAt("http://127.0.0.1:9/v4", backend.client, prefs, clocks, arrivals = AnswerArrivals.inMemory(clocks))

    @Before
    fun openGate() {
        ApiVersionGate.resetForTesting()
        assumeTrue(prefs.cloudSyncEnabled)
    }

    @After fun closeGate() = ApiVersionGate.resetForTesting()

    private fun ageOf(sent: RecordingBackend.Sent): Long? = sent.body?.let { JSONObject(it).opt("age_ms") as? Number }?.toLong()

    // Polled on another dispatcher: the queues' files are written on the IO threads.
    private suspend fun until(done: suspend () -> Boolean) = withContext(Dispatchers.Default) { while (!done()) delay(5) }

    @Test
    fun `a color an older build kept by Moodle id alone keeps its answer's arrival under the course key it went to`() = runTest {
        assertTrue(cache.saveCourses(listOf(Course(courseNo = "CS1", courseName = "Algo", moodleIdNumber = "M77")), term, signIn))
        val made = clocks.now()
        assertTrue(
            cache.savePendingCourseOverrides(
                listOf(PendingCourseOverride("M77", "#FF0000", null, null, 1, seq = 1, madeAtMs = made.wallMs, madeAtClocks = EditClocks.of(made))),
                signIn,
            ),
        )
        val overrides = PendingCourseOverrides(cache, client, prefs, clocks = clocks, scope = backgroundScope)
        // Answered with the backend's clock, as every answer of ours is.
        backend.answer("/v4/sync/courses/client:$term:CS1/override", 200, "{}", mapOf("X-TigerSync-Clock" to "1790000000000000"))
        val held = backend.holdNext()

        val draining = launch { overrides.drain(signIn) }
        held.out.await()
        elapsedMs += 200
        val picked = launch { overrides.send("M77", colorHex = "#00FF00", semester = term, courseNo = "CS1", signIn = signIn) }
        until { cache.loadPendingCourseOverrides().any { it.colorHex == "#00FF00" } }
        elapsedMs += 3_000
        held.release()
        draining.join()
        picked.join()
        until { backend.sent.size == 2 }

        assertEquals(listOf("/v4/sync/courses/client:$term:CS1/override"), backend.sent.map { it.url.substringAfter("127.0.0.1:9") }.distinct())
        assertEquals("the pick went right after the old row's answer, from it", 0L, ageOf(backend.sent[1]))
    }

    @Test
    fun `a mark tapped while the last one's PATCH is out counts its age from that answer's arrival`() = runTest {
        backend.answer("/v4/sync/assignments/7/override", 200, "{}", mapOf("X-TigerSync-Clock" to "1790000000000000"))
        val marks = UnsentMarks(cache, client, prefs, clocks = clocks, scope = backgroundScope)
        val held = backend.holdNext()

        val first = launch { marks.send("7", "ignored", signIn) }
        held.out.await()
        elapsedMs += 200
        val second = launch { marks.send("7", "none", signIn) }
        until { cache.loadUnsentMarks().singleOrNull()?.status == "none" }
        elapsedMs += 3_000
        held.release()
        first.join()
        second.join()
        until { backend.sent.size == 2 }

        assertEquals("the first from its tap, the second from the first's answer, not its own 3 s", listOf(0L, 0L), backend.sent.map(::ageOf))
        until { cache.loadUnsentMarks().isEmpty() }
    }

    @Test
    fun `a course deleted while its add's upload is out counts its age from that upload's answer`() = runTest {
        val course = Course(courseNo = "MY1", courseName = "Mine", isManual = true)
        assertTrue(cache.saveCourses(listOf(course), term, signIn))
        backend.answer(
            "/v4/sync/courses/upload", 200,
            """{"accepted_keys":["client:$term:MY1"],"adds":[{"course_key":"client:$term:MY1","applied":true}]}""",
            mapOf("X-TigerSync-Clock" to "1790000000000000"),
        )
        val adds = PendingCourseAdds(cache, client, scope = backgroundScope)
        val deletes = PendingCourseDeletes(cache, adds, client, clocks, scope = backgroundScope)
        val held = backend.holdNext()

        val added = launch { adds.send(listOf(course), term, "MY1", deletes.stamp(), signIn) }
        held.out.await()
        elapsedMs += 500
        val deleted = launch { deletes.send(term, "MY1", deletes.stamp(), signIn) }
        // Kept while the add is out, waiting for the send lock the upload holds.
        until { cache.loadPendingCourseDeletes()[term]?.containsKey("MY1") == true }
        elapsedMs += 2_000
        held.release()
        added.join()
        deleted.join()

        val hide = backend.sent.last()
        assertEquals("DELETE", hide.method)
        assertEquals("from the add's answer, not its own 2 s", "0", Regex("[?&]age_ms=(\\d+)").find(hide.url)?.groupValues?.get(1))
    }
}
