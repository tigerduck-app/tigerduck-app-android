package org.ntust.app.tigerduck.widget

import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.data.model.Course
import org.junit.Assert.*
import org.junit.Test

class WidgetBoundarySchedulerTest {

    @Test
    fun `returns class start time when before all classes`() {
        val course = Course.fromSchedule("CS101", "Test", schedule = mapOf(1 to listOf("3")))
        assertEquals(
            620,
            WidgetBoundaryScheduler.nextBoundaryMinuteAfter(listOf(course), weekday = 1, currentMinute = 500),
        )
    }

    @Test
    fun `returns class end time when currently inside class`() {
        val course = Course.fromSchedule("CS101", "Test", schedule = mapOf(1 to listOf("3")))
        assertEquals(
            670,
            WidgetBoundaryScheduler.nextBoundaryMinuteAfter(listOf(course), weekday = 1, currentMinute = 650),
        )
    }

    @Test
    fun `returns null when no future boundaries today`() {
        val course = Course.fromSchedule("CS101", "Test", schedule = mapOf(1 to listOf("3")))
        assertNull(
            WidgetBoundaryScheduler.nextBoundaryMinuteAfter(listOf(course), weekday = 1, currentMinute = 700),
        )
    }

    @Test
    fun `returns null for empty course list`() {
        assertNull(WidgetBoundaryScheduler.nextBoundaryMinuteAfter(emptyList(), weekday = 1, currentMinute = 0))
    }

    @Test
    fun `ignores courses on other weekdays`() {
        val course = Course.fromSchedule("CS101", "Test", schedule = mapOf(2 to listOf("3")))
        assertNull(WidgetBoundaryScheduler.nextBoundaryMinuteAfter(listOf(course), weekday = 1, currentMinute = 0))
    }

    // --- chooseTriggerMillis: term flips have to pre-empt the class boundary ---

    private val termStart = AppConstants.CurrentTerm.START
    private val termEnd = AppConstants.CurrentTerm.END
    private val oneDay = 24L * 60 * 60 * 1000

    @Test
    fun `wakes at 開學 rather than the first class boundary of 開學日`() {
        // Evening before 開學, chain has handed off to 開學日's first class.
        val firstClassOnOpeningDay = termStart + 8 * 60 * 60 * 1000
        assertEquals(
            termStart,
            WidgetBoundaryScheduler.chooseTriggerMillis(
                boundaryMillis = firstClassOnOpeningDay,
                appNowMillis = termStart - 6 * 60 * 60 * 1000,
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
            ),
        )
    }

    @Test
    fun `ignores 開學 once it has passed and uses 結業 instead`() {
        val midTermNow = termStart + 30 * oneDay
        val boundary = termEnd + oneDay
        assertEquals(
            termEnd,
            WidgetBoundaryScheduler.chooseTriggerMillis(boundary, midTermNow),
        )
    }

    @Test
    fun `falls through to the class boundary once the term is over`() {
        val afterTerm = termEnd + 5 * oneDay
        val boundary = afterTerm + 60 * 60 * 1000
        assertEquals(
            boundary,
            WidgetBoundaryScheduler.chooseTriggerMillis(boundary, afterTerm),
        )
    }

    @Test
    fun `a term flip exactly at now does not re-arm on itself`() {
        // Guards the alarm loop: at the instant of the flip the refresh
        // re-enters scheduleForToday, and START must no longer be a candidate.
        val boundary = termStart + 8 * 60 * 60 * 1000
        assertEquals(
            boundary,
            WidgetBoundaryScheduler.chooseTriggerMillis(boundary, termStart),
        )
    }
}
