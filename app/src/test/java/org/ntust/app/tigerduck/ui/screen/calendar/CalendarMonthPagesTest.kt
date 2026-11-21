package org.ntust.app.tigerduck.ui.screen.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

/** The month grid's pages: one a month, 1900 through 2099, read in Taipei, as the iPhone's. */
class CalendarMonthPagesTest {

    private val taipei = ZoneId.of("Asia/Taipei")
    private fun month(year: Int, month: Int): Date = Date.from(LocalDate.of(year, month, 1).atStartOfDay(taipei).toInstant())

    @Test
    fun `n page turns land n months away, either way`() {
        val july = MonthPages.pageOf(month(2026, 7))

        assertEquals(month(2026, 10), MonthPages.monthAt(july + 3))
        assertEquals(month(2026, 2), MonthPages.monthAt(july - 5))
    }

    @Test
    fun `a turn crosses the year`() {
        assertEquals(month(2027, 1), MonthPages.monthAt(MonthPages.pageOf(month(2026, 12)) + 1))
        assertEquals(month(2026, 12), MonthPages.monthAt(MonthPages.pageOf(month(2027, 1)) - 1))
    }

    @Test
    fun `the first minutes of a month in Taipei are on its page`() {
        // Still July 31 in UTC.
        val justAfterMidnight = Date.from(LocalDateTime.of(2026, 8, 1, 0, 5).atZone(taipei).toInstant())

        assertEquals(month(2026, 8), MonthPages.monthAt(MonthPages.pageOf(justAfterMidnight)))
    }

    @Test
    fun `the pages run from January 1900 through December 2099`() {
        assertEquals(month(1900, 1), MonthPages.monthAt(0))
        assertEquals(month(2099, 12), MonthPages.monthAt(MonthPages.COUNT - 1))
        assertEquals(MonthPages.COUNT - 1, MonthPages.pageOf(month(2150, 3)))
        assertEquals(0, MonthPages.pageOf(month(1850, 3)))
    }
}
