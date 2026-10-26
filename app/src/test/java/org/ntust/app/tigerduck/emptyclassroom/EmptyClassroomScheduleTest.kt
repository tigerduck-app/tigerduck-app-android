package org.ntust.app.tigerduck.emptyclassroom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.Holiday
import org.ntust.app.tigerduck.academic.SemesterTerm
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Where the finder opens: the period in progress, the next one in a break, and the next teaching
 * day after the last period, at a weekend, on a holiday or outside the term.
 */
class EmptyClassroomScheduleTest {
    private fun time(month: Int, day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, month, day, hour, minute)
    private fun day(month: Int, day: Int) = LocalDate.of(2026, month, day)

    /** 2026-10-26, a Monday, is a holiday; the term runs to 2027-01-15. */
    private val calendar = AcademicCalendar(
        revision = 1,
        terms = listOf(SemesterTerm("1151", LocalDate.of(2026, 9, 14), LocalDate.of(2027, 1, 15))),
        holidays = listOf(Holiday(1, "假日", "Holiday", day(10, 26), day(10, 26))),
    )

    private fun opens(now: LocalDateTime, onDay: LocalDate, period: Int) = assertEquals(
        onDay to period,
        EmptyClassroomSchedule.opening(now, 13) { EmptyClassroomSchedule.isTeachingDay(it, calendar) },
    )

    @Test
    fun `during a period it opens on that period, and in a break on the next`() {
        opens(time(10, 14, 10, 30), day(10, 14), 2)
        opens(time(10, 14, 10, 5), day(10, 14), 2)
        opens(time(10, 14, 10), day(10, 14), 2)
        opens(time(10, 14, 7), day(10, 14), 0)
        opens(time(10, 14, 21, 30), day(10, 14), 13)
    }

    @Test
    fun `after period D it opens on the next day's first period`() {
        opens(time(10, 14, 22), day(10, 15), 0)
    }

    @Test
    fun `from Friday night it skips the weekend to Monday`() {
        opens(time(10, 16, 23), day(10, 19), 0)
        opens(time(10, 17, 10), day(10, 19), 0)
        opens(time(10, 18, 23), day(10, 19), 0)
    }

    @Test
    fun `a holiday is passed over like a weekend`() {
        opens(time(10, 23, 22, 30), day(10, 27), 0)
        opens(time(10, 26, 10), day(10, 27), 0)
    }

    @Test
    fun `with no teaching day ahead it stays on today`() {
        assertEquals(day(10, 17) to 2, EmptyClassroomSchedule.opening(time(10, 17, 10), 13) { false })
        assertNull(EmptyClassroomSchedule.nextTeachingDay(day(10, 17), 13) { false })
    }

    @Test
    fun `the periods that have ended, and the one in progress or next`() {
        assertEquals(0, EmptyClassroomSchedule.endedPeriods(time(10, 14, 7)))
        assertEquals(2, EmptyClassroomSchedule.endedPeriods(time(10, 14, 10, 5)))
        assertEquals(14, EmptyClassroomSchedule.endedPeriods(time(10, 14, 22, 30)))
        assertEquals(0, EmptyClassroomSchedule.upcomingPeriod(time(10, 14, 7)))
        assertEquals(2, EmptyClassroomSchedule.upcomingPeriod(time(10, 14, 10, 45)))
        assertNull(EmptyClassroomSchedule.upcomingPeriod(time(10, 14, 22, 30)))
        assertEquals("10:20–11:10", EmptyClassroomSchedule.timeRangeText(2))
        assertEquals(10, EmptyClassroomSchedule.periodIndex("A"))
        assertNull(EmptyClassroomSchedule.periodIndex("Z"))
    }

    @Test
    fun `a weekday in the term without a holiday is a teaching day, and every other day says why not`() {
        assertNull(EmptyClassroomSchedule.dayOff(day(10, 14), calendar))
        assertEquals(DayOff.Weekend, EmptyClassroomSchedule.dayOff(day(10, 17), calendar))
        assertEquals(DayOff.Weekend, EmptyClassroomSchedule.dayOff(day(10, 18), calendar))
        assertEquals("Holiday", (EmptyClassroomSchedule.dayOff(day(10, 26), calendar) as DayOff.HolidayOff).holiday.nameEn)
        // The site projects the weekly timetable onto the weeks between terms (tigerduck-app#478).
        assertEquals(DayOff.OutOfTerm, EmptyClassroomSchedule.dayOff(LocalDate.of(2027, 1, 20), calendar))
        assertEquals(DayOff.OutOfTerm, EmptyClassroomSchedule.dayOff(day(9, 11), calendar))
        // An unknown calendar fails open, as the rest of the app's does.
        assertNull(EmptyClassroomSchedule.dayOff(LocalDate.of(2027, 1, 20), AcademicCalendar.EMPTY))
    }

    @Test
    fun `the next teaching day skips the break after the term`() {
        val winter = calendar.copy(
            terms = calendar.terms + SemesterTerm("1152", LocalDate.of(2027, 2, 22), LocalDate.of(2027, 6, 18)),
        )
        assertNull(EmptyClassroomSchedule.nextTeachingDay(LocalDate.of(2027, 1, 15), 13) { EmptyClassroomSchedule.isTeachingDay(it, winter) })
        assertEquals(
            LocalDate.of(2027, 2, 22),
            EmptyClassroomSchedule.nextTeachingDay(LocalDate.of(2027, 2, 19), 13) { EmptyClassroomSchedule.isTeachingDay(it, winter) },
        )
    }
}
