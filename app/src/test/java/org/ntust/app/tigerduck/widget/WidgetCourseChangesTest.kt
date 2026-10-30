package org.ntust.app.tigerduck.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.QuietDays
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.changes
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.row
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.thursday
import org.ntust.app.tigerduck.coursechanges.CourseChanges
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.widget.content.todayListBlocks
import org.ntust.app.tigerduck.widget.content.tomorrowFirstBlock
import org.ntust.app.tigerduck.widget.content.weekBlockChange
import java.time.LocalDate
import java.time.LocalTime

/**
 * The class widgets under the school's course changes: today's classes and the next days' as the
 * feed splits, moves and cancels them, and the Week grid's blocks whole, as the class table shows
 * them. Ported from the iPhone's `WidgetTimelineDerivationTests` and `WidgetSnapshotBuilderTests`;
 * Android's widgets read the feed in-process, so the snapshot's tests are put to the loader's
 * state. Thursday 2026-11-05 is the fixture's day; course numbers and rooms are made up.
 */
class WidgetCourseChangesTest {

    private val noQuietDays = QuietDays(AcademicCalendar.EMPTY, emptySet())

    private fun thursdayCourse(courseNo: String, name: String, periods: List<String>, room: String = "") =
        Course.fromSchedule(courseNo, name, classroom = room, schedule = mapOf(4 to periods))

    /** What the widgets draw at [time] on [day]. */
    private fun state(courses: List<Course>, changes: CourseChanges, time: String, day: LocalDate = thursday) =
        WidgetDataLoader.state(
            courses = courses,
            now = day.atTime(LocalTime.parse(time)),
            isLoggedIn = true,
            quietDays = noQuietDays,
            changes = changes,
            courseNameScale = 1f,
        )

    /** A block as a widget shows it, with what the school changed. */
    private fun CourseBlock.shown(): String = buildString {
        append("$periodRange $startTime–$endTime $classroom")
        if (isCancelled) append(" cancelled")
        if (timeChanged) append(" time")
        if (roomChanged) append(" room")
    }

    private fun minute(hhmm: String): Int = LocalTime.parse(hhmm).let { it.hour * 60 + it.minute }

    @Test
    fun `a cancelled meeting is neither ongoing nor next but stays in today's list`() {
        val gone = thursdayCourse("CC901", "Gone", listOf("1", "2"), room = "T1-101")
        val later = thursdayCourse("CC902", "Later", listOf("3"))
        val changes = changes(row(1, "cancelled", periods = listOf("1", "2"), courseNo = "CC901"))

        val now = state(listOf(gone, later), changes, "08:30")

        assertEquals(emptyList<CourseBlock>(), now.ongoingBlocks)
        assertEquals("CC902 3 10:20", now.nextTodayBlock?.let { "${it.course.courseNo} ${it.periodRange} ${it.startTime}" })
        assertEquals(listOf(true, false), todayListBlocks(now).map { it.isCancelled })
        val nextWeek = state(listOf(gone, later), changes, "08:30", thursday.plusWeeks(1))
        assertEquals(listOf("CC901"), nextWeek.ongoingBlocks.map { it.course.courseNo })
    }

    @Test
    fun `a meeting split by its changes runs as its classes`() {
        val split = thursdayCourse("CC901", "Split", listOf("1", "2", "3"), room = "T1-101")
        val changes = changes(
            row(1, "cancelled", periods = listOf("1", "2"), courseNo = "CC901"),
            row(2, "room", periods = listOf("3"), values = mapOf("room" to "T9-909"), courseNo = "CC901"),
        )

        val now = state(listOf(split), changes, "09:20")

        assertEquals(emptyList<CourseBlock>(), now.ongoingBlocks)
        assertEquals("3 10:20–11:10 T9-909 room", now.nextTodayBlock?.shown())
        assertEquals(
            listOf("1–2 08:10–10:00 T1-101 cancelled", "3 10:20–11:10 T9-909 room"),
            todayListBlocks(now).map { it.shown() },
        )
    }

    @Test
    fun `a changed start and room replace the timetable values`() {
        val moved = thursdayCourse("CC901", "Moved", listOf("2"), room = "T1-101")
        val changes = changes(
            row(1, "room", periods = listOf("2"), values = mapOf("room" to "T9-909"), courseNo = "CC901"),
            row(2, "start", periods = listOf("2"), values = mapOf("start" to "09:40"), courseNo = "CC901"),
        )

        val before = state(listOf(moved), changes, "09:20")
        val during = state(listOf(moved), changes, "09:45")

        assertEquals("2 09:40–10:00 T9-909 time room", before.nextTodayBlock?.shown())
        assertTrue(before.nextTodayBlock!!.startChanged)
        assertEquals(listOf("2 09:40–10:00 T9-909 time room"), during.ongoingBlocks.map { it.shown() })
        assertEquals(listOf("2 09:40–10:00 T9-909 time room"), todayListBlocks(before).map { it.shown() })
    }

    @Test
    fun `the next meeting is the earliest by its changed start`() {
        val split = thursdayCourse("CC901", "Split", listOf("1", "3"), room = "T1-101")
        val changes = changes(
            row(1, "start", periods = listOf("1"), values = mapOf("start" to "12:00"), courseNo = "CC901"),
            row(2, "end", periods = listOf("1"), values = mapOf("end" to "12:50"), courseNo = "CC901"),
        )

        val morning = state(listOf(split), changes, "08:00")
        val eveningBefore = state(listOf(split), changes, "20:00", thursday.minusDays(1))

        assertEquals("3 10:20–11:10 T1-101", morning.nextTodayBlock?.shown())
        assertEquals("3 10:20–11:10 T1-101", tomorrowFirstBlock(eveningBefore)?.shown())
        assertEquals(listOf("10:20", "12:00"), todayListBlocks(morning).map { it.startTime })
    }

    @Test
    fun `the next days pass over a cancelled class for the one that still meets`() {
        val gone = thursdayCourse("CC901", "Gone", listOf("1"), room = "T1-101")
        val later = thursdayCourse("CC902", "Later", listOf("3"), room = "T1-102")
        val changes = changes(row(1, "cancelled", courseNo = "CC901"))

        val eveningBefore = state(listOf(gone, later), changes, "20:00", thursday.minusDays(1))

        assertEquals("CC902", eveningBefore.tomorrowFirstCourseNo)
        assertEquals("3 10:20–11:10 T1-102", tomorrowFirstBlock(eveningBefore)?.shown())
    }

    @Test
    fun `the changes of every day of the term reach the widgets, but notes do not`() {
        val compilers = thursdayCourse("CC900", "Compilers", listOf("2", "3", "7"), room = "T4-501")
        val changes = changes(
            row(1, "cancelled", periods = listOf("7")),
            row(2, "room", periods = listOf("2", "3"), days = listOf("2026-11-12"), values = mapOf("room" to "TR-411")),
            row(3, "start", periods = listOf("2", "3"), days = listOf("2026-11-12"), values = mapOf("start" to "09:30")),
            row(4, "note", days = listOf("2026-11-19"), values = mapOf("note_zh" to "期中考")),
        )

        fun on(day: LocalDate) = state(listOf(compilers), changes, "07:00", day)
        fun rows(day: LocalDate) = todayListBlocks(on(day)).map { it.shown() }
        fun week(day: LocalDate, periods: List<String>) = weekBlockChange(on(day), compilers, 4, periods)?.shown()

        assertEquals(listOf("2–3 09:10–11:10 T4-501", "7 14:20–15:10 T4-501 cancelled"), rows(thursday))
        assertEquals(listOf("2–3 09:30–11:10 TR-411 time room", "7 14:20–15:10 T4-501"), rows(thursday.plusWeeks(1)))
        assertEquals(listOf("2–3 09:10–11:10 T4-501", "7 14:20–15:10 T4-501"), rows(thursday.plusWeeks(2)))

        assertNull(week(thursday, listOf("2", "3")))
        assertEquals("7 14:20–15:10 T4-501 cancelled", week(thursday, listOf("7")))
        assertEquals("2–3 09:30–11:10 TR-411 time room", week(thursday.plusWeeks(1), listOf("2", "3")))
        assertNull(week(thursday.plusWeeks(1), listOf("7")))
        assertNull(week(thursday.plusWeeks(2), listOf("2", "3")))
    }

    @Test
    fun `splits a meeting changed in part into its classes but keeps it whole for the Week grid`() {
        val compilers = thursdayCourse("CC900", "Compilers", listOf("2", "3", "4"), room = "T4-501")
        val changes = changes(
            row(1, "cancelled", periods = listOf("2")),
            row(2, "room", periods = listOf("4"), values = mapOf("room" to "TR-411")),
        )

        val now = state(listOf(compilers), changes, "07:00")

        assertEquals(
            listOf("2 09:10–10:00 T4-501 cancelled", "3 10:20–11:10 T4-501", "4 11:20–12:10 TR-411 room"),
            todayListBlocks(now).map { it.shown() },
        )
        assertEquals(
            "2–4 09:10–12:10 TR-411 room",
            weekBlockChange(now, compilers, 4, listOf("2", "3", "4"))?.shown(),
        )
    }

    @Test
    fun `the Week grid's columns are the week from today, a block cancelled only when every period is`() {
        val compilers = thursdayCourse("CC900", "Compilers", listOf("2", "3"), room = "T4-501")
        val whole = changes(row(1, "cancelled", periods = listOf("2", "3")))
        val part = changes(row(1, "cancelled", periods = listOf("2")))

        fun week(changes: CourseChanges, today: LocalDate) =
            weekBlockChange(state(listOf(compilers), changes, "07:00", today), compilers, 4, listOf("2", "3"))

        // From the Wednesday before, Thursday's column is the next day.
        assertEquals("2–3 09:10–11:10 T4-501 cancelled", week(whole, thursday.minusDays(1))?.shown())
        // From the Friday after, it is the next week's Thursday, which the school left alone.
        assertNull(week(whole, thursday.plusDays(1)))
        // Cancelled in part, the block stands; its first class does not.
        assertNull(week(part, thursday))
    }

    @Test
    fun `a Week tile over two meetings shows the change of the meeting it starts with`() {
        // Periods 4 and 6 with 5 hidden draw one tile; only the first meeting ends early.
        val compilers = thursdayCourse("CC900", "Compilers", listOf("4", "6"), room = "T4-501")
        val early = changes(row(1, "end", periods = listOf("4"), values = mapOf("end" to "11:45")))
        val tile = weekBlockChange(state(listOf(compilers), early, "07:00", thursday.minusDays(1)), compilers, 4, listOf("4", "6"))
        assertTrue(tile?.timeChanged == true)
    }

    @Test
    fun `the day's widget refresh comes at a class's changed start and end`() {
        val moved = thursdayCourse("CC901", "Moved", listOf("2"))
        val changes = changes(
            row(1, "start", periods = listOf("2"), values = mapOf("start" to "09:40"), courseNo = "CC901"),
            row(2, "end", periods = listOf("2"), values = mapOf("end" to "10:30"), courseNo = "CC901"),
        )

        fun next(at: String) =
            WidgetBoundaryScheduler.nextBoundaryMinuteAfter(listOf(moved), 4, minute(at), thursday, changes)

        assertEquals(minute("09:40"), next("09:15"))
        assertEquals(minute("10:30"), next("10:00"))
        assertEquals(minute("10:00"), WidgetBoundaryScheduler.nextBoundaryMinuteAfter(listOf(moved), 4, minute("09:15")))
    }

    @Test
    fun `with no feed every widget shows the timetable as before`() {
        val twice = Course.fromSchedule(
            courseNo = "CC903",
            courseName = "Twice",
            classroom = "T1-101, T1-102",
            schedule = mapOf(4 to listOf("3", "4", "7", "8"), 5 to listOf("1")),
            classroomMap = mapOf("4-3" to "T1-101", "4-4" to "T1-101", "4-7" to "T1-102", "4-8" to "T1-102"),
        )
        val early = thursdayCourse("CC904", "Early", listOf("1"))
        val courses = listOf(twice, early)

        for (time in listOf("07:00", "08:30", "10:30", "12:10", "12:30", "15:00", "20:00")) {
            val minute = minute(time)
            val now = state(courses, CourseChanges.EMPTY, time)
            val ongoing = WidgetDataLoader.computeOngoingBlocks(courses, 4, minute)

            assertEquals(time, ongoing, now.ongoingBlocks)
            assertEquals(
                time,
                WidgetDataLoader.computeNextTodayBlock(courses, 4, minute, ongoing.map { it.course.courseNo }),
                now.nextTodayBlock,
            )
            assertEquals(time, todayListBlocks(now.copy(today = null)), todayListBlocks(now))
        }
        val eveningBefore = state(courses, CourseChanges.EMPTY, "20:00", thursday.minusDays(1))
        assertEquals(CourseBlock.containing(twice, 4, "3"), tomorrowFirstBlock(eveningBefore.copy(tomorrowFirstCourseNo = "CC903", tomorrowFirstCoursePeriodId = "3")))
        assertNull(weekBlockChange(state(courses, CourseChanges.EMPTY, "07:00"), twice, 4, listOf("3", "4")))
    }
}
