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
    fun `computeOngoingCourses is ongoing in the last minute before the block end`() {
        // Period "2" ends at 10:00 = 600.  09:59 = 599 is still Ongoing.
        val courses = listOf(course("BC", mapOf(1 to listOf("1", "2"))))
        val result = computeOngoingCourses(courses, weekday = 1, minuteOfDay = 599)
        assertEquals(1, result.size)
        assertEquals(600, result[0].endMinute)
    }

    @Test
    fun `computeOngoingCourses is not ongoing at the exact block end minute`() {
        // D2: start <= now < end.  Minute == end → over.
        val courses = listOf(course("BC", mapOf(1 to listOf("1", "2"))))
        assertTrue(computeOngoingCourses(courses, weekday = 1, minuteOfDay = 600).isEmpty())
    }

    @Test
    fun `computeOngoingCourses is not ongoing one minute after block end`() {
        // 10:01 = 601 — one minute past the end of the block → not Ongoing.
        val courses = listOf(course("BC", mapOf(1 to listOf("1", "2"))))
        val result = computeOngoingCourses(courses, weekday = 1, minuteOfDay = 601)
        assertTrue(result.isEmpty())
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
    fun `computeOngoingCourses reports every period of the block it is in`() {
        // 11:30 is in period 4, the second period of the 3–4 block.
        val result = computeOngoingCourses(listOf(twice), weekday = 1, minuteOfDay = 11 * 60 + 30)
        assertEquals(listOf("3", "4"), result.single().periods)
    }

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

