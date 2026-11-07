package org.ntust.app.tigerduck.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.RecordingBackend
import org.ntust.app.tigerduck.push.pushApiClientAt
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.sync.DeviceClocks
import org.ntust.app.tigerduck.sync.outbox.EditFloors
import org.ntust.app.tigerduck.sync.outbox.Outbox
import org.ntust.app.tigerduck.sync.outbox.OutboxChannel
import org.ntust.app.tigerduck.util.JvmContext
import java.io.File
import java.io.IOException

/**
 * The per-key rule's floors on disk, as the iPhone keeps them (tigerduck-app#1008): as a write of a
 * key is answered, every edit of the key a queue keeps that was made at or after the write went out
 * counts its age from the answer at least, written with the edit; so after a relaunch, in a new
 * client that remembers no write, a parked edit's next try, a holiday's choice, and each course
 * queue of a term whose reset was out, the edit still ages from that end. None is written once the
 * write's sign-in has ended.
 */
class WriteSpanQueuesTest {

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

    // The queues the first process's writes raise the floors of, once built.
    private var channels: Set<OutboxChannel> = emptySet()
    private val client = pushApiClientAt(
        "http://127.0.0.1:9/v4", backend.client, prefs, clocks,
        floors = EditFloors { key, start, end, at -> Outbox(channels).raise(key, start, end, at) },
    )

    // The next process's client: it remembers no write of the first's.
    private fun relaunched(): PushApiClient = pushApiClientAt("http://127.0.0.1:9/v4", backend.client, prefs, clocks)

    @Before
    fun openGate() {
        ApiVersionGate.resetForTesting()
        assumeTrue(prefs.cloudSyncEnabled)
    }

    @After fun closeGate() = ApiVersionGate.resetForTesting()

    private fun lastAge(): Long? {
        val sent = backend.sent.last()
        val body = sent.body?.takeIf { it.isNotEmpty() }?.let(::JSONObject)
        val add = body?.optJSONArray("adds")?.optJSONObject(0)
        return ((add ?: body)?.opt("age_ms") as? Number)?.toLong() ?: Regex("[?&]age_ms=(\\d+)").find(sent.url)?.groupValues?.get(1)?.toLong()
    }

    private suspend fun until(done: suspend () -> Boolean) = withContext(Dispatchers.Default) { while (!done()) delay(5) }

    private fun ageOf(sent: RecordingBackend.Sent): Long? = sent.body?.let { JSONObject(it).opt("age_ms") as? Number }?.toLong()

    /**
     * [write] goes out, made now, and is held; 0.2 s into it [edit] keeps an edit unsent; its answer
     * arrives 3 s later, and the next process runs half a second after.
     */
    private suspend fun TestScope.during(write: suspend () -> Unit, edit: suspend () -> Unit) {
        val held = backend.holdNext()
        val out = backgroundScope.launch { write() }
        held.out.await()
        elapsedMs += 200
        edit()
        elapsedMs += 3_000
        held.release()
        out.join()
        elapsedMs += 500
    }

    private suspend fun TestScope.duringReset(edit: suspend () -> Unit) =
        during({ client.deleteAllCoursesAnswered(term, signIn, resetAt = null) }, edit)

    @Test
    fun `a mark tapped during Use Local's PATCH of it ages from that PATCH's end after a relaunch`() = runTest {
        val marks = UnsentMarks(cache, client, prefs, clocks = clocks, scope = backgroundScope).also { channels = setOf(it) }
        during({ client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = null) }) { marks.keep("7", "none", signIn) }

        UnsentMarks(cache, relaunched(), prefs, clocks = clocks, scope = backgroundScope).drain(signIn)

        assertEquals("/v4/sync/assignments/7/override", backend.sent.last().url.substringAfter("127.0.0.1:9"))
        assertEquals(500L, lastAge())
    }

    @Test
    fun `a parked mark keeps its floor for its one more try`() = runTest {
        val marks = UnsentMarks(cache, client, prefs, clocks = clocks, scope = backgroundScope).also { channels = setOf(it) }
        during({ client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = null) }) { marks.keep("7", "none", signIn) }
        repeat(UnsentMarks.MAX_ATTEMPTS) {
            backend.answer("/v4/sync/assignments/7/override", 422, """{"detail":{"code":"bad_status"}}""")
            marks.drain(signIn)
        }
        assertEquals(UnsentMarks.MAX_ATTEMPTS, cache.loadUnsentMarks().single().attempts)

        val next = UnsentMarks(cache, relaunched(), prefs, clocks = clocks, scope = backgroundScope)
        next.requeueParked(signIn)
        next.drain(signIn)

        assertEquals(500L, lastAge())
    }

    @Test
    fun `a holiday chosen during a PUT of it ages from that PUT's end after a relaunch`() = runTest {
        var sends = false
        val holidays = PendingHolidayOverrides(prefs, HolidayOverrideWriter(client::putHolidayOverrideAnswered), syncs = { sends }, clocks = clocks, scope = backgroundScope)
            .also { channels = setOf(it) }
        during({ client.putHolidayOverrideAnswered(3, true, signIn, madeAt = null) }) { holidays.send(3, signIn) }
        sends = true

        PendingHolidayOverrides(prefs, HolidayOverrideWriter(relaunched()::putHolidayOverrideAnswered), syncs = { true }, clocks = clocks, scope = backgroundScope).drain(signIn)

        assertEquals(500L, lastAge())
    }

    @Test
    fun `a write answered after its sign-in ended raises no floor`() = runTest {
        val marks = UnsentMarks(cache, client, prefs, clocks = clocks, scope = backgroundScope).also { channels = setOf(it) }
        during({ client.patchAssignmentOverrideAnswered(7, "ignored", signIn, madeAt = null) }) {
            marks.keep("7", "none", signIn)
            series.end()
        }

        val file = File(context.filesDir, "TigerDuckData/unsent_marks.json").readText()
        assertFalse(file, file.contains("floor"))
    }

    @Test
    fun `a hide kept while its term's reset is out ages from the reset's end after a relaunch`() = runTest {
        val adds = PendingCourseAdds(cache, client)
        val deletes = PendingCourseDeletes(cache, adds, client, clocks, scope = backgroundScope).also { channels = setOf(adds, it) }
        duringReset { assertTrue(deletes.keep(term, "MY1", deletes.stamp(), signIn)) }

        val next = relaunched()
        PendingCourseDeletes(cache, PendingCourseAdds(cache, next), next, clocks, scope = backgroundScope).sendKept(term, "MY1", signIn)

        assertEquals("/v4/sync/courses/client:$term:MY1", backend.sent.last().url.substringAfter("127.0.0.1:9").substringBefore('?'))
        assertEquals(500L, lastAge())
    }

    @Test
    fun `an add kept while its term's reset is out ages from the reset's end after a relaunch`() = runTest {
        val course = Course(courseNo = "MY1", courseName = "Mine", isManual = true)
        assertTrue(cache.saveCourses(listOf(course), term, signIn))
        val adds = PendingCourseAdds(cache, { _, _, _, _, _ -> throw IOException("offline") }).also { channels = setOf(it) }
        val stamps = PendingCourseDeletes(cache, adds, client, clocks, scope = backgroundScope)
        duringReset { adds.send(listOf(course), term, "MY1", stamps.stamp(), signIn) }

        PendingCourseAdds(cache, relaunched(), scope = backgroundScope).drain(signIn)

        assertEquals("/v4/sync/courses/upload", backend.sent.last().url.substringAfter("127.0.0.1:9"))
        assertEquals(500L, lastAge())
    }

    @Test
    fun `a color kept while its term's reset is out ages from the reset's end after a relaunch`() = runTest {
        val writer = CourseOverrideWriter { change, target, at, allowed, madeAt ->
            client.patchCourseOverrideAnswered(target, at, change.colorHex, change.customName, change.locale, allowed, madeAt)
        }
        val overrides = PendingCourseOverrides(cache, writer, records = { true }, sends = { false }, clocks = clocks, scope = backgroundScope)
            .also { channels = setOf(it) }
        duringReset { overrides.send("M1", colorHex = "#00FF00", semester = term, courseNo = "CS1", signIn = signIn) }

        PendingCourseOverrides(cache, relaunched(), prefs, clocks = clocks, scope = backgroundScope).drain(signIn)

        assertEquals("/v4/sync/courses/client:$term:CS1/override", backend.sent.last().url.substringAfter("127.0.0.1:9"))
        assertEquals(500L, lastAge())
    }

    @Test
    fun `an ignore kept while its term's reset is out ages from the reset's end after a relaunch`() = runTest {
        val wall = { 1_790_000_000_000L + elapsedMs }
        val first = CourseIgnoreSync(cache, CourseIgnoreWriter(client::patchCourseIgnoreAnswered), CourseIgnoreEffects {}, { false }, wall)
            .also { channels = setOf(it.channel) }
        duringReset { first.setIgnored(term, "CS1", "${term}CS1", null, ignore = true, signIn = signIn) }

        CourseIgnoreSync(cache, CourseIgnoreWriter(relaunched()::patchCourseIgnoreAnswered), CourseIgnoreEffects {}, { true }, wall).channel.drain(signIn)

        assertEquals("/v4/sync/courses/client:$term:CS1/override", backend.sent.last().url.substringAfter("127.0.0.1:9"))
        assertEquals(500L, lastAge())
    }

    @Test
    fun `a color an older build kept by Moodle id alone floors a pick under the term its write went to`() = runTest {
        assertTrue(cache.saveCourses(listOf(Course(courseNo = "CS1", courseName = "Algo", moodleIdNumber = "M77")), term, signIn))
        val made = clocks.now()
        assertTrue(
            cache.savePendingCourseOverrides(
                listOf(PendingCourseOverride("M77", "#FF0000", null, null, 1, seq = 1, madeAtMs = made.wallMs, madeAtClocks = EditClocks.of(made))),
                signIn,
            ),
        )
        val overrides = PendingCourseOverrides(cache, client, prefs, clocks = clocks, scope = backgroundScope).also { channels = setOf(it) }
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
        assertEquals("the pick went right after the old row's end, from it", 0L, ageOf(backend.sent[1]))
    }

    @Test
    fun `a mark tapped while the last one's PATCH is out ages from that PATCH's end`() = runTest {
        backend.answer("/v4/sync/assignments/7/override", 200, "{}", mapOf("X-TigerSync-Clock" to "1790000000000000"))
        val marks = UnsentMarks(cache, client, prefs, clocks = clocks, scope = backgroundScope).also { channels = setOf(it) }
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

        assertEquals("the first from its tap, the second from the first's end, not its own 3 s", listOf(0L, 0L), backend.sent.map(::ageOf))
        until { cache.loadUnsentMarks().isEmpty() }
    }

    @Test
    fun `a course deleted while its add's upload is out ages from that upload's end`() = runTest {
        val course = Course(courseNo = "MY1", courseName = "Mine", isManual = true)
        assertTrue(cache.saveCourses(listOf(course), term, signIn))
        backend.answer(
            "/v4/sync/courses/upload", 200,
            """{"accepted_keys":["client:$term:MY1"],"adds":[{"course_key":"client:$term:MY1","applied":true}]}""",
            mapOf("X-TigerSync-Clock" to "1790000000000000"),
        )
        val adds = PendingCourseAdds(cache, client, scope = backgroundScope)
        val deletes = PendingCourseDeletes(cache, adds, client, clocks, scope = backgroundScope).also { channels = setOf(adds, it) }
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
        assertEquals("from the add's end, not its own 2 s", "0", Regex("[?&]age_ms=(\\d+)").find(hide.url)?.groupValues?.get(1))
    }
}
