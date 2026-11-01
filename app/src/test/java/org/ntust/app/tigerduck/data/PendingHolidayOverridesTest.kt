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
import java.io.IOException
import org.ntust.app.tigerduck.util.JvmContext

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

    /** A sign-out that lands just as an acknowledgment is written, clearing both sets. */
    private var signOutAtTheWrite = false
    private var signInGeneration = 1
    private var now = 1_000_000L

    /** The sign-in each PUT was pinned to. */
    private val signIns = mutableListOf<Int>()

    @Before
    fun setUp() {
        prefs = AppPreferences(JvmContext())
        holidays = PendingHolidayOverrides(
            prefs = prefs,
            sender = HolidayOverrideSender { id, notify, signIn, madeAtMs ->
                signIns += signIn
                stamps += madeAtMs
                sent += id to notify
                answer(id, notify)
                delivered
            },
            syncs = { syncs },
            signInGeneration = { signInGeneration },
            runIfSignedIn = { signIn, block ->
                if (signOutAtTheWrite) {
                    // The sign-out moved the generation under the lock first, and cleared after.
                    signInGeneration++
                    prefs.forgetHolidayOverrides()
                }
                if (signIn == signInGeneration) {
                    block()
                    true
                } else {
                    false
                }
            },
            clock = { now },
        )
    }

    /** The user's tap, as `CalendarViewModel.setNotifyOnHoliday` makes it: the local set first. */
    private suspend fun tap(id: Int, notify: Boolean) {
        val current = prefs.holidayNotifyOverrides
        prefs.holidayNotifyOverrides = if (notify) current + id else current - id
        holidays.send(id)
    }

    @Test
    fun `a choice whose PUT fails is kept, and the next sync sends today's value and lets it go`() = runTest {
        answer = { _, _ -> throw IOException("offline") }
        tap(3, notify = true)
        assertEquals(setOf(3), holidays.waitingIds)

        // Changed back while it waited: the retry sends what the user wants now.
        prefs.holidayNotifyOverrides = emptySet()
        answer = { _, _ -> }
        holidays.retry()

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
        holidays.retry()

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
        holidays.retry()

        assertEquals(listOf(3 to true, 3 to false), sent)
        assertEquals(listOf<Long?>(2_000L, 3_000L), stamps)
    }

    @Test
    fun `a choice waiting from the build before has no time, and goes out without one`() = runTest {
        prefs.holidayNotifyOverrides = setOf(4)
        prefs.holidayOverridesAwaitingUpload = setOf(4)
        assertEquals(emptyMap<Int, Long>(), prefs.holidayOverrideEditTimes)

        holidays.retry()

        assertEquals(listOf<Long?>(null), stamps)
        assertEquals(listOf(4 to true), sent)
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
        val off = launch { holidays.send(3) }
        testScheduler.runCurrent()
        now = 4_000L
        prefs.holidayNotifyOverrides = setOf(3)
        val on = launch { holidays.send(3) }
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
        HolidayOverrideApply.apply(
            setOf(5), fetchedAtMs = now, signIn = 1, pending = holidays, calendar = calendar,
            runIfSignedIn = { _, block -> block(); true },
        )

        assertEquals(setOf(5), prefs.holidayNotifyOverrides)
        assertEquals(mapOf(5 to 3_000L), prefs.holidayOverrideEditTimes)
    }

    @Test
    fun `a choice that lands is let go at once`() = runTest {
        tap(3, notify = true)

        assertEquals(listOf(3 to true), sent)
        assertEquals(listOf(1), signIns)
        assertEquals(emptySet<Int>(), holidays.waitingIds)
    }

    @Test
    fun `with TigerSync off a choice goes nowhere and is not kept`() = runTest {
        syncs = false
        tap(3, notify = true)
        holidays.retry()

        assertEquals(emptyList<Pair<Int, Boolean>>(), sent)
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
        signInGeneration = 2
        prefs.holidayOverridesAwaitingUpload = setOf(4)
        gate.complete(Unit)
        first.join()
        second.join()

        assertEquals("4 never went up as the next account", listOf(3 to true), sent)
        assertEquals("nor was the next account's waiting set let go by 3's answer", setOf(4), holidays.waitingIds)
    }

    @Test
    fun `a sync snapshot keeps the choices still waiting as this phone has them`() = runTest {
        answer = { _, _ -> throw IOException("offline") }
        tap(3, notify = true)
        tap(5, notify = false)
        now += 1_000

        // The server knows 5 and 9 but not 3, and still has the 5 this phone took back.
        val merged = holidays.merged(snapshot = setOf(5, 9), fetchedAtMs = now, local = prefs.holidayNotifyOverrides)

        assertEquals(setOf(3, 9), merged)
    }

    @Test
    fun `a snapshot fetched before the last choice, or during a PUT, is not applied`() = runTest {
        tap(3, notify = true)
        assertNull("fetched before the tap", holidays.merged(emptySet(), fetchedAtMs = now - 1, local = setOf(3)))

        val gate = CompletableDeferred<Unit>()
        answer = { _, _ -> gate.await() }
        now += 1_000
        val tapping = launch { tap(4, notify = true) }
        testScheduler.runCurrent()
        now += 1_000
        assertNull("a PUT under way", holidays.merged(emptySet(), fetchedAtMs = now, local = setOf(3, 4)))

        gate.complete(Unit)
        tapping.join()
        now += 1_000
        assertEquals(setOf(3, 4), holidays.merged(setOf(3, 4), fetchedAtMs = now, local = setOf(3, 4)))
    }

    @Test
    fun `a PUT the client did not send is not taken for landed`() = runTest {
        delivered = false
        tap(3, notify = true)

        assertEquals(setOf(3), holidays.waitingIds)
    }

    @Test
    fun `an acknowledgment is written only while its sign-in holds, under the sign-out's lock`() = runTest {
        prefs.holidayOverridesAwaitingUpload = setOf(4)
        signOutAtTheWrite = true

        tap(3, notify = true)

        assertEquals("the departing account's 4 is not written back over the sign-out", emptySet<Int>(), holidays.waitingIds)
    }

    @Test
    fun `a snapshot fetched before a PUT landed is not applied`() = runTest {
        answer = { _, _ -> throw IOException("offline") }
        tap(3, notify = true)
        now += 1_000
        val fetchedAt = now
        now += 1_000
        answer = { _, _ -> }
        // Sent by the worker while Home's fetch was under way.
        holidays.retry()

        assertNull(holidays.merged(emptySet(), fetchedAtMs = fetchedAt, local = setOf(3)))
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
