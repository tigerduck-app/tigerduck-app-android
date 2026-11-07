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
import org.ntust.app.tigerduck.push.RecordingBackend
import org.ntust.app.tigerduck.push.WriteAnswer
import org.ntust.app.tigerduck.push.pushApiClientAt
import org.ntust.app.tigerduck.push.syncFixture
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.sync.AckClock
import org.ntust.app.tigerduck.sync.DeviceClocks
import org.ntust.app.tigerduck.sync.EditTime
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
 * The holiday choices' queue over the real backend client and real preferences. Each choice is
 * sent with its age by the device's clocks, one an older build left with no time goes with this build's first run
 * (Review Focus 1), a choice made with TigerSync off is kept and goes once it is on, the reply's
 * clock is the choice's acknowledgment, and a reply that says the backend kept a later choice puts
 * it on this phone unless the user has chosen again (LWW D12). The queue is the outbox's holidays
 * channel, registered from its own file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HolidayChannelTest {

    private val context = JvmContext()
    private val prefs = AppPreferences(context)
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

    private fun RecordingBackend.Sent.shape() = listOf(method, url, headers, contentType, body)

    // --- /v4 ---

    private val v4 = "http://127.0.0.1:9/v4"
    private val v4Backend = RecordingBackend()
    private val v4Path = "/v4/sync/holiday-overrides/3"

    /** The wall clock the device's clocks and the old-entry time read; a test moves it. */
    private var nowMs = 1_790_000_500_000L
    private val deviceClocks = DeviceClocks.wallOnly { nowMs }
    private val ack = AckClock()
    private val oldEntryTime = OldEntryTime(context.getSharedPreferences("tigerduck_outbox", 0), Dispatchers.Unconfined) { nowMs }

    /** The queue as the app builds it, over the client at `/v4`, its edits timed by [deviceClocks]. */
    private fun TestScope.v4Holidays() = PendingHolidayOverrides(
        prefs, pushApiClientAt(v4, v4Backend.client, prefs, deviceClocks),
        clocks = deviceClocks, ack = ack, scope = backgroundScope, oldEntryTime = oldEntryTime, calendar = calendar,
    )

    /** The user's switch, as `CalendarViewModel.setNotifyOnHoliday` flips it: the local set, then the send. */
    private suspend fun PendingHolidayOverrides.tap(id: Int, notify: Boolean) {
        calendar.setNotifyOnHoliday(id, notify)
        send(id, signIn)
    }

    private fun ageOf(sent: RecordingBackend.Sent): Long? =
        Regex(""""age_ms":(\d+)""").find(sent.body.orEmpty())?.groupValues?.get(1)?.toLong()

    /** This build first ran at [firstRunMs]. */
    private fun firstRanAt(firstRunMs: Long) {
        val now = nowMs
        nowMs = firstRunMs
        oldEntryTime.recordFirstRun()
        nowMs = now
    }

    // --- Through a writer of the test's: the lane's rules ---

    @Test
    fun `a sync sends no waiting choice while TigerSync is off`() = runTest {
        prefs.holidayNotifyOverrides = setOf(3)
        prefs.holidayOverridesAwaitingUpload = setOf(3)
        var syncs = false
        val sent = mutableListOf<Int>()
        val holidays = PendingHolidayOverrides(
            prefs,
            HolidayOverrideWriter { id, _, _, _ ->
                sent += id
                TOOK
            },
            syncs = { syncs },
        )

        holidays.drain(signIn)
        assertEquals(emptyList<Int>(), sent)

        syncs = true
        holidays.drain(signIn)
        assertEquals(listOf(3), sent)
    }

    /**
     * Two choices of one holiday, the second while the first is out, and the second failing: each
     * goes once, as the build before sent them. The drain the first choice began does not send the
     * second again after the second's own has.
     */
    @Test
    fun `a second choice made while the first is out goes once though it fails`() = runTest {
        val sent = mutableListOf<Boolean>()
        val firstOut = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var now = 2_000L
        val holidays = PendingHolidayOverrides(
            prefs,
            HolidayOverrideWriter { _, notify, _, _ ->
                sent += notify
                if (sent.size > 1) throw IOException("offline")
                firstOut.complete(Unit)
                release.await()
                TOOK
            },
            syncs = { true },
            clocks = DeviceClocks.wallOnly { now },
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
    fun `a waiting sync of an ended sign-in does not keep the next student's choice from going`() = runTest {
        val sent = mutableListOf<Pair<Int, SignIn>>()
        val firstOut = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holidays = PendingHolidayOverrides(
            prefs,
            HolidayOverrideWriter { id, _, sentAs, _ ->
                sent += id to sentAs
                if (sent.size == 1) {
                    firstOut.complete(Unit)
                    release.await()
                }
                TOOK
            },
            syncs = { true },
        )
        val first = signIn

        val a = launch { holidays.send(3, first) }
        firstOut.await()
        val c = launch { holidays.drain(first) }
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

    // --- /v4: the outbox's holidays channel ---

    @Test
    fun `on v4 a choice goes with its age, and the reply's clock is the choice's acknowledgment`() = runTest {
        v4Backend.answer(v4Path, 200, syncFixture("v4/answer-holiday.json"), mapOf("X-TigerSync-Clock" to "1790000500002000"))

        v4Holidays().tap(3, notify = true)

        val sent = v4Backend.sent.single()
        assertEquals(listOf("PUT", "$v4/sync/holiday-overrides/3", listOf(bearer)), listOf(sent.method, sent.url, sent.headers))
        // Sent as it was made: 0 old.
        assertEquals("""{"notify":true,"age_ms":0}""", sent.body)
        assertEquals(mapOf(3 to EditTime(nowMs)), prefs.holidayOverrideEditTimes.mapValues { it.value.madeAt })
        assertEquals(1_790_000_500_002_000L, ack.lastAck(SyncField.holiday(3)))
        assertEquals(emptySet<Int>(), prefs.holidayOverridesAwaitingUpload)
    }

    @Test
    fun `an entry an older build wrote goes out on v4 aged from this build's first run, kept before it goes`() = runTest {
        val firstRunMs = nowMs - 200_000
        firstRanAt(firstRunMs)
        prefs.holidayNotifyOverrides = setOf(5)
        prefs.holidayOverridesAwaitingUpload = setOf(5)
        v4Backend.answer("/v4/sync/holiday-overrides/5", 503, """{"error":"internal","message":"down"}""")
        val holidays = v4Holidays()

        holidays.drain(signIn)
        // The time it went with is kept, so a later write cannot move it.
        assertEquals(mapOf(5 to EditTime(firstRunMs)), prefs.holidayOverrideEditTimes.mapValues { it.value.madeAt })
        nowMs += 60_000
        holidays.drain(signIn)

        assertEquals(listOf(200_000L, 260_000L), v4Backend.sent.map(::ageOf))
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
        holidays.drain(ended)
        holidays.drain(ended)
        holidays.send(4, ended)
        assertEquals(setOf(SyncField.holiday(5)), holidays.heldFields())

        assertEquals(emptyList<RecordingBackend.Sent>(), v4Backend.sent.toList())
        assertEquals(setOf(5), prefs.holidayOverridesAwaitingUpload)
        assertEquals("not even given its old-entry time", emptyMap<Int, EditTime>(), prefs.holidayOverrideEditTimes.mapValues { it.value.madeAt })
    }

    @Test
    fun `an edit made with the category off is kept on v4 and sent once it is on`() = runTest {
        val holidays = v4Holidays()
        prefs.cloudSyncEnabled = false
        holidays.tap(3, notify = true)
        val madeAtMs = nowMs
        holidays.drain(signIn)
        assertEquals(emptyList<RecordingBackend.Sent>(), v4Backend.sent.toList())
        assertEquals(setOf(3), prefs.holidayOverridesAwaitingUpload)

        prefs.cloudSyncEnabled = true
        nowMs += 60_000
        holidays.drain(signIn)

        assertEquals(listOf("""{"notify":true,"age_ms":${nowMs - madeAtMs}}"""), v4Backend.sent.map { it.body })
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
        assertEquals("with no time of this phone's", emptyMap<Int, Long>(), prefs.holidayOverrideEditTimes.mapValues { it.value.madeAt })
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
            clocks = DeviceClocks.wallOnly { nowMs },
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
        assertEquals(mapOf(3 to EditTime(nowMs)), prefs.holidayOverrideEditTimes.mapValues { it.value.madeAt })
        release.complete(Unit)
        first.join()
        second.join()

        assertEquals(listOf(true, false), sent)
        assertEquals("the first reply is the field's acknowledgment", 1_790_000_600_000_000L, ack.lastAck(SyncField.holiday(3)))
        assertEquals("the user's choice stays", emptySet<Int>(), calendar.optedInHolidayIds)
        assertEquals(mapOf(3 to EditTime(nowMs)), prefs.holidayOverrideEditTimes.mapValues { it.value.madeAt })
        assertEquals(emptySet<Int>(), prefs.holidayOverridesAwaitingUpload)
    }

    @Test
    fun `on v4 a refused choice is never parked, and one refused with a 409 is kept for the next sync`() = runTest {
        repeat(10) { v4Backend.answer(v4Path, 404, syncFixture("v4/error-not-found.json")) }
        val holidays = v4Holidays()

        holidays.tap(3, notify = true)
        repeat(9) { holidays.drain(signIn) }
        assertEquals(10, v4Backend.sent.size)
        assertEquals(setOf(3), prefs.holidayOverridesAwaitingUpload)

        v4Backend.answer(v4Path, 409, syncFixture("v4/error-clock-ahead.json"), mapOf("X-TigerSync-Clock" to "${(nowMs - 60_000) * 1_000}"))
        holidays.drain(signIn)
        assertEquals("never sent again on its own", 11, v4Backend.sent.size)
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
    fun `a choice made while an older one's time is read keeps its own time, and a snapshot meanwhile keeps it`() = runTest {
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
            prefs, pushApiClientAt(v4, v4Backend.client, prefs, deviceClocks),
            clocks = deviceClocks, ack = ack, scope = backgroundScope, oldEntryTime = times, calendar = calendar,
        )

        val retry = launch { holidays.drain(signIn) }
        testScheduler.runCurrent()
        // Turned off while its old-entry time is read: kept at once, its request waiting.
        val tap = launch { holidays.tap(5, notify = false) }
        testScheduler.runCurrent()
        val tappedAt = prefs.holidayOverrideEditTimes.getValue(5).madeAt
        assertTrue(
            "a snapshot fetched after the choice keeps it as this phone has it",
            5 in holidays.keptOver(),
        )
        disk.release()
        retry.join()
        tap.join()

        assertEquals("the choice keeps its own time", mapOf(5 to tappedAt), prefs.holidayOverrideEditTimes.mapValues { it.value.madeAt })
        assertEquals(
            listOf("""{"notify":false,"age_ms":${nowMs - firstRunMs}}""", """{"notify":false,"age_ms":${nowMs - tappedAt.wallMs}}"""),
            v4Backend.sent.map { it.body },
        )
        assertEquals(emptySet<Int>(), prefs.holidayOverridesAwaitingUpload)
    }

    @Test
    fun `a build without TigerSync keeps no holiday choice, and on v4 one with it keeps every choice`() {
        for (cloudSync in listOf(false, true)) for (flavor in listOf(false, true)) {
            val policy = SyncPolicy(cloudSync, flavor, switchedOn = emptySet(), held = emptySet())
            assertEquals("cloudSync=$cloudSync flavor=$flavor", flavor, policy.keepsHolidays)
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
            ack = ack,
            scope = backgroundScope,
            calendar = calendar,
            main = Dispatchers.Unconfined,
        )

        holidays.tap(3, notify = false)

        assertEquals(emptySet<Int>(), prefs.holidayNotifyOverrides)
        assertEquals(emptySet<Int>(), prefs.holidayOverridesAwaitingUpload)
        assertEquals(emptyMap<Int, Long>(), prefs.holidayOverrideEditTimes.mapValues { it.value.madeAt })
        assertNull("nor is the departing student's acknowledgment the next one's", ack.lastAck(SyncField.holiday(3)))
    }
}
