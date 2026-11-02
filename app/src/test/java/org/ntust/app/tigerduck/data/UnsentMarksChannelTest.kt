package org.ntust.app.tigerduck.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.ntust.app.tigerduck.push.RecordingBackend
import org.ntust.app.tigerduck.push.pushApiClientAt
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The marks' queue over the real backend client and real files. What it sends on `/v3` is pinned
 * request by request, as the build before the outbox sent it: the address, every header the client
 * builds, the body and its type, for a tap, a waiting mark with its time or with none, a resend
 * after a `clock_ahead`, and how a refusal, an outage and the switches decide what goes again.
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
}
