package org.ntust.app.tigerduck.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseScheduleUtilsTest {

    private fun course(no: String, schedule: Map<Int, List<String>>): Course =
        Course.fromSchedule(courseNo = no, courseName = "C$no", schedule = schedule)

    @Test
    fun `computeOngoingCourses returns empty when no class matches`() {
        val courses = listOf(course("1", mapOf(1 to listOf("1", "2"))))
        val result = computeOngoingCourses(courses, weekday = 2, minuteOfDay = 8 * 60 + 10)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `computeOngoingCourses returns the matching block on the right weekday`() {
        val courses = listOf(course("CN", mapOf(1 to listOf("1", "2"))))
        val result = computeOngoingCourses(courses, weekday = 1, minuteOfDay = 8 * 60 + 30)
        assertEquals(1, result.size)
        assertEquals("CN", result[0].course.courseNo)
        assertEquals("1", result[0].firstPeriodId)
    }

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
}
