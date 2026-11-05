@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import okhttp3.OkHttpClient
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.StampedValue
import java.io.IOException
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.shared.account.SignIn

/**
 * Holiday choices whose PUT has not landed ([PendingHolidayOverrides]). A
 * failed upload used to be only logged: the choice stayed on this phone, and
 * the next full sync, which replaced the set with the server's, took it back.
 * As the iPhone's `holidayOverridesAwaitingUpload`.
 */
class PendingHolidayOverridesTest {

    private lateinit var prefs: AppPreferences
    private lateinit var holidays: PendingHolidayOverrides

    private val sent = mutableListOf<Pair<Int, Boolean>>()

    // The edit time each PUT carried as `hlc` (before the clock's microsecond scale).
    private val stamps = mutableListOf<Long?>()
    private var answer: suspend (Int, Boolean) -> Unit = { _, _ -> }

    /** TigerSync on. */
    private var syncs = true

    /** Whether the client sent the PUT: false as with TigerSync turned off before its turn. */
    private var delivered = true

    /** A sign-out that lands as the PUT's answer comes back, clearing both sets. */
    private var signOutAtTheAnswer = false
    private val series = SignInSeries()
    private val signIn get() = series.current
    private var now = 1_000_000L

    /** The sign-in each PUT was pinned to. */
    private val signIns = mutableListOf<SignIn>()

    @Before
    fun setUp() {
        prefs = AppPreferences(JvmContext())
        holidays = PendingHolidayOverrides(
            prefs = prefs,
            writer = HolidayOverrideWriter { id, notify, signIn, madeAtMs ->
                signIns += signIn
                stamps += madeAtMs
                sent += id to notify
                answer(id, notify)
                if (signOutAtTheAnswer) {
                    // The sign-in ends first, and the sets are cleared after.
                    series.end()
                    prefs.forgetHolidayOverrides()
                    series.open()
                }
                tookIf(delivered)
            },
            syncs = { syncs },
            clock = { now },
        )
    }

    /** The user's tap, as `CalendarViewModel.setNotifyOnHoliday` makes it: the local set first. */
    private suspend fun tap(id: Int, notify: Boolean) {
        val current = prefs.holidayNotifyOverrides
        prefs.holidayNotifyOverrides = if (notify) current + id else current - id
        holidays.send(id, signIn)
    }

    @Test
    fun `a choice whose PUT fails is kept, and the next sync sends today's value and lets it go`() = runTest {
        answer = { _, _ -> throw IOException("offline") }
        tap(3, notify = true)
        assertEquals(setOf(3), holidays.waitingIds)

        // Changed back while it waited: the retry sends what the user wants now.
        prefs.holidayNotifyOverrides = emptySet()
        answer = { _, _ -> }
        holidays.drain(signIn)

        assertEquals(listOf(3 to true, 3 to false), sent)
        assertEquals(emptySet<Int>(), holidays.waitingIds)
    }

    @Test
    fun `a choice sent after a newer one carries the time it was made`() = runTest {
        answer = { _, _ -> throw IOException("offline") }
        now = 2_000L
        tap(3, notify = true)
        answer = { _, _ -> }
        now = 3_000L
        tap(5, notify = true)
        now = 4_000L
        holidays.drain(signIn)

        // 3 failed at T1, 5 landed at T2, and the retry sent 3 again at its own time.
        assertEquals(listOf<Long?>(2_000L, 3_000L, 2_000L), stamps)
        assertEquals(emptySet<Int>(), holidays.waitingIds)
    }

    @Test
    fun `a choice made again while sync is off is sent with the time of the new edit`() = runTest {
        answer = { _, _ -> throw IOException("offline") }
        now = 2_000L
        tap(3, notify = true)
        assertEquals(setOf(3), holidays.waitingIds)

        // TigerSync off: the flip is this phone's own, but the choice still waiting is now this one.
        syncs = false
        answer = { _, _ -> }
        now = 3_000L
        tap(3, notify = false)
        syncs = true
        now = 4_000L
        holidays.drain(signIn)

        assertEquals(listOf(3 to true, 3 to false), sent)
        assertEquals(listOf<Long?>(2_000L, 3_000L), stamps)
    }

    @Test
    fun `a choice made again during its PUT stays waiting though its setting is the same`() = runTest {
        val gate = CompletableDeferred<Unit>()
        answer = { _, _ -> gate.await() }
        now = 2_000L
        val first = launch { tap(3, notify = true) }
        testScheduler.runCurrent()

        // Off and on again while the PUT is out: the same setting, made later.
        now = 3_000L
        prefs.holidayNotifyOverrides = emptySet()
        val off = launch { holidays.send(3, signIn) }
        testScheduler.runCurrent()
        now = 4_000L
        prefs.holidayNotifyOverrides = setOf(3)
        val on = launch { holidays.send(3, signIn) }
        testScheduler.runCurrent()
        gate.complete(Unit)
        first.join()
        off.join()
        on.join()

        assertEquals(listOf<Long?>(2_000L, 4_000L), stamps)
        assertEquals(emptySet<Int>(), holidays.waitingIds)
    }

    @Test
    fun `a pull that replaces a choice drops its edit time, and one it leaves keeps it`() = runTest {
        val context = JvmContext()
        val calendar = AcademicCalendarStore(context, OkHttpClient(), prefs)
        now = 2_000L
        tap(9, notify = true)
        now = 3_000L
        tap(5, notify = true)
        assertEquals(mapOf(9 to 2_000L, 5 to 3_000L), prefs.holidayOverrideEditTimes)

        now = 5_000L
        HolidayOverrideApply.applyStamped(
            mapOf(5 to StampedValue(true, 1L)), serverHlc = now * 1_000, keeps = { false }, signIn = signIn, pending = holidays, calendar = calendar,
        )

        assertEquals(setOf(5), prefs.holidayNotifyOverrides)
        assertEquals(mapOf(5 to 3_000L), prefs.holidayOverrideEditTimes)
    }

    @Test
    fun `a choice that lands is let go at once`() = runTest {
        tap(3, notify = true)

        assertEquals(listOf(3 to true), sent)
        assertEquals(listOf(signIn), signIns)
        assertEquals(emptySet<Int>(), holidays.waitingIds)
    }

    @Test
    fun `a choice changed while its PUT is under way stays waiting`() = runTest {
        val gate = CompletableDeferred<Unit>()
        answer = { _, _ -> gate.await() }
        val first = launch { tap(3, notify = true) }
        testScheduler.runCurrent()

        // Tapped again mid-request: the server is about to hold the older value.
        prefs.holidayNotifyOverrides = emptySet()
        gate.complete(Unit)
        first.join()

        assertEquals(setOf(3), holidays.waitingIds)
    }

    @Test
    fun `a PUT asked before a sign-out is neither sent nor settled as the next account`() = runTest {
        val gate = CompletableDeferred<Unit>()
        answer = { _, _ -> gate.await() }
        val first = launch { tap(3, notify = true) }
        testScheduler.runCurrent()
        val second = launch { tap(4, notify = true) }
        testScheduler.runCurrent()

        // Signed out and in as someone else while 3 was under way and 4 waited behind it.
        series.end()
        series.open()
        prefs.holidayOverridesAwaitingUpload = setOf(4)
        gate.complete(Unit)
        first.join()
        second.join()

        assertEquals("4 never went up as the next account", listOf(3 to true), sent)
        assertEquals("nor was the next account's waiting set let go by 3's answer", setOf(4), holidays.waitingIds)
    }

    /** The gap between the sign-in's end and the wipe: the entry is still kept, and stays as it is. */
    @Test
    fun `a choice read under an ended sign-in is neither sent nor rewritten`() = runTest {
        answer = { _, _ -> throw IOException("offline") }
        tap(3, notify = true)
        sent.clear()
        val times = prefs.holidayOverrideEditTimes

        val ended = series.end()
        holidays.drain(ended)
        // The sign-out's clear has run by now, or runs before the wipe: marked here it would stay.
        prefs.forgetHolidayOverrides()
        holidays.send(4, ended)

        assertEquals(emptyList<Pair<Int, Boolean>>(), sent)
        assertEquals("nothing for the next account to send", emptySet<Int>(), holidays.waitingIds)
        assertEquals(emptyMap<Int, Long>(), prefs.holidayOverrideEditTimes)
        assertEquals(mapOf(3 to times.getValue(3)), times)
    }

    @Test
    fun `a sync snapshot keeps the choices still waiting as this phone has them`() = runTest {
        answer = { _, _ -> throw IOException("offline") }
        tap(3, notify = true)
        tap(5, notify = false)
        now += 1_000

        // The server knows 5 and 9 but not 3, and still has the 5 this phone took back.
        val calendar = AcademicCalendarStore(JvmContext(), OkHttpClient(), prefs)
        HolidayOverrideApply.applyStamped(
            mapOf(5 to StampedValue(true, 1L), 9 to StampedValue(true, 1L)), serverHlc = now * 1_000, keeps = { false },
            signIn = signIn, pending = holidays, calendar = calendar,
        )

        assertEquals(setOf(3, 9), prefs.holidayNotifyOverrides)
    }

    @Test
    fun `a PUT the client did not send is not taken for landed`() = runTest {
        delivered = false
        tap(3, notify = true)

        assertEquals(setOf(3), holidays.waitingIds)
    }

    @Test
    fun `an acknowledgment is written only while its sign-in holds`() = runTest {
        prefs.holidayOverridesAwaitingUpload = setOf(4)
        signOutAtTheAnswer = true

        tap(3, notify = true)

        assertEquals("the departing account's 4 is not written back over the sign-out", emptySet<Int>(), holidays.waitingIds)
    }

    /** The next account's choice reads the same as the one that landed, down to its time. */
    @Test
    fun `an old run's answer leaves the next account's same choice waiting`() = runTest {
        val gate = CompletableDeferred<Unit>()
        answer = { _, _ -> gate.await() }
        val first = launch { tap(3, notify = true) }
        testScheduler.runCurrent()

        series.end()
        prefs.forgetHolidayOverrides()
        series.open()
        prefs.holidayNotifyOverrides = setOf(3)
        prefs.holidayOverrideEditTimes = mapOf(3 to now)
        prefs.holidayOverridesAwaitingUpload = setOf(3)
        gate.complete(Unit)
        first.join()

        assertEquals(setOf(3), holidays.waitingIds)
    }

    @Test
    fun `the sign-out forgets both sets`() {
        prefs.holidayNotifyOverrides = setOf(3)
        prefs.holidayOverridesAwaitingUpload = setOf(3)
        prefs.holidayOverrideEditTimes = mapOf(3 to 1_000L)

        prefs.forgetHolidayOverrides()

        assertEquals(emptySet<Int>(), prefs.holidayNotifyOverrides)
        assertEquals(emptySet<Int>(), prefs.holidayOverridesAwaitingUpload)
        assertEquals(emptyMap<Int, Long>(), prefs.holidayOverrideEditTimes)
    }
}
