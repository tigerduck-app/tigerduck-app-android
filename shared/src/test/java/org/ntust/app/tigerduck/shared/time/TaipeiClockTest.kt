package org.ntust.app.tigerduck.shared.time

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class TaipeiClockTest {

    private lateinit var savedDefault: TimeZone

    @Before
    fun setUp() {
        savedDefault = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
    }

    @After
    fun tearDown() {
        AppClock.setOverride(null)
        TimeZone.setDefault(savedDefault)
    }

    private fun freeze(iso: String) {
        AppClock.setOverride(
            ClockOverride(Instant.parse(iso).toEpochMilli(), frozen = true, savedAtRealMillis = 0L),
        )
    }

    @Test
    fun `zone is Asia Taipei`() {
        assertEquals("Asia/Taipei", TaipeiClock.zone.id)
        assertEquals("Asia/Taipei", TaipeiClock.timeZone.id)
    }

    @Test
    fun `today and minuteOfDay use Taipei whatever the phone zone is`() {
        // 17:30 UTC on Oct 31 is 01:30 on Nov 1 in Taipei, and still Oct 31 in Los Angeles.
        freeze("2026-10-31T17:30:00Z")
        assertEquals(LocalDate.of(2026, 11, 1), TaipeiClock.today())
        assertEquals(90, TaipeiClock.minuteOfDay())
        assertEquals(ZonedDateTime.parse("2026-11-01T01:30:00+08:00[Asia/Taipei]"), TaipeiClock.now())
    }

    @Test
    fun `minuteOfDay of an explicit instant`() {
        assertEquals(9 * 60, TaipeiClock.minuteOfDay(Instant.parse("2026-11-01T01:00:30Z")))
    }

    @Test
    fun `minuteTicks emits now, then again when the override moves forward`() = runTest(UnconfinedTestDispatcher()) {
        freeze("2026-10-31T17:30:10Z")
        val seen = mutableListOf<ZonedDateTime>()
        val job = launch { TaipeiClock.minuteTicks().toList(seen) }
        assertEquals(1, seen.size)
        assertEquals(90, seen[0].hour * 60 + seen[0].minute)

        freeze("2026-10-31T17:31:11Z")
        assertEquals(2, seen.size)
        assertEquals(91, seen[1].hour * 60 + seen[1].minute)
        job.cancel()
    }
}
