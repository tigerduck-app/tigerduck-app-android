package org.ntust.app.tigerduck.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.ApiVersionGate
import org.ntust.app.tigerduck.network.ApiVersionInterceptor
import org.ntust.app.tigerduck.push.RecordingBackend
import org.ntust.app.tigerduck.push.pushApiClientAt
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The holiday choices' queue over the real backend client and real preferences. What it sends on
 * `/v3` is pinned request by request, as the build before the outbox sent it: the address, every
 * header the client builds, the body and its type, for a tap, a waiting choice with its time or
 * with none, a resend after a `clock_ahead`, and how a refusal and TigerSync's switch decide what
 * goes again.
 */
class HolidayChannelTest {

    private val v3 = "http://127.0.0.1:9/v3"
    private val context = JvmContext()
    private val prefs = AppPreferences(context)
    private val v3Backend = RecordingBackend(ahead = listOf(ApiVersionInterceptor { v3 }))
    private val series = SignInSeries()
    private val signIn get() = series.current

    private val madeAtMs = 1_790_000_000_123L
    private val bearer = "Authorization" to "Bearer access"
    private val json = "application/json; charset=utf-8"

    @Before
    fun openGate() {
        ApiVersionGate.resetForTesting()
        // TigerSync is off for good on F-Droid, where none of this goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
    }

    @After fun closeGate() = ApiVersionGate.resetForTesting()

    /** The queue as the app builds it, over the client at `/v3`. */
    private fun v3Holidays() = PendingHolidayOverrides(prefs, pushApiClientAt(v3, v3Backend.client, prefs))

    private fun RecordingBackend.Sent.shape() = listOf(method, url, headers, contentType, body)

    private fun v3Shapes() = v3Backend.sent.map { it.shape() }

    private fun put(id: Int, body: String) = listOf("PUT", "$v3/sync/holiday-overrides/$id", listOf(bearer), json, body)

    // --- /v3: today's requests, which the move to the outbox leaves as they are ---

    @Test
    fun `on v3 each waiting choice goes as before, a PUT of notify with its time or with none`() = runTest {
        prefs.holidayNotifyOverrides = setOf(3)
        prefs.holidayOverrideEditTimes = mapOf(3 to madeAtMs)
        prefs.holidayOverridesAwaitingUpload = setOf(3, 5)
        val order = prefs.holidayOverridesAwaitingUpload.toList()

        v3Holidays().retry(signIn)

        val bodies = mapOf(3 to """{"notify":true,"hlc":1790000000123000}""", 5 to """{"notify":false}""")
        assertEquals(order.map { put(it, bodies.getValue(it)) }, v3Shapes())
        assertEquals(emptySet<Int>(), prefs.holidayOverridesAwaitingUpload)
        assertEquals("a time is neither given nor dropped", mapOf(3 to madeAtMs), prefs.holidayOverrideEditTimes)
    }

    @Test
    fun `on v3 a reply that names another choice changes nothing on this phone`() = runTest {
        v3Backend.answer("/v3/sync/holiday-overrides/3", 200, """{"holiday_id":3,"notify":false,"hlc":1790000900000000,"applied":false}""")
        prefs.holidayNotifyOverrides = setOf(3)

        v3Holidays().send(3, signIn)

        assertEquals(setOf(3), prefs.holidayNotifyOverrides)
        assertEquals(setOf(3), prefs.holidayOverrideEditTimes.keys)
        assertEquals(emptySet<Int>(), prefs.holidayOverridesAwaitingUpload)
    }

    @Test
    fun `on v3 a snapshot fetched while a sync's PUT is under way is not applied`() = runTest {
        prefs.holidayNotifyOverrides = setOf(3)
        prefs.holidayOverridesAwaitingUpload = setOf(3)
        val out = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holidays = PendingHolidayOverrides(
            prefs,
            HolidayOverrideSender { _, _, _, _ ->
                out.complete(Unit)
                release.await()
                true
            },
            syncs = { true },
        )

        val retry = launch { holidays.retry(signIn) }
        out.await()
        assertNull("it may predate the PUT", holidays.merged(emptySet(), fetchedAtMs = Long.MAX_VALUE, local = setOf(3)))
        release.complete(Unit)
        retry.join()

        assertEquals(emptySet<Int>(), holidays.merged(emptySet(), fetchedAtMs = Long.MAX_VALUE, local = setOf(3)))
    }

    @Test
    fun `on v3 a sync sends no waiting choice while TigerSync is off`() = runTest {
        prefs.holidayNotifyOverrides = setOf(3)
        prefs.holidayOverridesAwaitingUpload = setOf(3)
        var syncs = false
        val sent = mutableListOf<Int>()
        val holidays = PendingHolidayOverrides(
            prefs,
            HolidayOverrideSender { id, _, _, _ ->
                sent += id
                true
            },
            syncs = { syncs },
        )

        holidays.retry(signIn)
        assertEquals(emptyList<Int>(), sent)

        syncs = true
        holidays.retry(signIn)
        assertEquals(listOf(3), sent)
    }

    @Test
    fun `on v3 a tap goes at once, stamped with the wall clock as it was made`() = runTest {
        prefs.holidayNotifyOverrides = setOf(3)
        val before = System.currentTimeMillis()
        v3Holidays().send(3, signIn)
        val after = System.currentTimeMillis()

        val sent = v3Backend.sent.single()
        assertEquals(listOf("PUT", "$v3/sync/holiday-overrides/3", listOf(bearer), json), sent.shape().take(4))
        val stamp = Regex(""""hlc":(\d+)""").find(sent.body.orEmpty())?.groupValues?.get(1)?.toLong()
        assertTrue("stamped ${sent.body}", stamp != null && stamp in before * 1_000..after * 1_000)
        assertEquals("""{"notify":true,"hlc":$stamp}""", sent.body)
        assertEquals(mapOf(3 to stamp!! / 1_000), prefs.holidayOverrideEditTimes)
        assertEquals(emptySet<Int>(), prefs.holidayOverridesAwaitingUpload)
    }

    @Test
    fun `on v3 a choice refused as ahead goes once more without its time`() = runTest {
        prefs.holidayNotifyOverrides = setOf(3)
        prefs.holidayOverrideEditTimes = mapOf(3 to madeAtMs)
        prefs.holidayOverridesAwaitingUpload = setOf(3)
        v3Backend.answer("/v3/sync/holiday-overrides/3", 409, """{"detail":"clock_ahead"}""")

        v3Holidays().retry(signIn)

        assertEquals(
            listOf(put(3, """{"notify":true,"hlc":1790000000123000}"""), put(3, """{"notify":true}""")),
            v3Shapes(),
        )
        assertEquals(emptySet<Int>(), prefs.holidayOverridesAwaitingUpload)
    }

    @Test
    fun `on v3 a refused choice is never parked, and every sync sends it again`() = runTest {
        val path = "/v3/sync/holiday-overrides/3"
        repeat(12) { v3Backend.answer(path, 404, """{"detail":"Holiday not found"}""") }
        prefs.holidayNotifyOverrides = setOf(3)
        val holidays = v3Holidays()

        holidays.send(3, signIn)
        repeat(9) { holidays.retry(signIn) }

        assertEquals(10, v3Backend.sent.size)
        assertEquals(setOf(3), prefs.holidayOverridesAwaitingUpload)
    }

    @Test
    fun `on v3 nothing goes or waits with TigerSync off, though the time of the choice is kept`() = runTest {
        prefs.cloudSyncEnabled = false
        prefs.holidayNotifyOverrides = setOf(3)
        val holidays = v3Holidays()

        holidays.send(3, signIn)
        holidays.retry(signIn)

        assertEquals(emptyList<RecordingBackend.Sent>(), v3Backend.sent.toList())
        assertEquals(emptySet<Int>(), prefs.holidayOverridesAwaitingUpload)
        assertEquals(setOf(3), prefs.holidayOverrideEditTimes.keys)
    }
}
