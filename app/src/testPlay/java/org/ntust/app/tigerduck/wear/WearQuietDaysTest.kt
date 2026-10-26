package org.ntust.app.tigerduck.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.Holiday
import org.ntust.app.tigerduck.academic.SemesterTerm
import org.ntust.app.tigerduck.shared.WearProtocol
import java.time.LocalDate

class WearQuietDaysTest {

    private fun date(text: String) = LocalDate.parse(text)

    private fun days(from: LocalDate, through: LocalDate): Set<LocalDate> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(through) }.toSet()

    private val today = date("2026-10-05")

    // One term past the horizon, so the holidays are the only quiet days.
    private val wholeTerm = SemesterTerm("1151", date("2026-09-01"), date("2027-12-31"))

    private fun calendarWith(vararg holidays: Holiday) =
        AcademicCalendar(revision = 1L, terms = listOf(wholeTerm), holidays = holidays.toList())

    @Test
    fun `the watch reads back the quiet days the phone sends`() {
        val calendar = calendarWith(
            Holiday(1, "National Day", "National Day", date("2026-10-09"), date("2026-10-10")),
            Holiday(2, "Anniversary", "Anniversary", date("2026-11-14"), date("2026-11-14")),
        )
        val sent = wearQuietDays(calendar, optedInHolidayIds = setOf(2), today = today)
        assertEquals(setOf(date("2026-10-09"), date("2026-10-10")), WearProtocol.Schedule.decodeQuietDays(sent))
    }

    @Test
    fun `a holiday that is over is not sent`() {
        val calendar = calendarWith(
            Holiday(1, "Mid-Autumn", "Mid-Autumn", date("2026-09-25"), date("2026-09-27")),
        )
        assertTrue(wearQuietDays(calendar, emptySet(), today).isEmpty())
    }

    @Test
    fun `a holiday running past the horizon is cut at it`() {
        val calendar = calendarWith(
            Holiday(1, "Typo", "Typo", date("2026-10-01"), date("2036-10-01")),
        )
        val sent = wearQuietDays(calendar, emptySet(), today)
        assertEquals(WEAR_QUIET_DAYS_AHEAD.toInt() + 1, sent.size)
        assertEquals(today.toString(), sent.first())
        assertEquals(today.plusDays(WEAR_QUIET_DAYS_AHEAD).toString(), sent.last())
    }

    @Test
    fun `days outside the term are sent up to the horizon`() {
        val calendar = AcademicCalendar(
            revision = 1L,
            terms = listOf(
                SemesterTerm("1151", date("2026-09-07"), date("2026-12-25")),
                SemesterTerm("1152", date("2027-02-15"), date("2027-06-11")),
            ),
            holidays = emptyList(),
        )
        // Between the terms, then from the end of the last published one on.
        val expected = days(date("2026-12-26"), date("2027-02-14")) +
            days(date("2027-06-12"), today.plusDays(WEAR_QUIET_DAYS_AHEAD))
        assertEquals(
            expected,
            WearProtocol.Schedule.decodeQuietDays(wearQuietDays(calendar, emptySet(), today)),
        )
    }

    @Test
    fun `a calendar with no terms sends only its holidays`() {
        // No term known reads as in session, as on the phone, so no day is
        // sent for being outside one.
        val calendar = AcademicCalendar(
            revision = 1L,
            terms = emptyList(),
            holidays = listOf(
                Holiday(1, "National Day", "National Day", date("2026-10-09"), date("2026-10-10")),
            ),
        )
        assertEquals(listOf("2026-10-09", "2026-10-10"), wearQuietDays(calendar, emptySet(), today))
    }

    @Test
    fun `a phone that has no calendar yet sends no quiet days`() {
        assertTrue(wearQuietDays(AcademicCalendar.EMPTY, emptySet(), today).isEmpty())
    }
}
