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
    private val wednesdayCourse = course("OS", mapOf(3 to listOf("1", "2")))          // 08:10-10:00

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
        assertEquals(10 * 60, r.endMinute)   // full block end (periods 1+2)
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

    // A quiet day is outside the term, or a school holiday the user has not
    // opted back into.

    @Test
    fun `on a quiet day nothing is on now and the next class is the next class day's`() {
        val courses = listOf(mondayMorningCourse, mondayAfternoonCourse, tuesdayCourse)
        // 08:30 Mon, inside CN's block, with AI later today.
        val r = NextClassResolver.resolve(courses, weekday = 1, minuteOfDay = 8 * 60 + 30) {
            it == 0
        }
        assertTrue(r is NextClassResult.NextFuture)
        r as NextClassResult.NextFuture
        assertEquals("DB", r.course.courseNo)
        assertEquals(1, r.daysAhead)
    }

    @Test
    fun `a quiet day ahead is skipped for the next class`() {
        val courses = listOf(tuesdayCourse, wednesdayCourse)
        // Monday evening, Tuesday quiet: Wednesday's class is next.
        val r = NextClassResolver.resolve(courses, weekday = 1, minuteOfDay = 22 * 60) {
            it == 1
        }
        assertTrue(r is NextClassResult.NextFuture)
        r as NextClassResult.NextFuture
        assertEquals("OS", r.course.courseNo)
        assertEquals(2, r.daysAhead)
    }

    @Test
    fun `Empty when today and the whole week ahead are quiet`() {
        val courses = listOf(mondayMorningCourse, tuesdayCourse)
        val r = NextClassResolver.resolve(courses, weekday = 1, minuteOfDay = 8 * 60 + 30) { true }
        assertTrue(r is NextClassResult.Empty)
    }

    @Test
    fun `today's classes are empty on a quiet day`() {
        val courses = listOf(mondayMorningCourse, mondayAfternoonCourse)
        val today = NextClassResolver.todaysClasses(
            courses, weekday = 1, minuteOfDay = 11 * 60, quietToday = true,
        )
        assertTrue(today.isEmpty())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Block-level bounds tests
    //
    // mondayMorningCourse has periods ["1","2"] on Monday:
    //   period "1": 08:10–09:00  (490–540)
    //   period "2": 09:10–10:00  (550–600)
    //
    // Ongoing always uses the full contiguous block bounds (490–600) regardless
    // of which individual period the current minute falls in. This gives one
    // smooth 0→100% progress arc and a stable "Ends at" display.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `Ongoing mid-block uses full block bounds for smooth progress`() {
        val courses = listOf(mondayMorningCourse)
        // 09:30 on Monday — inside period "2" (09:10–10:00).
        val r = NextClassResolver.resolve(courses, weekday = 1, minuteOfDay = 9 * 60 + 30)
        assertTrue(r is NextClassResult.Ongoing)
        r as NextClassResult.Ongoing
        assertEquals("CN", r.course.courseNo)
        assertEquals(8 * 60 + 10, r.startMinute)   // block start = 08:10 = 490
        assertEquals(10 * 60, r.endMinute)          // block end   = 10:00 = 600
    }

    @Test
    fun `Ongoing during intra-block break uses full block bounds`() {
        val courses = listOf(mondayMorningCourse)
        // 09:05 on Monday — 5 min AFTER period "1" ends (09:00), BEFORE period "2" starts (09:10).
        val r = NextClassResolver.resolve(courses, weekday = 1, minuteOfDay = 9 * 60 + 5)
        assertTrue(r is NextClassResult.Ongoing)
        r as NextClassResult.Ongoing
        assertEquals("CN", r.course.courseNo)
        assertEquals(8 * 60 + 10, r.startMinute)   // block start = 08:10 = 490
        assertEquals(10 * 60, r.endMinute)          // block end   = 10:00 = 600
    }

    // ──────────────────────────────────────────────────────────────────────────
    // The school's changes: a day's changed classes stand in for the block they
    // start in, as iOS `WatchCourse.on(dayKey)` has them.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a changed class runs at its changed times in its changed room`() {
        // CN's 1-2 (08:10-10:00) starts at 08:40 in TR-411 today only.
        val change = WatchClassChange(listOf("1", "2"), room = "TR-411", start = "08:40")
        val changed = { courseNo: String, daysAhead: Int ->
            if (courseNo == "CN" && daysAhead == 0) listOf(change) else emptyList()
        }
        val before = NextClassResolver.resolve(listOf(mondayMorningCourse), 1, 8 * 60 + 20, changed)
        before as NextClassResult.NextToday
        assertEquals(8 * 60 + 40, before.startMinute)
        assertEquals("TR-411", before.classroom)
        assertEquals(change, before.change)

        val during = NextClassResolver.resolve(listOf(mondayMorningCourse), 1, 9 * 60, changed)
        during as NextClassResult.Ongoing
        assertEquals(8 * 60 + 40, during.startMinute)
        assertEquals(10 * 60, during.endMinute)
        assertEquals("TR-411", during.classroom)
        assertEquals(change, during.change)
    }

    @Test
    fun `a later day's cancelled class is passed over for the next class`() {
        val changed = { courseNo: String, daysAhead: Int ->
            if (courseNo == "DB" && daysAhead == 1) {
                listOf(WatchClassChange(listOf("3", "4"), cancelled = true))
            } else {
                emptyList()
            }
        }
        val r = NextClassResolver.resolve(listOf(tuesdayCourse, wednesdayCourse), 1, 22 * 60, changed)
        r as NextClassResult.NextFuture
        assertEquals("OS", r.course.courseNo)
        assertEquals(2, r.daysAhead)
        assertEquals(null, r.change)
    }

    @Test
    fun `a later day's moved class is next at its changed start`() {
        val change = WatchClassChange(listOf("3", "4"), start = "10:50")
        val r = NextClassResolver.resolve(listOf(tuesdayCourse), 1, 22 * 60, { _, daysAhead ->
            if (daysAhead == 1) listOf(change) else emptyList()
        })
        r as NextClassResult.NextFuture
        assertEquals(10 * 60 + 50, r.startMinute)
        assertEquals(listOf("3", "4"), r.periods)
        assertEquals(change, r.change)
    }

    @Test
    fun `today's classes show a changed class in place of its block and leave a cancelled one out`() {
        val today = NextClassResolver.todaysClasses(
            listOf(mondayMorningCourse, mondayAfternoonCourse),
            weekday = 1,
            minuteOfDay = 11 * 60,
            changedClasses = { courseNo ->
                when (courseNo) {
                    "CN" -> listOf(WatchClassChange(listOf("1", "2"), cancelled = true))
                    "AI" -> listOf(
                        WatchClassChange(listOf("7"), room = "TR-411"),
                        WatchClassChange(listOf("8"), end = "16:00"),
                    )
                    else -> emptyList()
                }
            },
        )
        assertEquals(listOf(listOf("7"), listOf("8")), today.map { it.periods })
        assertEquals(listOf("TR-411", ""), today.map { it.classroom })
        assertEquals(listOf(14 * 60 + 20, 15 * 60 + 30), today.map { it.startMinute })
        assertEquals(listOf(15 * 60 + 10, 16 * 60), today.map { it.endMinute })
    }
}
