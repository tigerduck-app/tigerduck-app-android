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

    // ──────────────────────────────────────────────────────────────────────────
    // Boundary tests for computeOngoingCourses
    //
    // Course with periods ["1","2"] on Monday forms a single contiguous block:
    //   period "1": 08:10–09:00  (startMinute = 490, endMinute = 540)
    //   period "2": 09:10–10:00  (startMinute = 550, endMinute = 600)
    // The block therefore spans 490..600.  The inclusive contract (in startMin..endMin)
    // means both boundary minutes (490 and 600) must be treated as Ongoing, while
    // 601 must not match.
    //
    // PeriodTimes.mapping source of truth (read from PeriodTimes.kt):
    //   "1" → ("08:10" to "09:00")  →  490..540
    //   "2" → ("09:10" to "10:00")  →  550..600
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `computeOngoingCourses is ongoing at exact block start minute (inclusive)`() {
        // Period "1" starts at 08:10 = 8*60+10 = 490.  Minute == start → Ongoing.
        val courses = listOf(course("BC", mapOf(1 to listOf("1", "2"))))
        val result = computeOngoingCourses(courses, weekday = 1, minuteOfDay = 490)
        assertEquals(1, result.size)
        assertEquals("BC", result[0].course.courseNo)
        assertEquals(490, result[0].startMinute)
    }

    @Test
    fun `computeOngoingCourses is ongoing at exact block end minute (inclusive)`() {
        // Period "2" ends at 10:00 = 10*60 = 600.  Minute == end → Ongoing.
        val courses = listOf(course("BC", mapOf(1 to listOf("1", "2"))))
        val result = computeOngoingCourses(courses, weekday = 1, minuteOfDay = 600)
        assertEquals(1, result.size)
        assertEquals(600, result[0].endMinute)
    }

    @Test
    fun `computeOngoingCourses is not ongoing one minute after block end`() {
        // 10:01 = 601 — one minute past the end of the block → not Ongoing.
        val courses = listOf(course("BC", mapOf(1 to listOf("1", "2"))))
        val result = computeOngoingCourses(courses, weekday = 1, minuteOfDay = 601)
        assertTrue(result.isEmpty())
    }
}
