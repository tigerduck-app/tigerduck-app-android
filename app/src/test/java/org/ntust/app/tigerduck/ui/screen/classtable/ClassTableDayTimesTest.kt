package org.ntust.app.tigerduck.ui.screen.classtable

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import java.time.LocalDateTime

/**
 * What the class table's clock says and when it says it again. The view model keeps the latest
 * of these in `currentMinute`, which the "now" card and the cells' ongoing mark read; a class
 * must show as ongoing within one tick of its start minute.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClassTableDayTimesTest {

    @After
    fun releaseClock() {
        AppClock.setOverride(null)
    }

    /** A Thursday, on a made-up date: 2026-11-05 11:19:59 and so on, Taipei time. */
    private fun taipei(time: String): Long =
        LocalDateTime.parse("2026-11-05T$time").atZone(AppConstants.TAIPEI_ZONE).toInstant().toEpochMilli()

    private fun minutes(h: Int, m: Int) = h * 60 + m

    @Test
    fun `a running clock moves the minute at the boundary, not seconds after it`() = runTest(UnconfinedTestDispatcher()) {
        AppClock.setOverride(ClockOverride(taipei("11:19:59"), frozen = false, savedAtRealMillis = System.currentTimeMillis()))
        val seen = mutableListOf<ClassTableSelection.DayTime>()
        val job = launch { ClassTableSelection.dayTimes().toList(seen) }
        assertEquals(listOf(minutes(11, 19)), seen.map { it.minuteOfDay })

        // The real second the clock needs to cross the boundary; the scheduler's time is virtual.
        Thread.sleep(1_100)
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()

        assertEquals(listOf(minutes(11, 19), minutes(11, 20)), seen.map { it.minuteOfDay })
        assertEquals(AppConstants.weekdayIndex(java.util.Calendar.THURSDAY), seen.last().weekday)
        job.cancel()
    }

    @Test
    fun `a frozen clock says nothing more until it is set again`() = runTest(UnconfinedTestDispatcher()) {
        AppClock.setOverride(ClockOverride(taipei("11:19:59"), frozen = true, savedAtRealMillis = 0L))
        val seen = mutableListOf<ClassTableSelection.DayTime>()
        val job = launch { ClassTableSelection.dayTimes().toList(seen) }
        testScheduler.advanceTimeBy(10 * 60_000L)
        testScheduler.runCurrent()
        assertEquals(listOf(minutes(11, 19)), seen.map { it.minuteOfDay })

        AppClock.setOverride(ClockOverride(taipei("11:20:00"), frozen = true, savedAtRealMillis = 0L))
        assertEquals(listOf(minutes(11, 19), minutes(11, 20)), seen.map { it.minuteOfDay })
        job.cancel()
    }
}
