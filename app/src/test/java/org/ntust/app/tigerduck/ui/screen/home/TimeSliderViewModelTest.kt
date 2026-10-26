package org.ntust.app.tigerduck.ui.screen.home

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import java.time.LocalDate

/**
 * Covers which days the time slider leaves off. The clock is frozen on a
 * Monday morning so "today" cannot roll over while a test runs.
 */
class TimeSliderViewModelTest {

    private val today = LocalDate.parse("2026-09-28")
    private val everyDay = Course.fromSchedule(
        "CS101", "Every day", schedule = (1..7).associateWith { listOf("3", "4") },
    )

    @Before
    fun freezeClock() {
        val nineAm = today.atTime(9, 0).atZone(AppConstants.TAIPEI_ZONE).toInstant().toEpochMilli()
        AppClock.setOverride(ClockOverride(nineAm, frozen = true, savedAtRealMillis = 0L))
    }

    @After
    fun releaseClock() {
        AppClock.setOverride(null)
    }

    @Test
    fun `a quiet day's classes are left off while the days around it keep theirs`() {
        val schoolDay = TimeSliderViewModel().apply { configure(listOf(everyDay)) }
        val holiday = TimeSliderViewModel().apply { configure(listOf(everyDay)) { it == today } }

        assertTrue(schoolDay.timeSlots.any { it.localDate == today })
        assertEquals(
            schoolDay.timeSlots.filterNot { it.localDate == today }.map { it.id },
            holiday.timeSlots.map { it.id },
        )
    }

    @Test
    fun `on a quiet day the slider points at the classes either side of it`() {
        val holiday = TimeSliderViewModel().apply { configure(listOf(everyDay)) { it == today } }

        val state = holiday.currentCourseState as CourseState.Between
        assertEquals(today.minusDays(1), state.previous?.localDate)
        assertEquals(today.plusDays(1), state.next?.localDate)
    }
}
