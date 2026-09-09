package org.ntust.app.tigerduck.academic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.model.AcademicCalendarDto
import org.ntust.app.tigerduck.network.model.HolidayDto
import org.ntust.app.tigerduck.network.model.SemesterTermDto
import java.time.LocalDate

class AcademicCalendarTest {

    private fun date(text: String) = LocalDate.parse(text)

    private val calendar = AcademicCalendar(
        revision = 1L,
        terms = listOf(
            SemesterTerm("1151", date("2026-09-07"), date("2026-12-25")),
            SemesterTerm("1152", date("2027-02-15"), date("2027-06-11")),
        ),
        holidays = listOf(
            Holiday(1, "中秋節", "Mid-Autumn", date("2026-09-25"), date("2026-09-27")),
            Holiday(2, "元旦", "New Year", date("2027-01-01"), date("2027-01-01")),
        ),
    )

    // --- suppression ---------------------------------------------------

    @Test
    fun `an ordinary class day is not suppressed`() {
        assertFalse(calendar.suppressesClasses(date("2026-10-05"), emptySet()))
    }

    @Test
    fun `every day of a multi-day holiday is suppressed`() {
        for (day in listOf("2026-09-25", "2026-09-26", "2026-09-27")) {
            assertTrue(day, calendar.suppressesClasses(date(day), emptySet()))
        }
    }

    @Test
    fun `the day after a holiday ends is not suppressed`() {
        assertFalse(calendar.suppressesClasses(date("2026-09-28"), emptySet()))
    }

    @Test
    fun `a single-day holiday covers exactly that day`() {
        assertTrue(calendar.suppressesClasses(date("2027-01-01"), emptySet()))
        assertFalse(calendar.suppressesClasses(date("2026-12-31"), emptySet()))
        assertFalse(calendar.suppressesClasses(date("2027-01-02"), emptySet()))
    }

    @Test
    fun `opting in to a holiday un-suppresses its days`() {
        assertFalse(calendar.suppressesClasses(date("2026-09-26"), setOf(1)))
    }

    @Test
    fun `opting in to one holiday leaves the others suppressed`() {
        assertTrue(calendar.suppressesClasses(date("2027-01-01"), setOf(1)))
    }

    @Test
    fun `opting in to either of two overlapping holidays is enough`() {
        // The user said "I have class that day"; a second holiday they never
        // saw should not overrule that.
        val overlapping = calendar.copy(
            holidays = calendar.holidays + Holiday(
                3, "校慶", "Anniversary", date("2026-09-26"), date("2026-09-26")
            )
        )
        assertTrue(overlapping.suppressesClasses(date("2026-09-26"), emptySet()))
        assertFalse(overlapping.suppressesClasses(date("2026-09-26"), setOf(3)))
        assertFalse(overlapping.suppressesClasses(date("2026-09-26"), setOf(1)))
    }

    @Test
    fun `an empty calendar suppresses nothing`() {
        // Failing open: a device that has never reached the backend must
        // behave exactly as it did before this feature existed.
        assertFalse(AcademicCalendar.EMPTY.suppressesClasses(date("2026-09-26"), emptySet()))
    }

    // --- current term --------------------------------------------------

    @Test
    fun `the term containing today is current`() {
        assertEquals("1151", calendar.currentTerm(date("2026-10-05"))?.code)
    }

    @Test
    fun `between terms the most recently begun one stays current`() {
        // The app has always kept showing the last timetable over a break;
        // suppression is the holidays' job, not the term boundary's.
        assertEquals("1151", calendar.currentTerm(date("2027-01-20"))?.code)
    }

    @Test
    fun `before every published term the earliest is used`() {
        assertEquals("1151", calendar.currentTerm(date("2026-08-01"))?.code)
    }

    @Test
    fun `an empty calendar has no current term`() {
        assertNull(AcademicCalendar.EMPTY.currentTerm(date("2026-10-05")))
    }

    // --- in session ----------------------------------------------------

    @Test
    fun `in session inside a term and not between them`() {
        assertTrue(calendar.isInSession(date("2026-10-05")))
        assertFalse(calendar.isInSession(date("2027-01-20")))
    }

    @Test
    fun `term boundaries are inclusive`() {
        assertTrue(calendar.isInSession(date("2026-09-07")))
        assertTrue(calendar.isInSession(date("2026-12-25")))
        assertFalse(calendar.isInSession(date("2026-12-26")))
    }

    @Test
    fun `an unknown calendar reads as in session`() {
        assertTrue(AcademicCalendar.EMPTY.isInSession(date("2027-01-20")))
    }

    // --- parsing -------------------------------------------------------

    @Test
    fun `parses a well-formed payload`() {
        val parsed = AcademicCalendar.from(
            AcademicCalendarDto(
                revision = 42L,
                semesters = listOf(SemesterTermDto("1151", "2026-09-07", "2026-12-25", "", "")),
                holidays = listOf(HolidayDto(7, "中秋節", "Mid-Autumn", "2026-09-25", "2026-09-27")),
            )
        )
        assertEquals(42L, parsed.revision)
        assertEquals(1, parsed.terms.size)
        assertEquals(7, parsed.holidays.single().id)
    }

    @Test
    fun `a malformed row is dropped without losing the rest`() {
        // One bad holiday should cost that holiday, not the whole calendar —
        // losing it all would silently turn suppression off everywhere.
        val parsed = AcademicCalendar.from(
            AcademicCalendarDto(
                revision = 1L,
                semesters = listOf(
                    SemesterTermDto("1151", "2026-09-07", "2026-12-25", null, null),
                    SemesterTermDto("bad", "not-a-date", "2026-12-25", null, null),
                ),
                holidays = listOf(
                    HolidayDto(1, "中秋節", "Mid-Autumn", "2026-09-25", "2026-09-27"),
                    HolidayDto(2, null, null, "2026-10-10", "2026-10-10"),
                    HolidayDto(3, "反向", "Backwards", "2026-11-05", "2026-11-01"),
                ),
            )
        )
        assertEquals(listOf("1151"), parsed.terms.map { it.code })
        assertEquals(listOf(1), parsed.holidays.map { it.id })
    }

    @Test
    fun `a null payload is the empty calendar`() {
        assertEquals(AcademicCalendar.EMPTY, AcademicCalendar.from(null))
    }

    @Test
    fun `a holiday with only one language uses it for both`() {
        val parsed = AcademicCalendar.from(
            AcademicCalendarDto(
                revision = 1L,
                semesters = null,
                holidays = listOf(HolidayDto(1, "校慶", null, "2026-11-01", "2026-11-01")),
            )
        )
        val holiday = parsed.holidays.single()
        assertEquals("校慶", holiday.name("zh-Hant-TW"))
        assertEquals("校慶", holiday.name("en-US"))
    }

    @Test
    fun `holiday name follows the language tag`() {
        val holiday = calendar.holidays.first()
        assertEquals("中秋節", holiday.name("zh-Hant-TW"))
        assertEquals("Mid-Autumn", holiday.name("en-US"))
        assertEquals("Mid-Autumn", holiday.name("ja-JP"))
    }
}
