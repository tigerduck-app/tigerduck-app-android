package org.ntust.app.tigerduck.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.academic.SemesterTerm
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.time.TaipeiClock
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.changes
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.row
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.thursday
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import java.time.LocalDate
import java.time.LocalTime

/**
 * Which classes the redesigned widgets show and how, on made-up Thursday courses
 * ([WidgetDayRows], [WidgetDataLoader.computeUpNext], [WidgetDataLoader.termWeek]): over, on with
 * its progress, or to come; the rows that fit; the class hours' redraws; the class after the one
 * Next Class shows; and the term's week.
 */
class WidgetDayRowsTest {

    // Thursday: period 2 (09:10–10:00), 3–4 (10:20–12:10), 6 (13:20–14:10), 8 (15:30–16:20).
    private val early = thu("ZZ1001", "2")
    private val morning = thu("ZZ1002", "3", "4")
    private val afternoon = thu("ZZ1003", "6")
    private val late = thu("ZZ1004", "8")
    private val courses = listOf(early, morning, afternoon, late)

    private fun thu(no: String, vararg periods: String) = Course.fromSchedule(no, no, schedule = mapOf(4 to periods.toList()))

    private fun minute(hm: String) = LocalTime.parse(hm).let { it.hour * 60 + it.minute }

    private fun blocks(changes: CourseChanges = CourseChanges.EMPTY) =
        courses.flatMap { CourseBlock.on(it, thursday, changes) }.sortedBy { it.startTime }

    private fun shown(rows: List<WidgetClassRow>) = rows.joinToString { row ->
        "${row.block.course.courseNo} " + when {
            row.isPast -> "past"
            row.progress != null -> "on %.2f".format(row.progress)
            else -> "upcoming"
        }
    }

    @Test
    fun `a class is over from its end minute and on from its start minute, with its progress`() {
        val rows = WidgetDayRows.rows(blocks(), minute("11:15"))

        assertEquals("ZZ1001 past, ZZ1002 on 0.50, ZZ1003 upcoming, ZZ1004 upcoming", shown(rows))
        assertEquals("ZZ1001 past, ZZ1002 past, ZZ1003 upcoming, ZZ1004 upcoming", shown(WidgetDayRows.rows(blocks(), minute("12:10"))))
        assertEquals("ZZ1003, ZZ1004", WidgetDayRows.upcoming(WidgetDayRows.rows(blocks(), minute("12:10"))).joinToString { it.block.course.courseNo })
    }

    @Test
    fun `a cancelled class is never on`() {
        val cancelled = changes(row(1, "cancelled", periods = listOf("3", "4"), courseNo = "ZZ1002"))

        val rows = WidgetDayRows.rows(blocks(cancelled), minute("11:15"))

        assertEquals("ZZ1001 past, ZZ1002 upcoming, ZZ1003 upcoming, ZZ1004 upcoming", shown(rows))
    }

    @Test
    fun `rows that do not all fit start at the first class not over, and end with the day's last`() {
        val atNoon = WidgetDayRows.rows(blocks(), minute("12:30"))
        val inTheEvening = WidgetDayRows.rows(blocks(), minute("18:00"))

        assertEquals(listOf("ZZ1003", "ZZ1004"), WidgetDayRows.window(atNoon, 2).map { it.block.course.courseNo })
        assertEquals(listOf("ZZ1002", "ZZ1003", "ZZ1004"), WidgetDayRows.window(atNoon, 3).map { it.block.course.courseNo })
        assertEquals(listOf("ZZ1003", "ZZ1004"), WidgetDayRows.window(inTheEvening, 2).map { it.block.course.courseNo })
        assertEquals(atNoon, WidgetDayRows.window(atNoon, 9))
    }

    @Test
    fun `the class hours' redraws run every five minutes from the first class's hour to the hour after the last`() {
        val rows = { at: String -> WidgetDayRows.rows(blocks(), minute(at)) }

        assertEquals(minute("09:00"), WidgetDayRows.nextTick(rows("07:00"), minute("07:00")))
        assertEquals(minute("09:05"), WidgetDayRows.nextTick(rows("09:00"), minute("09:00")))
        assertEquals(minute("11:20"), WidgetDayRows.nextTick(rows("11:17"), minute("11:17")))
        assertEquals(minute("17:00"), WidgetDayRows.nextTick(rows("16:58"), minute("16:58")))
        assertNull(WidgetDayRows.nextTick(rows("17:00"), minute("17:00")))
        assertNull(WidgetDayRows.nextTick(emptyList(), minute("09:00")))
    }

    @Test
    fun `the scheduler's tick reads the day's classes as the school changed them`() {
        // The last class is moved to 18:00–18:50, so the class hours run to 19:00.
        val moved = changes(
            row(1, "start", periods = listOf("8"), values = mapOf("start" to "18:00"), courseNo = "ZZ1004"),
            row(2, "end", periods = listOf("8"), values = mapOf("end" to "18:50"), courseNo = "ZZ1004"),
        )

        assertEquals(minute("17:05"), WidgetBoundaryScheduler.nextTickMinute(courses, thursday, minute("17:00"), moved))
        assertNull(WidgetBoundaryScheduler.nextTickMinute(courses, thursday, minute("17:00"), CourseChanges.EMPTY))
    }

    private fun at(hm: String) = thursday.atTime(LocalTime.parse(hm)).atZone(TaipeiClock.zone).toInstant()

    @Test
    fun `the class after the next one is the first other not over at its start`() {
        val next = CourseBlock.allOf(afternoon, 4).single()

        val upNext = WidgetDataLoader.computeUpNext(courses, at("12:30"), next, CourseChanges.EMPTY)

        assertEquals("ZZ1004", upNext?.course?.courseNo)
    }

    @Test
    fun `the class after one going on is the first other not over now, and none after the last`() {
        val on = CourseBlock.allOf(morning, 4).single()

        assertEquals("ZZ1003", WidgetDataLoader.computeUpNext(courses, at("11:15"), on, CourseChanges.EMPTY)?.course?.courseNo)
        assertNull(WidgetDataLoader.computeUpNext(courses, at("15:40"), CourseBlock.allOf(late, 4).single(), CourseChanges.EMPTY))
        assertNull(WidgetDataLoader.computeUpNext(courses, at("15:40"), null, CourseChanges.EMPTY))
    }

    @Test
    fun `a cancelled class is never the one after`() {
        val cancelled = changes(row(1, "cancelled", periods = listOf("6"), courseNo = "ZZ1003"))
        val on = CourseBlock.allOf(morning, 4).single()

        assertEquals("ZZ1004", WidgetDataLoader.computeUpNext(courses, at("11:15"), on, cancelled)?.course?.courseNo)
    }

    @Test
    fun `the term's week counts from the Monday of the week it starts in`() {
        // Starts on a Wednesday; its first week runs from the Monday before.
        val term = SemesterTerm("1151", LocalDate.parse("2026-09-09"), LocalDate.parse("2027-01-08"))

        assertEquals(1, WidgetDataLoader.termWeek(term, LocalDate.parse("2026-09-09")))
        assertEquals(1, WidgetDataLoader.termWeek(term, LocalDate.parse("2026-09-13")))
        assertEquals(2, WidgetDataLoader.termWeek(term, LocalDate.parse("2026-09-14")))
        assertEquals(9, WidgetDataLoader.termWeek(term, thursday))
        assertNull(WidgetDataLoader.termWeek(term, LocalDate.parse("2026-09-08")))
        assertNull(WidgetDataLoader.termWeek(term, LocalDate.parse("2027-01-09")))
    }
}
