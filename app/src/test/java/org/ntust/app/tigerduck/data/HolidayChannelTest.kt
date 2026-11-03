package org.ntust.app.tigerduck.data

import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.ApiVersionGate
import org.ntust.app.tigerduck.network.ApiVersionInterceptor
import org.ntust.app.tigerduck.network.backend.BackendApi
import org.ntust.app.tigerduck.push.RecordingBackend
import org.ntust.app.tigerduck.push.WriteAnswer
import org.ntust.app.tigerduck.push.pushApiClientAt
import org.ntust.app.tigerduck.push.syncFixture
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.SyncClock
import org.ntust.app.tigerduck.sync.SyncClockStore
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.SyncPolicy
import org.ntust.app.tigerduck.sync.outbox.OldEntryTime
import org.ntust.app.tigerduck.sync.outbox.Outbox
import org.ntust.app.tigerduck.sync.outbox.OutboxChannel
import org.ntust.app.tigerduck.sync.outbox.OutboxChannelId
import org.ntust.app.tigerduck.util.JvmContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/**
 * The holiday choices' queue over the real backend client and real preferences. What it sends on
 * `/v3` is pinned request by request, as the build before the outbox sent it: the address, every
 * header the client builds, the body and its type, for a tap, a waiting choice with its time or
 * with none, a resend after a `clock_ahead`, and how a refusal and TigerSync's switch decide what
 * goes again.
 *
 * On `/v4` each choice is stamped by the sync clock, one an older build left with no time goes with
 * this build's first run (Review Focus 1), a choice made with TigerSync off is kept and goes once it
 * is on, the reply's clock is the choice's acknowledgment, and a reply that says the backend kept a
 * later choice puts it on this phone unless the user has chosen again (LWW D12). The queue is the
 * outbox's holidays channel, registered from its own file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HolidayChannelTest {

    private val v3 = "http://127.0.0.1:9/v3"
    private val context = JvmContext()
    private val prefs = AppPreferences(context)
    private val v3Backend = RecordingBackend(ahead = listOf(ApiVersionInterceptor { v3 }))
    private val series = SignInSeries()
    private val signIn get() = series.current
    private val calendar = AcademicCalendarStore(context, OkHttpClient(), prefs)

    private val madeAtMs = 1_790_000_000_123L
    private val bearer = "Authorization" to "Bearer access"
    private val json = "application/json; charset=utf-8"

    @Before
    fun openGate() {
        ApiVersionGate.resetForTesting()
        // The Calendar's thread, where a choice the backend kept is written; runTest shares its clock.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // TigerSync is off for good on F-Droid, where none of this goes out at all.
        assumeTrue(prefs.cloudSyncEnabled)
    }

    @After
    fun closeGate() {
        ApiVersionGate.resetForTesting()
        Dispatchers.resetMain()
    }

    /** The queue as the app builds it, over the client at `/v3`. */
    private fun v3Holidays() = PendingHolidayOverrides(prefs, pushApiClientAt(v3, v3Backend.client, prefs), calendar = calendar)

    private fun RecordingBackend.Sent.shape() = listOf(method, url, headers, contentType, body)

    private fun v3Shapes() = v3Backend.sent.map { it.shape() }

    private fun put(id: Int, body: String) = listOf("PUT", "$v3/sync/holiday-overrides/$id", listOf(bearer), json, body)

    // --- /v4 ---

    private val v4 = "http://127.0.0.1:9/v4"
    private val v4Backend = RecordingBackend()
    private val v4Path = "/v4/sync/holiday-overrides/3"

    /** The wall clock the sync clock and the old-entry time read; a test moves it. */
    private var nowMs = 1_790_000_500_000L
    private val syncClock = SyncClock(SyncClockStore(context.getSharedPreferences("sync-clock", 0)), { BackendApi.V4 }, { nowMs })
    private val ack = AckClock()
    private val oldEntryTime = OldEntryTime(context.getSharedPreferences("tigerduck_outbox", 0), Dispatchers.Unconfined) { nowMs }

    /** The queue as the app builds it, over the client at `/v4`, stamping with [syncClock]. */
    private fun TestScope.v4Holidays() = PendingHolidayOverrides(
        prefs, pushApiClientAt(v4, v4Backend.client, prefs, syncClock),
        syncClock = syncClock, ack = ack, scope = backgroundScope, oldEntryTime = oldEntryTime, calendar = calendar,
    )

    /** The user's switch, as `CalendarViewModel.setNotifyOnHoliday` flips it: the local set, then the send. */
    private suspend fun PendingHolidayOverrides.tap(id: Int, notify: Boolean) {
        calendar.setNotifyOnHoliday(id, notify)
        send(id, signIn)
    }

    private fun hlcOf(sent: RecordingBackend.Sent): Long? =
        Regex(""""hlc":(\d+)""").find(sent.body.orEmpty())?.groupValues?.get(1)?.toLong()

    /** This build first ran at [firstRunMs]. */
    private fun firstRanAt(firstRunMs: Long) {
        val now = nowMs
        nowMs = firstRunMs
        oldEntryTime.recordFirstRun()
        nowMs = now
    }

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

    /**
     * Two choices of one holiday, the second while the first is out, and the second failing: each
     * goes once, as the build before sent them. The drain the first choice began does not send the
     * second again after the second's own has.
     */
    @Test
    fun `on v3 a second choice made while the first is out goes once though it fails`() = runTest {
        val sent = mutableListOf<Boolean>()
        val firstOut = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var now = 2_000L
        val holidays = PendingHolidayOverrides(
            prefs,
            HolidayOverrideSender { _, notify, _, _ ->
                sent += notify
                if (sent.size > 1) throw IOException("offline")
                firstOut.complete(Unit)
                release.await()
                true
            },
            syncs = { true },
            clock = { now },
        )
        prefs.holidayNotifyOverrides = setOf(3)

        val first = launch { holidays.send(3, signIn) }
        firstOut.await()
        now = 3_000L
        prefs.holidayNotifyOverrides = emptySet()
        val second = launch { holidays.send(3, signIn) }
        testScheduler.runCurrent()
        release.complete(Unit)
        first.join()
        second.join()

        assertEquals(listOf(true, false), sent)
        assertEquals(setOf(3), prefs.holidayOverridesAwaitingUpload)
    }

    /**
     * The first student's choice out, and a sync of theirs waiting behind it, when they sign out and
     * the next student makes one: the next student's goes, once, under their own sign-in.
     */
    @Test
    fun `on v3 a waiting sync of an ended sign-in does not keep the next student's choice from going`() = runTest {
        val sent = mutableListOf<Pair<Int, SignIn>>()
        val firstOut = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holidays = PendingHolidayOverrides(
            prefs,
            HolidayOverrideSender { id, _, sentAs, _ ->
                sent += id to sentAs
                if (sent.size == 1) {
                    firstOut.complete(Unit)
                    release.await()
                }
                true
            },
            syncs = { true },
        )
        val first = signIn

        val a = launch { holidays.send(3, first) }
        firstOut.await()
        val c = launch { holidays.retry(first) }
        testScheduler.runCurrent()
        // Signed out: the choices cleared, and the next student signs in and makes one.
        series.end()
        prefs.forgetHolidayOverrides()
        series.open()
        val second = series.current
        val next = launch { holidays.send(4, second) }
        testScheduler.runCurrent()
        release.complete(Unit)
        a.join()
        c.join()
        next.join()

        assertEquals(listOf(3 to first, 4 to second), sent)
        assertEquals(emptySet<Int>(), prefs.holidayOverridesAwaitingUpload)
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

    // --- /v4: the outbox's holidays channel ---

    @Test
    fun `on v4 a choice goes stamped by the sync clock, and the reply's clock is the choice's acknowledgment`() = runTest {
        v4Backend.answer(v4Path, 200, syncFixture("v4/answer-holiday.json"), mapOf("X-TigerSync-Clock" to "1790000500002000"))

        v4Holidays().tap(3, notify = true)

        val sent = v4Backend.sent.single()
        assertEquals(listOf("PUT", "$v4/sync/holiday-overrides/3", listOf(bearer)), listOf(sent.method, sent.url, sent.headers))
        // Nothing heard yet: the wall clock, a tick on.
        assertEquals("""{"notify":true,"hlc":${(nowMs + 1) * 1_000}}""", sent.body)
        assertEquals(mapOf(3 to nowMs + 1), prefs.holidayOverrideEditTimes)
        assertEquals(1_790_000_500_002_000L, ack.lastAck(SyncField.holiday(3)))
        assertEquals(emptySet<Int>(), prefs.holidayOverridesAwaitingUpload)
    }

    @Test
    fun `an entry an older build wrote goes out on v4 stamped with this build's first run, kept before it goes`() = runTest {
        val firstRunMs = nowMs - 200_000
        firstRanAt(firstRunMs)
        prefs.holidayNotifyOverrides = setOf(5)
        prefs.holidayOverridesAwaitingUpload = setOf(5)
        v4Backend.answer("/v4/sync/holiday-overrides/5", 503, """{"error":"internal","message":"down"}""")
        val holidays = v4Holidays()

        holidays.retry(signIn)
        // The time it went with is kept, so a later write cannot move it.
        assertEquals(mapOf(5 to firstRunMs), prefs.holidayOverrideEditTimes)
        nowMs += 60_000
        holidays.retry(signIn)

        assertEquals(listOf(firstRunMs * 1_000, firstRunMs * 1_000), v4Backend.sent.map(::hlcOf))
        assertEquals(emptySet<Int>(), prefs.holidayOverridesAwaitingUpload)
    }

    /** The gap between the sign-in's end and the clear: the choice is still kept, and stays as it is. */
    @Test
    fun `on v4 a choice read under an ended sign-in is neither sent nor rewritten`() = runTest {
        firstRanAt(nowMs - 200_000)
        prefs.holidayNotifyOverrides = setOf(5)
        prefs.holidayOverridesAwaitingUpload = setOf(5)
        val holidays = v4Holidays()

        val ended = series.end()
        holidays.retry(ended)
        holidays.drain(ended)
        holidays.send(4, ended)
        assertEquals(setOf(SyncField.holiday(5)), holidays.heldFields())

        assertEquals(emptyList<RecordingBackend.Sent>(), v4Backend.sent.toList())
        assertEquals(setOf(5), prefs.holidayOverridesAwaitingUpload)
        assertEquals("not even given its old-entry time", emptyMap<Int, Long>(), prefs.holidayOverrideEditTimes)
    }

    @Test
    fun `an edit made with the category off is kept on v4 and sent once it is on`() = runTest {
        val holidays = v4Holidays()
        prefs.cloudSyncEnabled = false
        holidays.tap(3, notify = true)
        val madeAtMs = nowMs + 1
        holidays.retry(signIn)
        assertEquals(emptyList<RecordingBackend.Sent>(), v4Backend.sent.toList())
        assertEquals(setOf(3), prefs.holidayOverridesAwaitingUpload)

        prefs.cloudSyncEnabled = true
        nowMs += 60_000
        holidays.retry(signIn)

        assertEquals(listOf("""{"notify":true,"hlc":${madeAtMs * 1_000}}"""), v4Backend.sent.map { it.body })
        assertEquals(emptySet<Int>(), prefs.holidayOverridesAwaitingUpload)
    }

    @Test
    fun `a refused v4 write applies the backend's value unless a newer edit waits`() = runTest {
        v4Backend.answer(v4Path, 200, syncFixture("v4/answer-holiday-lost.json"))
        val told = mutableListOf<Set<Int>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { calendar.changes.collect { told += calendar.optedInHolidayIds } }

        v4Holidays().tap(3, notify = true)

        assertEquals("the backend's choice is this phone's now", emptySet<Int>(), calendar.optedInHolidayIds)
        assertEquals("and the calendar's pages are told", listOf(setOf(3), emptySet<Int>()), told)
        assertEquals("with no time of this phone's", emptyMap<Int, Long>(), prefs.holidayOverrideEditTimes)
        assertEquals("let go, not sent again", 1, v4Backend.sent.size)
        assertEquals(emptySet<Int>(), prefs.holidayOverridesAwaitingUpload)
    }

    @Test
    fun `the backend's choice is not taken over one the user made since, which goes next`() = runTest {
        val sent = mutableListOf<Boolean>()
        val firstOut = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holidays = PendingHolidayOverrides(
            prefs = prefs,
            writer = HolidayOverrideWriter { _, notify, _, _ ->
                sent += notify
                if (sent.size == 1) {
                    firstOut.complete(Unit)
                    release.await()
                    // The backend kept a later choice than the first one.
                    WriteAnswer(sent = true, serverClockUs = 1_790_000_600_000_000L, applied = emptySet(), values = mapOf("notify" to true))
                } else {
                    WriteAnswer(sent = true, serverClockUs = null, applied = setOf("notify"), values = mapOf("notify" to notify))
                }
            },
            syncs = { true },
            stamp = { nowMs },
            lastWriterWins = { true },
            ack = ack,
            scope = backgroundScope,
            calendar = calendar,
            main = Dispatchers.Unconfined,
        )

        val first = launch { holidays.tap(3, notify = true) }
        firstOut.await()
        // Turned off again while the first was out: kept at once, its request waiting for the first's reply.
        nowMs += 1_000
        val second = launch { holidays.tap(3, notify = false) }
        testScheduler.runCurrent()
        assertEquals(mapOf(3 to nowMs), prefs.holidayOverrideEditTimes)
        release.complete(Unit)
        first.join()
        second.join()

        assertEquals(listOf(true, false), sent)
        assertEquals("the first reply is the field's acknowledgment", 1_790_000_600_000_000L, ack.lastAck(SyncField.holiday(3)))
        assertEquals("the user's choice stays", emptySet<Int>(), calendar.optedInHolidayIds)
        assertEquals(mapOf(3 to nowMs), prefs.holidayOverrideEditTimes)
        assertEquals(emptySet<Int>(), prefs.holidayOverridesAwaitingUpload)
    }

    @Test
    fun `on v4 a refused choice is never parked, and one refused as ahead twice is kept for the next sync`() = runTest {
        repeat(10) { v4Backend.answer(v4Path, 404, syncFixture("v4/error-not-found.json")) }
        val holidays = v4Holidays()

        holidays.tap(3, notify = true)
        repeat(9) { holidays.retry(signIn) }
        assertEquals(10, v4Backend.sent.size)
        assertEquals(setOf(3), prefs.holidayOverridesAwaitingUpload)

        repeat(2) {
            v4Backend.answer(v4Path, 409, syncFixture("v4/error-clock-ahead.json"), mapOf("X-TigerSync-Clock" to "${(nowMs - 60_000) * 1_000}"))
        }
        holidays.retry(signIn)
        assertEquals("stamped, then stamped again", 12, v4Backend.sent.size)
        assertEquals(setOf(3), prefs.holidayOverridesAwaitingUpload)
    }

    @Test
    fun `the holiday choices are the outbox's holidays channel`() = runTest {
        v4Backend.answer(v4Path, 503, """{"error":"internal","message":"down"}""")
        val holidays = v4Holidays()
        val outbox = Outbox(setOf(holidays))
        assertEquals(OutboxChannelId.HOLIDAYS, holidays.id)
        holidays.tap(3, notify = true)
        assertEquals(setOf(SyncField.holiday(3)), outbox.heldFields())

        outbox.courseUploadLanded(signIn)
        outbox.forgetAccount()
        outbox.drain(signIn)

        assertEquals(2, v4Backend.sent.size)
        assertEquals(emptySet<String>(), outbox.heldFields())
    }

    @Test
    fun `the holiday choices register as an outbox channel from their own file`() {
        val provides = HolidayChannelModule::class.java.getMethod("channel", PendingHolidayOverrides::class.java)
        assertTrue(HolidayChannelModule::class.java.isAnnotationPresent(Module::class.java))
        assertTrue(provides.isAnnotationPresent(Provides::class.java))
        assertTrue(provides.isAnnotationPresent(IntoSet::class.java))
        assertEquals(OutboxChannel::class.java, provides.returnType)
    }

    /** The disk, held at its first read until the test lets it go: a read that takes its time. Later reads run at once. */
    private class HeldDisk : CoroutineDispatcher() {
        private var held: Runnable? = null
        private var fired = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (fired) return block.run()
            fired = true
            held = block
        }

        fun release() {
            held?.run()
            held = null
        }
    }

    @Test
    fun `a choice made while an older one's time is read keeps its own time, and no snapshot applies meanwhile`() = runTest {
        val disk = HeldDisk()
        val firstRunMs = nowMs - 200_000
        val times = OldEntryTime(context.getSharedPreferences("tigerduck_outbox_held", 0), disk) { nowMs }
        nowMs = firstRunMs
        times.recordFirstRun()
        nowMs = firstRunMs + 200_000
        // Left waiting by an older build, with no time.
        prefs.holidayNotifyOverrides = setOf(5)
        prefs.holidayOverridesAwaitingUpload = setOf(5)
        val holidays = PendingHolidayOverrides(
            prefs, pushApiClientAt(v4, v4Backend.client, prefs, syncClock),
            syncClock = syncClock, ack = ack, scope = backgroundScope, oldEntryTime = times, calendar = calendar,
        )

        val retry = launch { holidays.retry(signIn) }
        testScheduler.runCurrent()
        // Turned off while its old-entry time is read: kept at once, its request waiting.
        val tap = launch { holidays.tap(5, notify = false) }
        testScheduler.runCurrent()
        val tappedAt = prefs.holidayOverrideEditTimes.getValue(5)
        assertNull(
            "a snapshot fetched after the choice waits for its PUT",
            holidays.merged(snapshot = setOf(5), fetchedAtMs = System.currentTimeMillis() + 60_000, local = emptySet()),
        )
        disk.release()
        retry.join()
        tap.join()

        assertEquals("the choice keeps its own time", mapOf(5 to tappedAt), prefs.holidayOverrideEditTimes)
        assertEquals(
            listOf("""{"notify":false,"hlc":${firstRunMs * 1_000}}""", """{"notify":false,"hlc":${tappedAt * 1_000}}"""),
            v4Backend.sent.map { it.body },
        )
        assertEquals(emptySet<Int>(), prefs.holidayOverridesAwaitingUpload)
    }

    @Test
    fun `a build without TigerSync keeps no holiday choice, and on v4 one with it keeps every choice`() {
        for (cloudSync in listOf(false, true)) for (lww in listOf(false, true)) for (flavor in listOf(false, true)) {
            val policy = SyncPolicy(cloudSync, flavor, switchedOn = emptySet(), held = emptySet(), lastWriterWins = lww)
            val expected = when {
                !flavor -> false
                lww -> true
                else -> cloudSync
            }
            assertEquals("cloudSync=$cloudSync lww=$lww flavor=$flavor", expected, policy.keepsHolidays)
        }
    }

    /** Calendar's switch runs on Main; a choice written off it can erase one made after the check, as the pull's apply would. */
    @Test
    fun `a choice the backend kept is written on the Calendar's thread, under the lane's lock`() {
        val path = "src/main/java/org/ntust/app/tigerduck/data/PendingHolidayOverrides.kt"
        var dir: File? = File("").absoluteFile
        var source: String? = null
        while (dir != null && source == null) {
            source = listOf(File(dir, path), File(dir, "app/$path")).firstOrNull { it.isFile }?.readText()
            dir = dir.parentFile
        }
        checkNotNull(source) { "no $path" }
        assertTrue(Regex("""withContext\(main\) \{\s*lane\.edit\(signIn\) \{""").containsMatchIn(source))
        assertTrue(Regex("""private val main: CoroutineContext = Dispatchers\.Main,""").containsMatchIn(source))
    }

    @Test
    fun `a choice kept while TigerSync is off does not go with its tap`() = runTest {
        val sent = mutableListOf<Int>()
        val holidays = PendingHolidayOverrides(
            prefs = prefs,
            writer = HolidayOverrideWriter { id, _, _, _ ->
                sent += id
                WriteAnswer(sent = true, serverClockUs = null, applied = null, values = emptyMap())
            },
            syncs = { false },
            keeps = { true },
            lastWriterWins = { true },
            scope = backgroundScope,
            main = Dispatchers.Unconfined,
        )

        holidays.tap(3, notify = true)

        assertEquals(emptyList<Int>(), sent)
        assertEquals(setOf(3), prefs.holidayOverridesAwaitingUpload)
    }

    /** Review Focus 5: the reply lands after the sign-out, and the next student's choices are not the ones it was read from. */
    @Test
    fun `a v4 reply that crossed a sign-out puts nothing in the next student's choices`() = runTest {
        prefs.holidayNotifyOverrides = setOf(3)
        val holidays = PendingHolidayOverrides(
            prefs = prefs,
            writer = HolidayOverrideWriter { _, _, _, _ ->
                // Signed out, the choices cleared, and someone else signed in while the choice was out.
                series.end()
                prefs.forgetHolidayOverrides()
                series.open()
                WriteAnswer(sent = true, serverClockUs = 1_790_000_600_000_000L, applied = emptySet(), values = mapOf("notify" to true))
            },
            syncs = { true },
            lastWriterWins = { true },
            ack = ack,
            scope = backgroundScope,
            calendar = calendar,
            main = Dispatchers.Unconfined,
        )

        holidays.tap(3, notify = false)

        assertEquals(emptySet<Int>(), prefs.holidayNotifyOverrides)
        assertEquals(emptySet<Int>(), prefs.holidayOverridesAwaitingUpload)
        assertEquals(emptyMap<Int, Long>(), prefs.holidayOverrideEditTimes)
        assertNull("nor is the departing student's acknowledgment the next one's", ack.lastAck(SyncField.holiday(3)))
    }
}
