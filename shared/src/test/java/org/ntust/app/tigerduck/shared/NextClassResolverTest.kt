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

    // ──────────────────────────────────────────────────────────────────────────
    // Mid-block test
    //
    // mondayMorningCourse has periods ["1","2"] on Monday:
    //   period "1": 08:10–09:00  (490–540)
    //   period "2": 09:10–10:00  (550–600)
    //
    // At 09:30 (= 570), the query falls inside period "2".
    // currentPeriodBoundsMinutes finds that 570 ∈ 550..600 → returns (550, 600).
    // The Ongoing result must carry those tighter per-period bounds, not the
    // full block bounds (490–600).
    //
    // PeriodTimes.mapping:
    //   "2" → ("09:10" to "10:00")  →  startMinute = 9*60+10 = 550, endMinute = 10*60 = 600
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `Ongoing mid-block uses current period bounds not full block bounds`() {
        val courses = listOf(mondayMorningCourse)
        // 09:30 on Monday — inside period "2" (09:10–10:00).
        val r = NextClassResolver.resolve(courses, weekday = 1, minuteOfDay = 9 * 60 + 30)
        assertTrue(r is NextClassResult.Ongoing)
        r as NextClassResult.Ongoing
        assertEquals("CN", r.course.courseNo)
        // Must reflect period "2" bounds, not the full block start (08:10).
        assertEquals(9 * 60 + 10, r.startMinute)   // 09:10 = 550
        assertEquals(10 * 60, r.endMinute)          // 10:00 = 600
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Inter-period break test
    //
    // At 09:05 (= 545), we are in the 10-minute gap between period "1" (ends
    // 09:00 = 540) and period "2" (starts 09:10 = 550).
    //
    // computeOngoingCourses uses the BLOCK span (490..600), so 545 ∈ 490..600 →
    // the course is still considered Ongoing.
    //
    // currentPeriodBoundsMinutes inspects individual periods: 545 ∉ 490..540
    // and 545 ∉ 550..600 → returns null → the resolver falls back to
    // (ongoing.startMinute, ongoing.endMinute) = (490, 600).
    //
    // This test documents EXISTING semantics.  The intra-block gap is intentionally
    // treated as Ongoing because both periods form one logical teaching block.
    // If the semantics are ever changed (e.g. to NextToday during the break),
    // update this test and note the deliberate behavioral change.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `Ongoing during intra-block break uses full block bounds (documents existing semantics)`() {
        val courses = listOf(mondayMorningCourse)
        // 09:05 on Monday — 5 min AFTER period "1" ends (09:00), BEFORE period "2" starts (09:10).
        val r = NextClassResolver.resolve(courses, weekday = 1, minuteOfDay = 9 * 60 + 5)
        // Current behavior: still Ongoing (block span 490..600 contains 545).
        assertTrue(r is NextClassResult.Ongoing)
        r as NextClassResult.Ongoing
        assertEquals("CN", r.course.courseNo)
        // currentPeriodBoundsMinutes returns null for this minute (not in any single
        // period's range), so the resolver falls back to the full block bounds.
        assertEquals(8 * 60 + 10, r.startMinute)   // block start = 08:10 = 490
        assertEquals(10 * 60, r.endMinute)          // block end   = 10:00 = 600
    }
}
