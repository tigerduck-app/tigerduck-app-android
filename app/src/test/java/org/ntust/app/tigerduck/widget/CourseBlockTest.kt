package org.ntust.app.tigerduck.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.widget.content.todayListBlocks
import org.ntust.app.tigerduck.widget.content.tomorrowFirstBlock

/**
 * A course that meets twice a day, in periods 3–4 in one room and 7–8 in
 * another: every widget shows the block it is about, not the whole day.
 */
class CourseBlockTest {

    private val twice = Course.fromSchedule(
        courseNo = "CS2001301",
        courseName = "Data Structures",
        classroom = "TR-313, TR-412",
        schedule = mapOf(1 to listOf("3", "4", "7", "8"), 3 to listOf("3", "4")),
        classroomMap = mapOf(
            "1-3" to "TR-313", "1-4" to "TR-313",
            "1-7" to "TR-412", "1-8" to "TR-412",
            "3-3" to "TR-313", "3-4" to "TR-313",
        ),
    )
    private val early = Course.fromSchedule("CS1001301", "Calculus", schedule = mapOf(1 to listOf("1")))

    private fun minute(hhmm: String): Int = hhmm.split(":").let { it[0].toInt() * 60 + it[1].toInt() }

    private fun mondayState(at: String) = WidgetState(
        courses = listOf(twice, early),
        activeWeekdays = (1..5).toList(),
        activePeriodIds = listOf("1", "2", "3", "4", "6", "7", "8", "9", "10"),
        currentWeekday = 1,
        currentMinuteOfDay = minute(at),
        isLoggedIn = true,
        isQuietToday = false,
        ongoingBlocks = WidgetDataLoader.computeOngoingBlocks(listOf(twice, early), 1, minute(at)),
        nextTodayBlock = null,
        tomorrowFirstCourseNo = null,
        tomorrowFirstCourseWeekday = null,
        tomorrowFirstCoursePeriodId = null,
        courseColors = emptyMap(),
        courseNameScale = 1f,
    )

    @Test
    fun `the ongoing card shows the morning block's times, periods, room and progress`() {
        val block = WidgetDataLoader.computeOngoingBlocks(listOf(twice), 1, minute("10:30")).single()

        assertEquals("10:20", block.startTime)
        assertEquals("12:10", block.endTime)
        assertEquals("3–4", block.periodRange)
        assertEquals("TR-313", block.classroom)
        assertEquals(0.5f, block.progress(minute("11:15")), 0.001f)
    }

    @Test
    fun `the afternoon block is ongoing on its own`() {
        val block = WidgetDataLoader.computeOngoingBlocks(listOf(twice), 1, minute("15:00")).single()

        assertEquals("14:20–16:20 7–8", "${block.startTime}–${block.endTime} ${block.periodRange}")
        assertEquals("TR-412", block.classroom)
    }

    @Test
    fun `after the morning block the next class starts at the afternoon block`() {
        val next = WidgetDataLoader.computeNextTodayBlock(listOf(twice), 1, minute("12:30"), emptyList())!!

        assertEquals("14:20", next.startTime)
        assertEquals("TR-412", next.classroom)
    }

    @Test
    fun `the Today widget has a row per block, and only the block in progress is ongoing`() {
        val state = mondayState(at = "10:30")

        val rows = todayListBlocks(state)

        assertEquals(
            listOf(
                "1 08:10–09:00 ",
                "3–4 10:20–12:10 TR-313",
                "7–8 14:20–16:20 TR-412",
            ),
            rows.map { "${it.periodRange} ${it.startTime}–${it.endTime} ${it.classroom}" },
        )
        assertEquals(listOf(false, true, false), rows.map { state.isOngoing(it) })
    }

    @Test
    fun `the Week grid lights up only today's tile of the block in progress`() {
        val state = mondayState(at = "10:30")

        assertTrue(state.isOngoingTile(twice, 1, listOf("3", "4")))
        assertFalse(state.isOngoingTile(twice, 1, listOf("7", "8")))
        assertFalse(state.isOngoingTile(twice, 3, listOf("3", "4")))
    }

    @Test
    fun `a tile that joins two blocks across a hidden period lights up for either`() {
        // Period 5 is hidden by default, so a course on 4 and 6 is one tile in
        // the grid but two blocks in the schedule.
        val split = Course.fromSchedule("CS3001301", "Networks", schedule = mapOf(1 to listOf("4", "6")))
        val state = mondayState(at = "13:30").copy(
            courses = listOf(split),
            ongoingBlocks = WidgetDataLoader.computeOngoingBlocks(listOf(split), 1, minute("13:30")),
        )

        assertTrue(state.isOngoingTile(split, 1, listOf("4", "6")))
    }

    @Test
    fun `the next day's card shows the room of that day's first block`() {
        val state = mondayState(at = "18:00").copy(
            tomorrowFirstCourseNo = twice.courseNo,
            tomorrowFirstCourseWeekday = 1,
            tomorrowFirstCoursePeriodId = "3",
        )

        val block = tomorrowFirstBlock(state)!!

        assertEquals("10:20", block.startTime)
        assertEquals("TR-313", block.classroom)
    }
}
