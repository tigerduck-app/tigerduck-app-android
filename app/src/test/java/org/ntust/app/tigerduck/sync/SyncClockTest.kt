package org.ntust.app.tigerduck.sync

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `a refusal stamps again one tick past the backend's clock, and keeps the last value issued`() {
        val clock = clock()
        now = T + MINUTE
        assertEquals(T + MINUTE + 1, clock.stampMs())

        // 409 clock_ahead, its answer carrying the backend's clock.
        assertEquals(T + 1, clock.restampAfterClockAhead(us(T)))
        // The next edit stamps after the last value issued, until the cap passes it.
        assertEquals(T + MINUTE + 2, clock.stampMs())
        now += MINUTE
        assertEquals(T + MINUTE + 5_000 + 1, clock.stampMs())
    }

    /**
     * Agreed with the iOS lead and the owner (Telegram 1638, 1651): the restamp is the clock the 409
     * carries plus one tick, a millisecond here, and the last value issued is never lowered for it,
     * so a still wall clock cannot issue one stamp twice (`tigersync-clock`, "A device re-stamps an
     * edit refused as ahead").
     */
    @Test
    fun `two refusals with a still wall clock give two different, increasing stamps, each under the refusal's clock plus five seconds`() {
        val clock = clock()
        now = T + MINUTE
        val offline = clock.stampMs()

        // Two edits refused as ahead, each answered with the backend's clock, which has not moved.
        val first = clock.restampAfterClockAhead(us(T) + 456)
        val between = clock.stampMs()
        val second = clock.restampAfterClockAhead(us(T) + 456)

        assertTrue("two refusals, two stamps: $first, $second", second > first)
        assertEquals("one tick past the refusal's clock", T + 1, first)
        assertTrue("under the refusal's clock plus five seconds: $second", second < T + 5_000)
        assertEquals("an edit between them stamps after the last value issued", offline + 1, between)
        assertEquals("the last value issued is never lowered", offline + 1, SyncClockStore(file).load().lastIssuedMs)
    }

    /**
     * Codex on PR P (P2 1): the backend's 409 clock is its wall clock, and it took an earlier edit
     * of this device's stamped up to five seconds past it, which its answer gave back and the clock
     * heard (`WriteClock.answer`). A restamp one tick past the refusal's clock fell below that stamp
     * and lost to it (last writer wins: ties and older lose), so the student's newer value reverted.
     * It stays above it, and within the refusal's clock plus five seconds, after a relaunch too.
     */
    @Test
    fun `a restamp stays above this device's own earlier edit the backend took`() {
        val clock = clock()
        clock.heard(us(T))
        now = T + 3_999
        assertEquals("under the cap, so the backend takes it", T + 4_000, clock.stampMs())
        // The edit's answer gives back the stamp it kept.
        clock.heardInSnapshot(us(T + 4_000))
        // The wall clock jumps, and the next edit, far ahead, is refused at the backend's T + 1 s.
        now = T + 10 * MINUTE
        clock.stampMs()

        val restamp = clock.restampAfterClockAhead(us(T + 1_000))
        val afterRelaunch = clock().restampAfterClockAhead(us(T + 1_000))

        assertTrue("above the edit the backend took: $restamp", restamp > T + 4_000)
        assertTrue("within the refusal's clock plus five seconds: $restamp", restamp <= T + 1_000 + 5_000)
        assertTrue("after a relaunch, above it still: $afterRelaunch", afterRelaunch in T + 4_001..T + 1_000 + 5_000)
    }

    /**
     * Codex on PR P (round 2): the backend reported a clock an hour ahead once, which the clock kept
     * as seen, and later refuses an edit at its right time. No stamp past that hour can be taken
     * now, so it is no floor of the restamp: one past it would be refused at every try. The restamp
     * goes within the refusal's clock plus five seconds.
     */
    @Test
    fun `a far-ahead clock the backend reported once, then corrected, holds no restamp past its time`() {
        val clock = clock()
        clock.heard(us(T + 60 * MINUTE))
        now = T + 60 * MINUTE

        val restamp = clock.restampAfterClockAhead(us(T))

        assertTrue("within the refusal's clock plus five seconds: $restamp", restamp in T + 1..T + 5_000)
    }

    /**
     * At the edge itself: a stamp seen at the refusal's clock plus five seconds may be one the
     * backend stored as the refusal came. The restamp still goes past it, a millisecond over the
     * allowance, refused once and sent again at the next sync, rather than at or below it, where it
     * would be taken and lose.
     */
    @Test
    fun `a restamp goes past a stamp seen at the refusal's clock plus five seconds, the edge itself`() {
        val clock = clock()
        clock.heardInSnapshot(us(T + 5_000))

        assertEquals(T + 5_001, clock.restampAfterClockAhead(us(T)))
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

    /**
     * PR B's parked window: the values heard from one backend and the stamps issued under it say
     * nothing of another's, and a backend whose clock ran far ahead would hold every stamp up on the
     * next. A move of the endpoint to another backend ([org.ntust.app.tigerduck.network.backend.ApiBase.backendKey])
     * forgets them; a sign-out does not ([SyncClockAcrossSignOutTest]), nor does a move between the
     * API versions of one backend.
     */
    @Test
    fun `the endpoint moved to another backend forgets what the clock heard and issued`() {
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "https://one.example.test/v3" }
        val clock = SyncClock(SyncClockStore(file), prefs)
        val ahead = System.currentTimeMillis() + 60 * MINUTE
        clock.heard(us(ahead))
        clock.heardInSnapshot(us(ahead))
        assertEquals(ahead + 1, clock.stampMs())
        prefs.announcementApiBaseUrlOverride = "https://one.example.test/v4"
        assertEquals("the same backend at another version keeps them", ahead + 2, clock.stampMs())

        prefs.announcementApiBaseUrlOverride = "https://two.example.test/v4"
        val before = System.currentTimeMillis()
        val stamp = clock.stampMs()

        assertTrue("stamped by the wall clock, uncapped: $stamp", stamp in before + 1..System.currentTimeMillis() + 1)
        assertEquals(stamp, SyncClockStore(file).load().lastIssuedMs)
        assertEquals("nothing heard of the new backend", null, SyncClockStore(file).load().heardUs)
    }

    @Test
    fun `values a build before the backend's key kept are the endpoint's backend's`() {
        val ahead = System.currentTimeMillis() + 60 * MINUTE
        file.edit().putLong("lastIssuedMs", ahead).putLong("lastSeenUs", us(ahead)).commit()
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "https://one.example.test/v4" }

        assertEquals(ahead + 1, SyncClock(SyncClockStore(file), prefs).stampMs())
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
