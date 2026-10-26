package org.ntust.app.tigerduck.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.SemesterTerm
import java.time.LocalDate

/**
 * Whether a class reminder still goes out when its alarm fires: only for a
 * day classes meet, and only from that day's own term's timetable.
 */
class ClassReminderTermTest {

    private val twoTerms = AcademicCalendar(
        revision = 1L,
        terms = listOf(
            SemesterTerm("1142", LocalDate.of(2026, 2, 23), LocalDate.of(2026, 9, 11)),
            SemesterTerm("1151", LocalDate.of(2026, 9, 14), LocalDate.of(2027, 1, 15)),
        ),
        holidays = emptyList(),
    )
    private val firstMonday = LocalDate.of(2026, 9, 14)

    private fun stillDue(calendar: AcademicCalendar, day: LocalDate, armedTerm: String?) =
        ClassPreparingNotificationReceiver.stillDue(calendar, day, armedTerm)

    @Test
    fun `a reminder armed from the old term's timetable for the new term's first day is dropped`() {
        assertFalse(stillDue(twoTerms, firstMonday, armedTerm = "1142"))
    }

    @Test
    fun `a reminder armed from the day's own term goes out`() {
        assertTrue(stillDue(twoTerms, firstMonday, armedTerm = "1151"))
    }

    @Test
    fun `a day between terms gets no reminder`() {
        assertFalse(stillDue(twoTerms, LocalDate.of(2026, 9, 12), armedTerm = "1142"))
    }

    @Test
    fun `an alarm armed before reminders carried their term still goes out`() {
        assertTrue(stillDue(twoTerms, firstMonday, armedTerm = null))
    }

    @Test
    fun `with no calendar, nothing is dropped`() {
        assertTrue(stillDue(AcademicCalendar.EMPTY, firstMonday, armedTerm = "1142"))
    }
}
