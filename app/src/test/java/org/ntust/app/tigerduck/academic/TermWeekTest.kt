package org.ntust.app.tigerduck.academic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** The term's week, week 1 being the Monday-to-Sunday week of its first day. Ported from iOS `TermWeekTests`. */
class TermWeekTest {

    private fun day(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day)

    private val calendar = AcademicCalendar(
        revision = 1L,
        terms = listOf(
            SemesterTerm("1151", day(2026, 9, 16), day(2027, 1, 15)),
            SemesterTerm("1152", day(2027, 2, 22), day(2027, 6, 18)),
        ),
        holidays = emptyList(),
    )

    private fun week(on: LocalDate, term: String = "1151"): Int? = calendar.week(of = term, on = on)

    @Test
    fun `week 1 is the Monday-to-Sunday week holding a first day in mid-week`() {
        assertEquals(1, week(day(2026, 9, 16)))
        assertEquals(1, week(day(2026, 9, 20)))
        assertEquals(2, week(day(2026, 9, 21)))
    }

    @Test
    fun `the week turns between Sunday and Monday`() {
        assertEquals(7, week(day(2026, 11, 1)))
        assertEquals(8, week(day(2026, 11, 2)))
    }

    @Test
    fun `the term's last day is in its last week and the day after has none`() {
        assertEquals(18, week(day(2027, 1, 11)))
        assertEquals(18, week(day(2027, 1, 15)))
        assertNull(week(day(2027, 1, 16)))
    }

    @Test
    fun `a day before the first one has no week, week 1's Monday and Tuesday included`() {
        assertNull(week(day(2026, 9, 15)))
        assertNull(week(day(2026, 9, 14)))
        assertNull(week(day(2026, 8, 1)))
    }

    @Test
    fun `a term the day is not in gives no week, nor does one the calendar lacks or an empty calendar`() {
        val friday = day(2026, 11, 6)
        assertEquals(8, week(friday))
        assertNull(week(friday, "1152"))
        assertNull(week(friday, "1142"))
        assertNull(week(day(2027, 3, 1), "1151"))
        assertEquals(2, week(day(2027, 3, 1), "1152"))
        assertNull(AcademicCalendar.EMPTY.week(of = "1151", on = friday))
    }

    @Test
    fun `a term starting on a Monday or a Sunday counts from that week's Monday`() {
        val monday = AcademicCalendar(1L, listOf(SemesterTerm("1152", day(2027, 2, 22), day(2027, 6, 18))), emptyList())
        assertEquals(1, monday.week(of = "1152", on = day(2027, 2, 28)))
        assertEquals(2, monday.week(of = "1152", on = day(2027, 3, 1)))
        val sunday = AcademicCalendar(1L, listOf(SemesterTerm("1152", day(2027, 2, 21), day(2027, 6, 18))), emptyList())
        assertEquals(1, sunday.week(of = "1152", on = day(2027, 2, 21)))
        assertEquals(2, sunday.week(of = "1152", on = day(2027, 2, 22)))
    }
}
