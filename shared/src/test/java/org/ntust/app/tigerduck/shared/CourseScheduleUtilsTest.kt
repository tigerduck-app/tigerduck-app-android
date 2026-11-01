package org.ntust.app.tigerduck.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseScheduleUtilsTest {

    @Test
    fun `parseHm handles HHMM and rejects garbage`() {
        assertEquals(8 * 60 + 10, parseHm("08:10"))
        assertEquals(null, parseHm(null))
        assertEquals(null, parseHm("nope"))
    }

    @Test
    fun `collapseContiguousPeriods groups adjacent periods`() {
        val result = collapseContiguousPeriods(listOf("3", "4", "6"))
        assertEquals(listOf("3" to "4", "6" to "6"), result)
    }

    // ---- a course meeting twice a day ----

    private val twice = Course.fromSchedule(
        courseNo = "CS2001301",
        courseName = "Data Structures",
        classroom = "TR-313, TR-412",
        schedule = mapOf(1 to listOf("8", "3", "7", "4")),
        classroomMap = mapOf(
            "1-3" to "TR-313", "1-4" to "TR-313",
            "1-7" to "TR-412", "1-8" to "TR-412",
        ),
    )

    @Test
    fun `contiguousPeriodRuns splits a day into its blocks`() {
        assertEquals(listOf(listOf("3", "4"), listOf("7", "8")), contiguousPeriodRuns(listOf("8", "3", "7", "4")))
    }

    @Test
    fun `blockClassroom gives one block's room, not the day's`() {
        assertEquals("TR-313", twice.blockClassroom(1, listOf("3", "4")))
        assertEquals("TR-412", twice.blockClassroom(1, listOf("7", "8")))
    }

    @Test
    fun `blockClassroom falls back to the flat room for a block the map lacks`() {
        assertEquals("TR-313, TR-412", twice.blockClassroom(2, listOf("1")))
    }

    @Test
    fun `blockClassroomFrom gives the room of the block holding the period, or of the next block`() {
        assertEquals("TR-313", twice.blockClassroomFrom(1, "4"))
        // Period 5 is in no block; the next one starts at 7.
        assertEquals("TR-412", twice.blockClassroomFrom(1, "5"))
        // Nothing after period 9: the day's rooms.
        assertEquals("TR-313, TR-412", twice.blockClassroomFrom(1, "9"))
    }
}

