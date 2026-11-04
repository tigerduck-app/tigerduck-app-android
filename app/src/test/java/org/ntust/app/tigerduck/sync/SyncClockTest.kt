package org.ntust.app.tigerduck.sync

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.util.JvmContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * `tigersync-clock`'s scenarios, in Android's ticks of one millisecond: a stamp follows the device
 * clock, never falls below the last backend value seen or the last stamp issued, and is capped at
 * the backend's time plus five seconds once the backend has been heard (Review Focus 3).
 */
class SyncClockTest {

    private val context = JvmContext()
    private val file = context.getSharedPreferences("tigerduck_sync_clock", Context.MODE_PRIVATE)
    private var now = T

    /** The clock as a launch makes it, over the file the last launch left. */
    private fun clock() = SyncClock(SyncClockStore(file), { now })

    private fun us(ms: Long) = ms * 1_000

    @Test
    fun `a device behind the backend stamps the last value seen plus one tick`() {
        val clock = clock()
        clock.heard(us(T + 5 * MINUTE))

        assertEquals(T + 5 * MINUTE + 1, clock.stampMs())
    }

    @Test
    fun `a value seen part way into a millisecond is never stamped below`() {
        val clock = clock()
        clock.heardInSnapshot(us(T + 5 * MINUTE) + 456)

        // The next whole ms at or past what was seen, plus one tick.
        assertEquals(T + 5 * MINUTE + 2, clock.stampMs())
    }

    @Test
    fun `two edits in one millisecond differ`() {
        val clock = clock()

        val first = clock.stampMs()
        val second = clock.stampMs()

        assertEquals(T + 1, first)
        assertEquals(first + 1, second)
    }

    @Test
    fun `a clock a minute ahead that has heard the backend is capped at its time plus five seconds`() {
        val clock = clock()
        now = T + MINUTE
        clock.heard(us(T))

        assertEquals(T + 5_000 + 1, clock.stampMs())
        // The cap follows the device time since the header came.
        now += 10_000
        assertEquals(T + 10_000 + 5_000 + 1, clock.stampMs())
    }

    @Test
    fun `a clock a minute ahead that has heard nothing stamps uncapped`() {
        now = T + MINUTE

        assertEquals(T + MINUTE + 1, clock().stampMs())
    }

    @Test
    fun `a snapshot's values raise the floor but set no cap`() {
        val clock = clock()
        now = T + MINUTE
        clock.heardInSnapshot(us(T))

        assertEquals(T + MINUTE + 1, clock.stampMs())
    }

    @Test
    fun `a refusal stamps again at the backend's time plus five seconds, and caps the next edit`() {
        val clock = clock()
        now = T + MINUTE
        assertEquals(T + MINUTE + 1, clock.stampMs())

        // 409 clock_ahead, its answer carrying the backend's clock.
        assertEquals(T + 5_000 + 1, clock.restampAfterClockAhead(us(T)))
        assertEquals(T + 5_000 + 2, clock.stampMs())
    }

    @Test
    fun `a refusal with no clock in its answer stamps after the last one issued`() {
        val clock = clock()
        val first = clock.stampMs()

        assertEquals(first + 1, clock.restampAfterClockAhead(null))
    }

    @Test
    fun `a wall clock set back after hearing never stamps a value twice`() {
        val clock = clock()
        clock.heard(us(T))
        val first = clock.stampMs()
        now = T - MINUTE

        // No time has passed since the header, as far as the cap can tell, and nothing goes below it.
        assertEquals(first + 1, clock.restampAfterClockAhead(null))
    }

    @Test
    fun `edits made at once on many threads never share a stamp`() {
        val clock = clock()
        val stamps = ConcurrentHashMap.newKeySet<Long>()
        val start = CountDownLatch(1)
        val threads = (1..8).map {
            thread {
                start.await()
                repeat(500) { stamps += clock.stampMs() }
            }
        }
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(8 * 500, stamps.size)
        assertEquals(T + 8 * 500, SyncClockStore(file).load().lastIssuedMs)
    }

    @Test
    fun `a relaunch with a slow clock stamps after the last value issued`() {
        val issued = clock().stampMs()
        now = T - 60 * MINUTE

        assertEquals(issued + 1, clock().stampMs())
    }

    @Test
    fun `a relaunch keeps what was heard, cap included`() {
        now = T + MINUTE
        clock().heard(us(T))

        assertEquals(T + 5_000 + 1, clock().stampMs())
    }

    @Test
    fun `the last value seen never lowers`() {
        val clock = clock()
        clock.heard(us(T + 5 * MINUTE))
        clock.heard(us(T))
        clock.heardInSnapshot(us(T))

        assertEquals(us(T + 5 * MINUTE), SyncClockStore(file).load().lastSeenUs)
        assertEquals(T + 5 * MINUTE + 1, clock.stampMs())
    }

    @Test
    fun `the app's clock stamps by the rule, whatever API the endpoint setting names`() {
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "https://api.example.test/v3" }
        val clock = SyncClock(SyncClockStore(file), prefs)
        val ahead = System.currentTimeMillis() + 60 * MINUTE
        clock.heardInSnapshot(us(ahead))

        assertEquals(ahead + 1, clock.stampMs())
        prefs.announcementApiBaseUrlOverride = "https://api.example.test/v4"
        assertEquals(ahead + 2, clock.stampMs())
    }

    @Test
    fun `a clock value outside any plausible time is not heard`() {
        val clock = clock()
        // Before 2020, past 2100, and a value whose stamps would overflow once sent as microseconds.
        listOf(1_000L, -5L, 4_200_000_000_000_000L, Long.MAX_VALUE).forEach { value ->
            clock.heard(value)
            clock.heardInSnapshot(value)
        }

        assertEquals(SyncClockState(), SyncClockStore(file).load())
        assertEquals(T + 1, clock.stampMs())
        assertEquals(T + 2, clock.restampAfterClockAhead(Long.MAX_VALUE))
    }

    @Test
    fun `a stored value outside any plausible time reads as nothing`() {
        file.edit().putLong("lastSeenUs", Long.MAX_VALUE).putLong("heardUs", Long.MAX_VALUE)
            .putLong("lastIssuedMs", Long.MAX_VALUE).putLong("heardAtMs", T).commit()

        assertEquals(SyncClockState(heardAtMs = T), SyncClockStore(file).load())
        assertEquals(T + 1, clock().stampMs())
    }

    @Test
    fun `nothing stored reads as nothing heard or issued`() {
        assertEquals(SyncClockState(), SyncClockStore(file).load())
        assertEquals(null, SyncClockState().capMs(T))
    }

    private companion object {
        // 2027-01-15T08:00:00Z.
        const val T = 1_800_000_000_000L
        const val MINUTE = 60_000L
    }
}
