package org.ntust.app.tigerduck.data

import org.ntust.app.tigerduck.data.model.Course
import org.junit.Assert.*
import org.junit.Test

class CourseScheduleUtilsTest {

    @Test
    fun `returns null for empty course list`() {
        assertNull(computeOngoingCourse(emptyList(), weekday = 1, minuteOfDay = 660))
    }

    @Test
    fun `returns ongoing course when current time is within a contiguous block`() {
        val course = Course.fromSchedule("CS101", "Algorithms", schedule = mapOf(1 to listOf("3", "4")))
        val result = computeOngoingCourse(listOf(course), weekday = 1, minuteOfDay = 660)
        assertNotNull(result)
        assertEquals("CS101", result!!.course.courseNo)
        assertEquals("3", result.firstPeriodId)
        assertEquals(620, result.startMinute)
        assertEquals(730, result.endMinute)
    }

    @Test
    fun `returns null when between two non-contiguous blocks of the same course`() {
        val course = Course.fromSchedule("CS101", "Algorithms", schedule = mapOf(1 to listOf("3", "6")))
        assertNull(computeOngoingCourse(listOf(course), weekday = 1, minuteOfDay = 750))
    }

    @Test
    fun `returns null when course is on a different weekday`() {
        val course = Course.fromSchedule("CS101", "Algorithms", schedule = mapOf(2 to listOf("3")))
        assertNull(computeOngoingCourse(listOf(course), weekday = 1, minuteOfDay = 660))
    }

    @Test
    fun `parseHm converts HH colon MM to total minutes`() {
        assertEquals(620, parseHm("10:20"))
        assertEquals(0, parseHm("00:00"))
        assertEquals(1439, parseHm("23:59"))
    }

    @Test
    fun `parseHm returns null for null or malformed input`() {
        assertNull(parseHm(null))
        assertNull(parseHm("invalid"))
        assertNull(parseHm(""))
        assertNull(parseHm("10"))
    }
}
