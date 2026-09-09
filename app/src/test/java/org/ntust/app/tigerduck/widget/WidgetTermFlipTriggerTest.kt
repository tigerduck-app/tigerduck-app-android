package org.ntust.app.tigerduck.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.AppConstants
import java.time.LocalDate

/**
 * Covers [WidgetBoundaryScheduler.chooseTriggerMillis] — the term-flip arm of
 * the widget refresh chain. Separate from [WidgetBoundarySchedulerTest], which
 * covers the class-boundary arithmetic.
 */
class WidgetTermFlipTriggerTest {

    private val oneDay = 24L * 60 * 60 * 1000

    private fun taipeiMidnight(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atStartOfDay(AppConstants.TAIPEI_ZONE)
            .toInstant()
            .toEpochMilli()

    /** 開學 and the day after 結業, exactly as `AcademicCalendar.termFlipMillis` hands them over. */
    private val termStart = taipeiMidnight(2026, 9, 7)
    private val termEnd = taipeiMidnight(2026, 12, 26)
    private val flips = listOf(termStart, termEnd)

    @Test
    fun `wakes at 開學 rather than the first class boundary of 開學日`() {
        // Evening before 開學, chain has handed off to 開學日's first class.
        val firstClassOnOpeningDay = termStart + 8 * 60 * 60 * 1000
        assertEquals(
            termStart,
            WidgetBoundaryScheduler.chooseTriggerMillis(
                boundaryMillis = firstClassOnOpeningDay,
                appNowMillis = termStart - 6 * 60 * 60 * 1000,
                termFlips = flips,
            ),
        )
    }

    @Test
    fun `keeps the class boundary when it lands before the term flip`() {
        val boundary = termStart - 3 * oneDay
        assertEquals(
            boundary,
            WidgetBoundaryScheduler.chooseTriggerMillis(
                boundaryMillis = boundary,
                appNowMillis = termStart - 4 * oneDay,
                termFlips = flips,
            ),
        )
    }

    @Test
    fun `wakes at 結業 rather than the following day's stale boundary`() {
        val boundaryAfterTermEnds = termEnd + 8 * 60 * 60 * 1000
        assertEquals(
            termEnd,
            WidgetBoundaryScheduler.chooseTriggerMillis(
                boundaryMillis = boundaryAfterTermEnds,
                appNowMillis = termEnd - 2 * 60 * 60 * 1000,
                termFlips = flips,
            ),
        )
    }

    @Test
    fun `ignores 開學 once it has passed and uses 結業 instead`() {
        val midTermNow = termStart + 30 * oneDay
        val boundary = termEnd + oneDay
        assertEquals(
            termEnd,
            WidgetBoundaryScheduler.chooseTriggerMillis(boundary, midTermNow, flips),
        )
    }

    @Test
    fun `falls through to the class boundary once the term is over`() {
        val afterTerm = termEnd + 5 * oneDay
        val boundary = afterTerm + 60 * 60 * 1000
        assertEquals(
            boundary,
            WidgetBoundaryScheduler.chooseTriggerMillis(boundary, afterTerm, flips),
        )
    }

    @Test
    fun `a term flip exactly at now does not re-arm on itself`() {
        // Guards the alarm loop: at the instant of the flip the refresh
        // re-enters scheduleForToday, and START must no longer be a candidate.
        val boundary = termStart + 8 * 60 * 60 * 1000
        assertEquals(
            boundary,
            WidgetBoundaryScheduler.chooseTriggerMillis(boundary, termStart, flips),
        )
    }

    @Test
    fun `an empty calendar leaves the class boundary alone`() {
        // AcademicCalendar.EMPTY publishes no terms, so the widgets keep the
        // behaviour they had before the feed existed.
        val boundary = termStart + 8 * 60 * 60 * 1000
        assertEquals(
            boundary,
            WidgetBoundaryScheduler.chooseTriggerMillis(boundary, termStart - oneDay, emptyList()),
        )
    }

}
