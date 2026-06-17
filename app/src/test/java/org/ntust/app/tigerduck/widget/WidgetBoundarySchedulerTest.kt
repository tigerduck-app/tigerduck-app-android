package org.ntust.app.tigerduck.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course

class WidgetBoundarySchedulerTest {

    @Test
    fun `returns class start time when before all classes`() {
        val course = Course.fromSchedule("CS101", "Test", schedule = mapOf(1 to listOf("3")))
        assertEquals(
            620,
            WidgetBoundaryScheduler.nextBoundaryMinuteAfter(
                listOf(course),
                weekday = 1,
                currentMinute = 500
            ),
        )
    }

    @Test
    fun `returns class end time when currently inside class`() {
        val course = Course.fromSchedule("CS101", "Test", schedule = mapOf(1 to listOf("3")))
        assertEquals(
            670,
            WidgetBoundaryScheduler.nextBoundaryMinuteAfter(
                listOf(course),
                weekday = 1,
                currentMinute = 650
            ),
        )
    }

    @Test
    fun `returns null when no future boundaries today`() {
        val course = Course.fromSchedule("CS101", "Test", schedule = mapOf(1 to listOf("3")))
        assertNull(
            WidgetBoundaryScheduler.nextBoundaryMinuteAfter(
                listOf(course),
                weekday = 1,
                currentMinute = 700
            ),
        )
    }

    @Test
    fun `returns null for empty course list`() {
        assertNull(
            WidgetBoundaryScheduler.nextBoundaryMinuteAfter(
                emptyList(),
                weekday = 1,
                currentMinute = 0
            )
        )
    }

    @Test
    fun `ignores courses on other weekdays`() {
        val course = Course.fromSchedule("CS101", "Test", schedule = mapOf(2 to listOf("3")))
        assertNull(
            WidgetBoundaryScheduler.nextBoundaryMinuteAfter(
                listOf(course),
                weekday = 1,
                currentMinute = 0
            )
        )
    }

    @Test
    fun `returns start of later period when between two non-contiguous periods`() {
        // Period 3: 10:20–11:10 (start=620, end=670)
        // Period 7: 14:20–15:10 (start=860, end=910)
        // currentMinute=750 is after period 3 has ended and before period 7 begins,
        // so the next boundary must be period 7's start minute (860).
        val course = Course.fromSchedule("CS101", "Test", schedule = mapOf(1 to listOf("3", "7")))
        assertEquals(
            860,
            WidgetBoundaryScheduler.nextBoundaryMinuteAfter(
                listOf(course),
                weekday = 1,
                currentMinute = 750
            )
        )
    }
}
