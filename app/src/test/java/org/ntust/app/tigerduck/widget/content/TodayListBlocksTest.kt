package org.ntust.app.tigerduck.widget.content

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.widget.WidgetState

/**
 * Covers [todayListBlocks], the rows of the Today widget. The Week grid reads
 * [WidgetState.courses] itself, so a quiet day leaves it the timetable.
 */
class TodayListBlocksTest {

    private val mondayEvening = Course.fromSchedule("CS101", "Evening", schedule = mapOf(1 to listOf("10")))
    private val mondayMorning = Course.fromSchedule("CS102", "Morning", schedule = mapOf(1 to listOf("2", "3")))
    private val tuesday = Course.fromSchedule("CS201", "Tuesday", schedule = mapOf(2 to listOf("1")))

    private fun mondayState(isQuietToday: Boolean) = WidgetState(
        courses = listOf(mondayEvening, tuesday, mondayMorning),
        activeWeekdays = (1..5).toList(),
        activePeriodIds = emptyList(),
        currentWeekday = 1,
        currentMinuteOfDay = 8 * 60,
        isLoggedIn = true,
        isQuietToday = isQuietToday,
        ongoingBlocks = emptyList(),
        nextTodayBlock = null,
        tomorrowFirstCourseNo = null,
        tomorrowFirstCourseWeekday = null,
        tomorrowFirstCoursePeriodId = null,
        courseColors = emptyMap(),
        courseNameScale = 1f,
    )

    @Test
    fun `lists today's classes in the order they are taught`() {
        assertEquals(
            listOf("CS102", "CS101"),
            todayListBlocks(mondayState(isQuietToday = false)).map { it.course.courseNo },
        )
    }

    @Test
    fun `lists no class on a holiday the user has not opted back into`() {
        assertEquals(
            emptyList<String>(),
            todayListBlocks(mondayState(isQuietToday = true)).map { it.course.courseNo },
        )
    }
}
