package org.ntust.app.tigerduck.data

import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.UnsentMark
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.ApiVersionGate
import org.ntust.app.tigerduck.network.ApiVersionInterceptor
import org.ntust.app.tigerduck.network.backend.BackendApi
import org.ntust.app.tigerduck.push.RecordingBackend
import org.ntust.app.tigerduck.push.WriteAnswer
import org.ntust.app.tigerduck.push.pushApiClientAt
import org.ntust.app.tigerduck.push.syncFixture
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.SyncClock
import org.ntust.app.tigerduck.sync.SyncClockStore
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.outbox.OldEntryTime
import org.ntust.app.tigerduck.sync.outbox.Outbox
import org.ntust.app.tigerduck.sync.outbox.OutboxChannel
import org.ntust.app.tigerduck.sync.outbox.OutboxChannelId
import org.ntust.app.tigerduck.util.JvmContext
import java.io.File

/**
 * The marks' queue over the real backend client and real files. What it sends on `/v3` is pinned
 * request by request, as the build before the outbox sent it: the address, every header the client
 * builds, the body and its type, for a tap, a waiting mark with its time or with none, a resend
 * after a `clock_ahead`, and how a refusal, an outage and the switches decide what goes again.
 *
 * On `/v4` each tap is stamped by the sync clock, a mark an older build wrote with no time goes with
 * its old-entry time (Review Focus 1), an edit made with marks off is kept and goes once they are
 * on, the reply's clock is the mark's acknowledgment, and a reply that says the backend kept a
 * later mark puts it on this phone unless a newer tap waits (LWW D12). The queue is the outbox's
 * marks channel, registered from its own file.
 */
class UnsentMarksChannelTest {

    private val v3 = "http://127.0.0.1:9/v3"
    private val context = JvmContext()
    private val prefs = AppPreferences(context)
    private val v3Backend = RecordingBackend(ahead = listOf(ApiVersionInterceptor { v3 }))
    private val cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
    private val series = SignInSeries()
    private val signIn get() = series.current

    private val madeAtMs = 1_790_000_000_123L
    private val bearer = "Authorization" to "Bearer access"
    private val json = "application/json; charset=utf-8"
    private val markPath = "/v3/sync/assignments/7/override"

    @Before
    fun openGate() {
        ApiVersionGate.resetForTesting()
        // TigerSync is off for good on F-Droid, where none of this goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
    }

    @After fun closeGate() = ApiVersionGate.resetForTesting()

    /** The queue as the app builds it, over the client at `/v3`. */
    private fun v3Marks() = UnsentMarks(cache, pushApiClientAt(v3, v3Backend.client, prefs), prefs)

    private fun RecordingBackend.Sent.shape() = listOf(method, url, headers, contentType, body)

    private fun v3Shapes() = v3Backend.sent.map { it.shape() }

    private fun patch(id: Int, body: String) = listOf("PATCH", "$v3/sync/assignments/$id/override", listOf(bearer), json, body)

    private suspend fun queue(vararg marks: UnsentMark) = assertTrue(cache.saveUnsentMarks(marks.toList(), signIn))

    // --- /v4 ---

    private val v4 = "http://127.0.0.1:9/v4"
    private val v4Backend = RecordingBackend()
    private val v4MarkPath = "/v4/sync/assignments/7/override"

    /** The wall clock the sync clock and the old-entry time read; a test moves it. */
    private var nowMs = 1_790_000_500_000L
    private val syncClock = SyncClock(SyncClockStore(context.getSharedPreferences("sync-clock", 0)), { BackendApi.V4 }, { nowMs })
    private val ack = AckClock()
    private val oldEntryTime = OldEntryTime(context.getSharedPreferences("tigerduck_outbox", 0), Dispatchers.Unconfined) { nowMs }
    private val queueFile = File(context.filesDir, "TigerDuckData/unsent_marks.json")

    /** The queue as the app builds it, over the client at `/v4`, stamping with [syncClock]. */
    private fun TestScope.v4Marks() = UnsentMarks(
        cache, pushApiClientAt(v4, v4Backend.client, prefs, syncClock), prefs,
        syncClock = syncClock, ack = ack, scope = backgroundScope, oldEntryTime = oldEntryTime,
    )

    private fun hlcOf(sent: RecordingBackend.Sent): Long? =
        Regex(""""hlc":(\d+)""").find(sent.body.orEmpty())?.groupValues?.get(1)?.toLong()

    /** This build first ran at [firstRunMs]; an older build wrote the queue file [rows] at [fileMs]. */
    private fun olderBuildWrote(rows: String, fileMs: Long, firstRunMs: Long) {
        val now = nowMs
        nowMs = firstRunMs
        oldEntryTime.recordFirstRun()
        nowMs = now
        queueFile.parentFile?.mkdirs()
        queueFile.writeText(rows)
        assertTrue(queueFile.setLastModified(fileMs))
    }

    private val untimed7 = """{"assignmentId":"7","status":"ignored","attempts":0,"lastRefusedAtMs":0}"""

    // --- /v3: today's requests, which the move to the outbox leaves as they are ---

    @Test
    fun `on v3 each waiting mark goes as before, a PATCH of its status with its time or with none`() = runTest {
        queue(UnsentMark("7", "ignored", madeAtMs = madeAtMs), UnsentMark("8", "none"))

        v3Marks().retry(signIn)

        assertEquals(
            listOf(
                patch(7, """{"local_status":"ignored","hlc":1790000000123000}"""),
                patch(8, """{"local_status":"none"}"""),
            ),
            v3Shapes(),
        )
        assertEquals(emptyList<UnsentMark>(), cache.loadUnsentMarks())
    }

    @Test
    fun `on v3 two waiting marks of one status and one time each go`() = runTest {
        queue(UnsentMark("7", "ignored", madeAtMs = madeAtMs), UnsentMark("8", "ignored", madeAtMs = madeAtMs), UnsentMark("9", "none"), UnsentMark("10", "none"))

        v3Marks().retry(signIn)

        assertEquals(listOf("7", "8", "9", "10").map { "$v3/sync/assignments/$it/override" }, v3Backend.sent.map { it.url })
        assertEquals(emptyList<UnsentMark>(), cache.loadUnsentMarks())
    }

    @Test
    fun `on v3 a mark whose id names no assignment goes nowhere and is let go`() = runTest {
        queue(UnsentMark("x7", "ignored", madeAtMs = madeAtMs), UnsentMark("8", "ignored", madeAtMs = madeAtMs))

        v3Marks().retry(signIn)

        assertEquals(listOf("$v3/sync/assignments/8/override"), v3Backend.sent.map { it.url })
        assertEquals(emptyList<UnsentMark>(), cache.loadUnsentMarks())
    }

    @Test
    fun `on v3 a reply that names another mark changes nothing on this phone`() = runTest {
        assertTrue(cache.saveIgnoredAssignments(setOf("7"), signIn))
        v3Backend.answer(markPath, 200, """{"local_status":"locally_completed","local_status_hlc":1790000900000000,"applied":false}""")

        v3Marks().send("7", "ignored", signIn)

        assertEquals(setOf("7"), cache.loadIgnoredAssignments())
        assertEquals(emptySet<String>(), cache.loadMarkedCompletedAssignments())
        assertEquals(emptyList<UnsentMark>(), cache.loadUnsentMarks())
    }

    @Test
    fun `on v3 a tap goes at once, stamped with the wall clock as it was made`() = runTest {
        val before = System.currentTimeMillis()
        v3Marks().send("7", "locally_completed", signIn)
        val after = System.currentTimeMillis()

        val sent = v3Backend.sent.single()
        assertEquals(listOf("PATCH", "$v3/sync/assignments/7/override", listOf(bearer), json), sent.shape().take(4))
        val stamp = Regex(""""hlc":(\d+)""").find(sent.body.orEmpty())?.groupValues?.get(1)?.toLong()
        assertTrue("stamped ${sent.body}", stamp != null && stamp in before * 1_000..after * 1_000)
        assertEquals("""{"local_status":"locally_completed","hlc":$stamp}""", sent.body)
        assertEquals(emptyList<UnsentMark>(), cache.loadUnsentMarks())
    }

    @Test
    fun `on v3 a mark refused as ahead goes once more without its time`() = runTest {
        queue(UnsentMark("7", "ignored", madeAtMs = madeAtMs))
        v3Backend.answer(markPath, 409, """{"detail":"clock_ahead"}""")

        v3Marks().retry(signIn)

        assertEquals(
            listOf(
                patch(7, """{"local_status":"ignored","hlc":1790000000123000}"""),
                patch(7, """{"local_status":"ignored"}"""),
            ),
            v3Shapes(),
        )
        assertEquals(emptyList<UnsentMark>(), cache.loadUnsentMarks())
    }

    @Test
    fun `on v3 a mark the backend refuses is counted, parked at five, and not sent again in this process`() = runTest {
        repeat(UnsentMarks.MAX_ATTEMPTS + 3) { v3Backend.answer(markPath, 404, """{"detail":"Not found"}""") }
        queue(UnsentMark("7", "ignored", madeAtMs = madeAtMs))
        val marks = v3Marks()

        repeat(UnsentMarks.MAX_ATTEMPTS + 2) { marks.retry(signIn) }

        assertEquals(UnsentMarks.MAX_ATTEMPTS, v3Backend.sent.size)
        assertEquals(UnsentMarks.MAX_ATTEMPTS, cache.loadUnsentMarks().single().attempts)
    }

    @Test
    fun `on v3 a server error or no answer keeps a mark without counting it`() = runTest {
        repeat(3) { v3Backend.answer(markPath, 503, """{"detail":"down"}""") }
        queue(UnsentMark("7", "ignored", madeAtMs = madeAtMs))
        val marks = v3Marks()

        repeat(3) { marks.retry(signIn) }

        assertEquals(3, v3Backend.sent.size)
        val kept = cache.loadUnsentMarks().single()
        assertEquals(0, kept.attempts)
        assertEquals(madeAtMs, kept.madeAtMs)
    }

    @Test
    fun `on v3 nothing goes or stays with the Assignments switch off, and a tap during the hold waits for it`() = runTest {
        val marks = v3Marks()
        prefs.syncAssignments = false
        marks.send("7", "ignored", signIn)
        assertEquals(emptyList<UnsentMark>(), cache.loadUnsentMarks())

        prefs.syncAssignments = true
        assertTrue(prefs.school.pendingConflictCategories.set(setOf("assignments"), signIn))
        marks.send("8", "ignored", signIn)
        marks.retry(signIn)
        assertEquals(emptyList<RecordingBackend.Sent>(), v3Backend.sent.toList())
        assertEquals(listOf("8"), cache.loadUnsentMarks().map { it.assignmentId })

        assertTrue(prefs.school.pendingConflictCategories.set(emptySet(), signIn))
        marks.retry(signIn)
        assertEquals(listOf("$v3/sync/assignments/8/override"), v3Backend.sent.map { it.url })
        assertEquals(emptyList<UnsentMark>(), cache.loadUnsentMarks())
    }

    // --- /v4: the outbox's marks channel ---

    @Test
    fun `on v4 a tap goes stamped by the sync clock, and the reply's clock is the mark's acknowledgment`() = runTest {
        v4Backend.answer(v4MarkPath, 200, syncFixture("v4/answer-mark-applied.json"), mapOf("X-TigerSync-Clock" to "1790000500002000"))

        v4Marks().send("7", "ignored", signIn)

        val sent = v4Backend.sent.single()
        assertEquals(listOf("PATCH", "$v4/sync/assignments/7/override", listOf(bearer)), listOf(sent.method, sent.url, sent.headers))
        // Nothing heard yet: the wall clock, a tick on.
        assertEquals("""{"local_status":"ignored","hlc":${(nowMs + 1) * 1_000}}""", sent.body)
        assertEquals(1_790_000_500_002_000L, ack.lastAck(SyncField.mark("7")))
        assertEquals(emptyList<UnsentMark>(), cache.loadUnsentMarks())
        assertEquals("a mark the backend took leaves this phone's as they are", emptySet<String>(), cache.loadIgnoredAssignments())
    }

    @Test
    fun `an entry an older build wrote goes out on v4 stamped with its file's time, kept on disk before it goes`() = runTest {
        val fileMs = nowMs - 300_000
        olderBuildWrote("[$untimed7]", fileMs = fileMs, firstRunMs = nowMs - 200_000)
        v4Backend.answer(v4MarkPath, 503, """{"error":"internal","message":"down"}""")
        val marks = v4Marks()

        marks.retry(signIn)
        // The time it went with is on disk, so a later rewrite cannot move it.
        assertEquals(fileMs, cache.loadUnsentMarks().single().madeAtMs)
        nowMs += 60_000
        marks.retry(signIn)

        assertEquals(listOf(fileMs * 1_000, fileMs * 1_000), v4Backend.sent.map(::hlcOf))
        assertEquals(emptyList<UnsentMark>(), cache.loadUnsentMarks())
    }

    @Test
    fun `an entry with no time in a file rewritten since this build first ran goes with the first run's time`() = runTest {
        val firstRunMs = nowMs - 200_000
        val timed = """{"assignmentId":"8","status":"none","attempts":0,"lastRefusedAtMs":0,"madeAtMs":${nowMs - 400_000}}"""
        olderBuildWrote("[$untimed7,$timed]", fileMs = nowMs - 100_000, firstRunMs = firstRunMs)

        v4Marks().retry(signIn)

        // Each with its own time where it has one, never the time it was sent.
        assertEquals(listOf(firstRunMs * 1_000, (nowMs - 400_000) * 1_000), v4Backend.sent.map(::hlcOf))
    }

    @Test
    fun `a mark with no time left waiting past a v3 rewrite of its file goes on v4 with the first run's time`() = runTest {
        val firstRunMs = nowMs - 200_000
        olderBuildWrote("[$untimed7,${untimed7.replace("\"7\"", "\"8\"")}]", fileMs = nowMs - 300_000, firstRunMs = firstRunMs)
        var onV4 = false
        val stamps = mutableListOf<Pair<Int, Long?>>()
        val marks = UnsentMarks(
            dataCache = cache,
            writer = MarkWriter { id, _, _, madeAtMs ->
                stamps += id to madeAtMs
                // 8 finds no network on v3.
                if (id == 8 && !onV4) throw java.io.IOException("offline")
                WriteAnswer(sent = true, serverClockUs = null, applied = null, values = emptyMap())
            },
            records = { true },
            sends = { true },
            lastWriterWins = { onV4 },
            scope = backgroundScope,
            oldEntryTime = oldEntryTime,
        )
        marks.retry(signIn)
        // On v3 both went with no time, and 7's landing rewrote the file.
        assertEquals(listOf(7 to null, 8 to null), stamps)
        assertNull(cache.loadUnsentMarks().single().madeAtMs)

        // The endpoint set to v4: the next read gives 8 a time, which it predates, and never its send time.
        onV4 = true
        nowMs += 60_000
        marks.retry(signIn)

        assertEquals(listOf(7 to null, 8 to null, 8 to firstRunMs), stamps)
        assertEquals(emptyList<UnsentMark>(), cache.loadUnsentMarks())
    }

    /** The gap between the sign-in's end and the wipe: the file is still there, and stays as it is. */
    @Test
    fun `on v4 an entry read under an ended sign-in is neither sent nor rewritten`() = runTest {
        olderBuildWrote("[$untimed7]", fileMs = nowMs - 300_000, firstRunMs = nowMs - 200_000)
        val bytes = queueFile.readText()
        val marks = v4Marks()

        val ended = series.end()
        marks.retry(ended)
        marks.drain(ended)
        marks.send("7", "none", ended)
        marks.send("8", "ignored", ended)
        marks.keep("9", "ignored", ended)
        marks.queueAsOldest("10", "ignored", ended)
        marks.forget(ended)
        assertEquals(setOf("7"), marks.ids())

        assertEquals(emptyList<RecordingBackend.Sent>(), v4Backend.sent.toList())
        assertEquals("not even given its old-entry time", bytes, queueFile.readText())
    }

    @Test
    fun `an edit made with the category off is kept on v4 and sent once it is on`() = runTest {
        val marks = v4Marks()
        prefs.syncAssignments = false
        marks.send("7", "ignored", signIn)
        val sevenAt = nowMs + 1
        nowMs += 1_000
        prefs.syncAssignments = true
        prefs.cloudSyncEnabled = false
        marks.send("8", "none", signIn)
        val eightAt = nowMs + 1
        marks.retry(signIn)
        assertEquals(emptyList<RecordingBackend.Sent>(), v4Backend.sent.toList())
        assertEquals(listOf("7" to sevenAt, "8" to eightAt), cache.loadUnsentMarks().map { it.assignmentId to it.madeAtMs })

        prefs.cloudSyncEnabled = true
        nowMs += 60_000
        marks.retry(signIn)

        assertEquals(listOf("$v4/sync/assignments/7/override", "$v4/sync/assignments/8/override"), v4Backend.sent.map { it.url })
        assertEquals("each with the time it was made", listOf(sevenAt * 1_000, eightAt * 1_000), v4Backend.sent.map(::hlcOf))
        assertEquals(emptyList<UnsentMark>(), cache.loadUnsentMarks())
    }

    @Test
    fun `a refused v4 write applies the backend's value unless a newer edit waits`() = runTest {
        // The tap's own write, as Home makes it before the mark goes.
        assertTrue(cache.saveIgnoredAssignments(setOf("7", "9"), signIn))
        v4Backend.answer(v4MarkPath, 200, syncFixture("v4/answer-mark-lost.json"))
        val adopted = cache.marksAdoptedVersion.value

        v4Marks().send("7", "ignored", signIn)

        assertEquals(setOf("9"), cache.loadIgnoredAssignments())
        assertEquals(setOf("7"), cache.loadMarkedCompletedAssignments())
        assertTrue("Home reads the marks again", cache.marksAdoptedVersion.value > adopted)
        assertEquals("let go, not sent again", 1, v4Backend.sent.size)
        assertEquals(emptyList<UnsentMark>(), cache.loadUnsentMarks())
    }

    @Test
    fun `a clear the backend refused puts back the mark it kept, archived read as ignored`() = runTest {
        // The clear's own write, as Home makes it: 7 in neither set, 8 left marked done.
        assertTrue(cache.saveMarkedCompletedAssignments(setOf("8"), signIn))
        v4Backend.answer(v4MarkPath, 200, syncFixture("v4/answer-mark.json"))

        v4Marks().send("7", "none", signIn)

        assertEquals(setOf("7"), cache.loadIgnoredAssignments())
        assertEquals(setOf("8"), cache.loadMarkedCompletedAssignments())
    }

    @Test
    fun `the backend's mark is not taken over a newer tap of the assignment, which goes next`() = runTest {
        assertTrue(cache.saveIgnoredAssignments(setOf("7"), signIn))
        val sent = mutableListOf<String>()
        val firstOut = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val marks = UnsentMarks(
            dataCache = cache,
            writer = MarkWriter { _, status, _, _ ->
                sent += status
                if (sent.size == 1) {
                    firstOut.complete(Unit)
                    release.await()
                    // The backend kept a later mark than the first tap's.
                    WriteAnswer(sent = true, serverClockUs = null, applied = emptySet(), values = mapOf("local_status" to "locally_completed"))
                } else {
                    WriteAnswer(sent = true, serverClockUs = null, applied = setOf("local_status"), values = mapOf("local_status" to status))
                }
            },
            records = { true },
            sends = { true },
            lastWriterWins = { true },
            scope = backgroundScope,
        )

        val first = launch { marks.send("7", "ignored", signIn) }
        firstOut.await()
        // Cleared while the first was out, and kept before its reply.
        val newer = launch { marks.send("7", "none", signIn) }
        withContext(Dispatchers.Default) {
            while (cache.loadUnsentMarks().singleOrNull()?.status != "none") delay(5)
        }
        release.complete(Unit)
        first.join()
        newer.join()

        assertEquals(listOf("ignored", "none"), sent)
        assertEquals("the backend's mark was not taken", setOf("7"), cache.loadIgnoredAssignments())
        assertEquals(emptySet<String>(), cache.loadMarkedCompletedAssignments())
        assertEquals(emptyList<UnsentMark>(), cache.loadUnsentMarks())
    }

    @Test
    fun `a re-enable check that opens during a sync keeps the marks still to go`() = runTest {
        queue(UnsentMark("7", "ignored", madeAtMs = madeAtMs), UnsentMark("8", "ignored", madeAtMs = madeAtMs + 1))
        var sends = true
        val sent = mutableListOf<Int>()
        val marks = UnsentMarks(
            dataCache = cache,
            writer = MarkWriter { id, _, _, _ ->
                sent += id
                // The check opens while 7 is out.
                sends = false
                WriteAnswer(sent = true, serverClockUs = null, applied = null, values = emptyMap())
            },
            records = { true },
            sends = { sends },
            scope = backgroundScope,
        )

        marks.retry(signIn)

        assertEquals(listOf(7), sent)
        assertEquals(listOf("8"), cache.loadUnsentMarks().map { it.assignmentId })
    }

    /**
     * A newer tap whose mark Home saved before the older reply's backend mark was read, and which
     * was queued only after the check for a newer tap: Home's save and the queue's keep are two
     * steps, in either order. Its mark stays, and goes next.
     */
    @Test
    fun `the backend's mark is not taken over a newer tap saved before it and queued after the check`() = runTest {
        // The first tap's own write, as Home makes it.
        assertTrue(cache.saveIgnoredAssignments(setOf("7"), signIn))
        val sent = mutableListOf<String>()
        val firstOut = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val marks = UnsentMarks(
            dataCache = cache,
            writer = MarkWriter { _, status, _, _ ->
                sent += status
                if (sent.size == 1) {
                    firstOut.complete(Unit)
                    release.await()
                    // The backend kept a later mark than the first tap's.
                    WriteAnswer(sent = true, serverClockUs = null, applied = emptySet(), values = mapOf("local_status" to "locally_completed"))
                } else {
                    WriteAnswer(sent = true, serverClockUs = null, applied = setOf("local_status"), values = mapOf("local_status" to status))
                }
            },
            records = { true },
            sends = { true },
            lastWriterWins = { true },
            scope = backgroundScope,
        )

        val first = launch { marks.send("7", "ignored", signIn) }
        firstOut.await()
        // Cleared while the first was out: Home saves the mark first...
        assertTrue(cache.replaceIgnoredAssignments(emptySet(), signIn))
        release.complete(Unit)
        // ...and the tap reaches the queue as the reply is read, behind the check's read of it.
        val newer = launch { marks.send("7", "none", signIn) }
        first.join()
        newer.join()

        assertEquals(listOf("ignored", "none"), sent)
        assertEquals("the newer tap's mark stays", emptySet<String>(), cache.loadMarkedCompletedAssignments())
        assertEquals(emptySet<String>(), cache.loadIgnoredAssignments())
        assertEquals(emptyList<UnsentMark>(), cache.loadUnsentMarks())
    }

    @Test
    fun `the backend's mark is taken only if no other write of the marks came since the queue was read`() = runTest {
        var version = 0
        val adopted = mutableListOf<Pair<Set<String>, Set<String>>>()
        val read: suspend () -> Pair<Set<String>, Set<String>> = { setOf("7", "8") to emptySet() }

        takeBackendMark("7", "locally_completed", "ignored", readAt = version, { version }, read) { i, c -> adopted += i to c }
        // A swipe of 8 saved while 7's were read: written, the read would undo it.
        takeBackendMark("7", "locally_completed", "ignored", readAt = version, { version }, { read().also { version++ } }) { i, c -> adopted += i to c }
        // One saved after the queue was read, before the marks were: a newer tap's, perhaps.
        takeBackendMark("7", "locally_completed", "ignored", readAt = version - 1, { version }, read) { i, c -> adopted += i to c }
        // Already so: nothing to write.
        takeBackendMark("8", "ignored", "ignored", readAt = version, { version }, read) { i, c -> adopted += i to c }
        // This phone's mark is not the one answered: a newer tap set it, which the queue may not hold yet.
        takeBackendMark("7", "locally_completed", "none", readAt = version, { version }, read) { i, c -> adopted += i to c }
        // An archived answer is the ignored mark this phone holds.
        takeBackendMark("8", "none", "archived", readAt = version, { version }, read) { i, c -> adopted += i to c }

        assertEquals(listOf(setOf("8") to setOf("7"), setOf("7") to emptySet()), adopted)
    }

    @Test
    fun `on v4 a mark refused as ahead twice is kept uncounted, and a refusal is counted`() = runTest {
        repeat(2) {
            v4Backend.answer(v4MarkPath, 409, syncFixture("v4/error-clock-ahead.json"), mapOf("X-TigerSync-Clock" to "${(nowMs - 60_000) * 1_000}"))
        }
        val marks = v4Marks()

        marks.send("7", "ignored", signIn)
        assertEquals("stamped, then stamped again", 2, v4Backend.sent.size)
        assertEquals(0, cache.loadUnsentMarks().single().attempts)

        v4Backend.answer(v4MarkPath, 404, syncFixture("v4/error-not-found.json"))
        marks.retry(signIn)
        assertEquals(1, cache.loadUnsentMarks().single().attempts)
    }

    @Test
    fun `the marks are the outbox's marks channel, which a course upload leaves parked`() = runTest {
        repeat(UnsentMarks.MAX_ATTEMPTS + 2) { v4Backend.answer(v4MarkPath, 404, syncFixture("v4/error-not-found.json")) }
        val marks = v4Marks()
        val outbox = Outbox(setOf(marks))
        assertEquals(OutboxChannelId.MARKS, marks.id)
        marks.send("7", "ignored", signIn)
        // The first in this process takes the parked marks' one more try.
        repeat(UnsentMarks.MAX_ATTEMPTS) { outbox.drain(signIn) }
        assertEquals(UnsentMarks.MAX_ATTEMPTS, v4Backend.sent.size)
        assertEquals(setOf(SyncField.mark("7")), outbox.heldFields())
        assertTrue(outbox.holds(SyncField.mark("7")))

        outbox.courseUploadLanded(signIn)
        outbox.drain(signIn)
        assertEquals("a course upload is not what a mark waits for", UnsentMarks.MAX_ATTEMPTS, v4Backend.sent.size)

        // A sign-out's forgetting: the next student's parked marks get their own one more try.
        outbox.forgetAccount()
        outbox.drain(signIn)
        assertEquals(UnsentMarks.MAX_ATTEMPTS + 1, v4Backend.sent.size)
    }

    @Test
    fun `a mark queued as the oldest is stamped 0, and only when none waits for its assignment`() = runTest {
        val marks = v4Marks()
        prefs.syncAssignments = false
        marks.send("8", "ignored", signIn)
        marks.queueAsOldest("7", "ignored", signIn)
        marks.queueAsOldest("8", "none", signIn)
        assertEquals(listOf(Triple("8", "ignored", nowMs + 1), Triple("7", "ignored", 0L)), cache.loadUnsentMarks().map { Triple(it.assignmentId, it.status, it.madeAtMs) })

        prefs.syncAssignments = true
        marks.retry(signIn)

        assertEquals(listOf((nowMs + 1) * 1_000, 0L), v4Backend.sent.map(::hlcOf))
    }

    @Test
    fun `the marks register as an outbox channel from their own file`() {
        val provides = UnsentMarksChannelModule::class.java.getMethod("channel", UnsentMarks::class.java)
        assertTrue(UnsentMarksChannelModule::class.java.isAnnotationPresent(Module::class.java))
        assertTrue(provides.isAnnotationPresent(Provides::class.java))
        assertTrue(provides.isAnnotationPresent(IntoSet::class.java))
        assertEquals(OutboxChannel::class.java, provides.returnType)
    }

    /** Review Focus 5: the reply lands after the sign-out, and the next student's files are not the ones it was read from. */
    @Test
    fun `a v4 reply that crossed a sign-out puts nothing in the next student's marks`() = runTest {
        val marks = UnsentMarks(
            dataCache = cache,
            writer = MarkWriter { _, _, _, _ ->
                // Signed out, the data wiped, and someone else signed in while the mark was out.
                series.end()
                cache.clearAllUserData()
                series.open()
                WriteAnswer(sent = true, serverClockUs = 1_790_000_600_000_000L, applied = emptySet(), values = mapOf("local_status" to "locally_completed"))
            },
            records = { true },
            sends = { true },
            lastWriterWins = { true },
            ack = ack,
            scope = backgroundScope,
        )

        marks.send("7", "ignored", signIn)

        assertEquals(emptySet<String>(), cache.loadMarkedCompletedAssignments())
        assertEquals(emptySet<String>(), cache.loadIgnoredAssignments())
        assertEquals(emptyList<UnsentMark>(), cache.loadUnsentMarks())
        assertNull("nor is the departing student's acknowledgment the next one's", ack.lastAck(SyncField.mark("7")))
    }
}
