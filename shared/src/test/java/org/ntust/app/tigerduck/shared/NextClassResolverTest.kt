package org.ntust.app.tigerduck.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NextClassResolverTest {

    private fun course(no: String, schedule: Map<Int, List<String>>): Course =
        Course.fromSchedule(courseNo = no, courseName = "Course-$no", schedule = schedule)

    private val mondayMorningCourse = course("CN", mapOf(1 to listOf("1", "2")))      // 08:10-10:00
    private val mondayAfternoonCourse = course("AI", mapOf(1 to listOf("7", "8")))    // 14:20-16:20
    private val tuesdayCourse = course("DB", mapOf(2 to listOf("3", "4")))            // 10:20-12:10

    @Test
    fun `Empty when no courses`() {
        val r = NextClassResolver.resolve(courses = emptyList(), weekday = 1, minuteOfDay = 600)
        assertTrue(r is NextClassResult.Empty)
    }

    @Test
    fun `Ongoing returns currently-running course and next-today below`() {
        val courses = listOf(mondayMorningCourse, mondayAfternoonCourse)
        // 08:30 on Monday — period 1 ongoing, AI is next today.
        val r = NextClassResolver.resolve(courses, weekday = 1, minuteOfDay = 8 * 60 + 30)
        assertTrue(r is NextClassResult.Ongoing)
        r as NextClassResult.Ongoing
        assertEquals("CN", r.course.courseNo)
        assertEquals(9 * 60, r.endMinute)
        assertEquals("AI", r.nextToday?.course?.courseNo)
        assertEquals(14 * 60 + 20, r.nextToday?.startMinute)
    }

    @Test
    fun `Ongoing with no next-today returns null secondary`() {
        val courses = listOf(mondayMorningCourse)
        val r = NextClassResolver.resolve(courses, weekday = 1, minuteOfDay = 8 * 60 + 30)
        assertTrue(r is NextClassResult.Ongoing)
        assertEquals(null, (r as NextClassResult.Ongoing).nextToday)
    }

    @Test
    fun `NextToday when not in class but more classes today`() {
        val courses = listOf(mondayMorningCourse, mondayAfternoonCourse)
        // 11:00 Mon — gap between morning and afternoon. AI starts at 14:20.
        val r = NextClassResolver.resolve(courses, weekday = 1, minuteOfDay = 11 * 60)
        assertTrue(r is NextClassResult.NextToday)
        r as NextClassResult.NextToday
        assertEquals("AI", r.course.courseNo)
        assertEquals(14 * 60 + 20, r.startMinute)
    }

    @Test
    fun `NextFuture walks forward across weekdays`() {
        val courses = listOf(tuesdayCourse)
        // Sunday after all classes. Walk forward to Tuesday (2 days).
        val r = NextClassResolver.resolve(courses, weekday = 7, minuteOfDay = 23 * 60)
        assertTrue(r is NextClassResult.NextFuture)
        r as NextClassResult.NextFuture
        assertEquals("DB", r.course.courseNo)
        assertEquals(2, r.daysAhead)
        assertEquals(10 * 60 + 20, r.startMinute)
    }

    @Test
    fun `NextFuture daysAhead is 1 when tomorrow has class`() {
        val courses = listOf(tuesdayCourse)
        // Monday evening — tomorrow (Tuesday) has class.
        val r = NextClassResolver.resolve(courses, weekday = 1, minuteOfDay = 22 * 60)
        r as NextClassResult.NextFuture
        assertEquals(1, r.daysAhead)
    }

    @Test
    fun `Empty when nothing scheduled in next 7 days`() {
        val r = NextClassResolver.resolve(courses = emptyList(), weekday = 1, minuteOfDay = 600)
        assertTrue(r is NextClassResult.Empty)
    }

    @Test
    fun `today's classes returns chronological list with status`() {
        val courses = listOf(mondayMorningCourse, mondayAfternoonCourse)
        // 11:00 Mon — morning ended, afternoon upcoming.
        val today = NextClassResolver.todaysClasses(courses, weekday = 1, minuteOfDay = 11 * 60)
        assertEquals(2, today.size)
        assertEquals("CN", today[0].course.courseNo)
        assertEquals(TodayClassStatus.Ended, today[0].status)
        assertEquals("AI", today[1].course.courseNo)
        assertEquals(TodayClassStatus.Upcoming, today[1].status)
    }

    @Test
    fun `today's classes marks ongoing row`() {
        val courses = listOf(mondayMorningCourse)
        val today = NextClassResolver.todaysClasses(courses, weekday = 1, minuteOfDay = 8 * 60 + 30)
        assertEquals(TodayClassStatus.Ongoing, today.single().status)
    }
}
