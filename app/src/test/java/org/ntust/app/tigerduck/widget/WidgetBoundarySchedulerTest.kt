package org.ntust.app.tigerduck.widget

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
}
