package org.ntust.app.tigerduck.ui.screen.emptyclassroom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.Holiday
import org.ntust.app.tigerduck.academic.SemesterTerm
import org.ntust.app.tigerduck.emptyclassroom.DayOff
import org.ntust.app.tigerduck.emptyclassroom.EmptyClassroomSchedule
import java.time.LocalDate

class FinderCalendarTest {
    private val calendar = AcademicCalendar(
        revision = 1,
        terms = listOf(SemesterTerm("1151", LocalDate.of(2026, 9, 14), LocalDate.of(2027, 1, 15))),
        holidays = listOf(Holiday(1, "國慶日", "National Day", LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 9))),
    )
    private val betweenTerms = LocalDate.of(2027, 1, 20)
    private val holiday = LocalDate.of(2026, 10, 9)

    @Test
    fun `a signed-in account reads the school's calendar`() {
        val read = finderCalendar(isDemo = false) { calendar }
        assertEquals(DayOff.OutOfTerm, EmptyClassroomSchedule.dayOff(betweenTerms, read))
        assertEquals(DayOff.HolidayOff(calendar.holidays.single()), EmptyClassroomSchedule.dayOff(holiday, read))
    }

    @Test
    fun `the demo account sees rooms on every weekday, between terms and on holidays too`() {
        val read = finderCalendar(isDemo = true) { calendar }
        assertNull(EmptyClassroomSchedule.dayOff(betweenTerms, read))
        assertNull(EmptyClassroomSchedule.dayOff(holiday, read))
        assertEquals(DayOff.Weekend, EmptyClassroomSchedule.dayOff(LocalDate.of(2027, 1, 23), read))
    }
}
