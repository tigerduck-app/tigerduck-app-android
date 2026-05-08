package org.ntust.app.tigerduck.shared.clock

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppClockTest {

    @After
    fun tearDown() {
        AppClock.setOverride(null)
    }

    @Test
    fun `null override delegates to real clock`() {
        AppClock.setOverride(null)
        val before = System.currentTimeMillis()
        val now = AppClock.nowMillis()
        val after = System.currentTimeMillis()
        assertTrue("$now in [$before,$after]", now in before..after)
    }

    @Test
    fun `frozen override returns identical instant on repeated reads`() {
        val frozenInstant = 1_700_000_000_000L
        AppClock.setOverride(
            ClockOverride(
                instantMillis = frozenInstant,
                frozen = true,
                savedAtRealMillis = System.currentTimeMillis(),
            )
        )
        val a = AppClock.nowMillis()
        Thread.sleep(50)
        val b = AppClock.nowMillis()
        assertEquals(frozenInstant, a)
        assertEquals(frozenInstant, b)
    }

    @Test
    fun `ticking override advances proportionally`() {
        val savedAt = System.currentTimeMillis() - 1000L
        val baseInstant = 1_700_000_000_000L
        AppClock.setOverride(
            ClockOverride(
                instantMillis = baseInstant,
                frozen = false,
                savedAtRealMillis = savedAt,
            )
        )
        val now = AppClock.nowMillis()
        assertTrue(now >= baseInstant + 1000L)
        assertTrue(now < baseInstant + 5_000L)
    }

    @Test
    fun `realTimeFor identity when no override`() {
        AppClock.setOverride(null)
        val target = 1_800_000_000_000L
        assertEquals(target, AppClock.realTimeFor(target))
    }

    @Test
    fun `realTimeFor frozen translates by elapsed offset`() {
        val frozenInstant = 1_700_000_000_000L
        val savedAtReal = 1_650_000_000_000L
        AppClock.setOverride(
            ClockOverride(
                instantMillis = frozenInstant,
                frozen = true,
                savedAtRealMillis = savedAtReal,
            )
        )
        val realNowBefore = System.currentTimeMillis()
        val translated = AppClock.realTimeFor(frozenInstant + 60_000L)
        val realNowAfter = System.currentTimeMillis()
        assertTrue(translated >= realNowBefore + 60_000L)
        assertTrue(translated <= realNowAfter + 60_000L)
    }

    @Test
    fun `realTimeFor ticking applies constant offset`() {
        val baseInstant = 1_700_000_000_000L
        val savedAtReal = 1_650_000_000_000L
        AppClock.setOverride(
            ClockOverride(
                instantMillis = baseInstant,
                frozen = false,
                savedAtRealMillis = savedAtReal,
            )
        )
        val target = baseInstant + 60_000L
        val expectedReal = target - (baseInstant - savedAtReal)
        assertEquals(expectedReal, AppClock.realTimeFor(target))
    }

    @Test
    fun `localDateTime reflects frozen override in Taipei zone`() {
        val frozenInstant = java.time.ZonedDateTime.of(
            2026, 5, 4, 10, 20, 0, 0,
            java.time.ZoneId.of("Asia/Taipei"),
        ).toInstant().toEpochMilli()
        AppClock.setOverride(
            ClockOverride(frozenInstant, frozen = true, savedAtRealMillis = System.currentTimeMillis())
        )
        val ldt = AppClock.localDateTime(java.time.ZoneId.of("Asia/Taipei"))
        assertEquals(2026, ldt.year)
        assertEquals(5, ldt.monthValue)
        assertEquals(4, ldt.dayOfMonth)
        assertEquals(10, ldt.hour)
        assertEquals(20, ldt.minute)
    }
}
