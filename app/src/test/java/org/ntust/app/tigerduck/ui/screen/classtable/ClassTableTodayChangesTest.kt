package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.changes
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.course
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.row
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.thursday
import org.ntust.app.tigerduck.coursechanges.CourseChanges
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.computeOngoingCourses
import java.time.LocalDate
import java.time.LocalTime

/**
 * The class table's today strip on a day the school changed: the Current class card and the cards
 * beside it. Ported from the iPhone's `CourseBlockSelectionTests` and `CourseChangesTests`.
 */
class ClassTableTodayChangesTest {

    private val morning = listOf("2", "3", "4")

    private fun minute(text: String): Int = LocalTime.parse(text).let { it.hour * 60 + it.minute }

    private fun ongoing(changes: CourseChanges, at: String, courses: List<Course> = listOf(course()), day: LocalDate = thursday) =
        ClassTableSelection.ongoingClasses(courses, day, minute(at), changes)

    private fun card(changes: CourseChanges, at: String) =
        ClassTableSelection.todayCard(course(), thursday, minute(at), changes)

    /** Monday 3-4 in TR-313 and 7-8 in IB-503. */
    private val twiceOnMonday = Course.fromSchedule(
        courseNo = "CC917",
        courseName = "Test",
        classroom = "TR-313, IB-503",
        schedule = mapOf(1 to listOf("3", "4", "7", "8")),
        classroomMap = mapOf("1-3" to "TR-313", "1-4" to "TR-313", "1-7" to "IB-503", "1-8" to "IB-503"),
    )

    @Test
    fun `the current class card shows the running block's room`() {
        val now = ongoing(CourseChanges.EMPTY, "10:30", listOf(twiceOnMonday), LocalDate.of(2026, 11, 2)).single()

        assertEquals(listOf("3", "4"), now.periods)
        assertEquals("TR-313", now.room)
    }

    @Test
    fun `the current class is the changed one and a cancelled class is not running`() {
        val changes = changes(
            row(1, "room", periods = morning, values = mapOf("room" to "TR-313")),
            row(2, "cancelled", periods = listOf("7", "8")),
        )

        val now = ongoing(changes, "10:30").single()
        assertEquals(morning to "TR-313", now.periods to now.room)
        assertTrue(now.hasChangedRoom)
        assertTrue(ongoing(changes, "14:30").isEmpty())
        assertEquals(1, ongoing(CourseChanges.EMPTY, "14:30").size)
    }

    @Test
    fun `a class runs through its changed times, its last minute included as the strip has it`() {
        val changes = changes(
            row(1, "start", periods = morning, values = mapOf("start" to "09:40")),
            row(2, "end", periods = morning, values = mapOf("end" to "11:50")),
        )

        assertTrue(ongoing(changes, "09:30").isEmpty())
        assertEquals(LocalTime.of(9, 40) to LocalTime.of(11, 50), ongoing(changes, "09:40").single().let { it.start to it.end })
        assertEquals(1, ongoing(changes, "11:50").size)
        assertTrue(ongoing(changes, "11:51").isEmpty())
    }

    @Test
    fun `a running piece of a meeting partly cancelled is the class going on`() {
        val changes = changes(row(1, "cancelled", periods = listOf("4")))

        assertEquals(listOf("2", "3"), ongoing(changes, "10:30").single().periods)
        assertTrue("the cancelled piece is not running", ongoing(changes, "11:30").isEmpty())
    }

    @Test
    fun `with no changes the running classes are the timetable's`() {
        val twiceOnThursday = Course.fromSchedule(courseNo = "CC918", courseName = "Test", classroom = "TR-313", schedule = mapOf(4 to listOf("3", "4", "7", "8")))
        val courses = listOf(course(), twiceOnThursday)
        for (at in listOf("08:00", "09:10", "10:30", "12:10", "12:11", "14:20", "15:10", "16:20", "20:00")) {
            val expected = computeOngoingCourses(courses, 4, minute(at))
                .map { listOf(it.course.courseNo, it.periods, it.startMinute, it.endMinute) }
                .sortedBy { it[0] as String }
            val actual = ongoing(CourseChanges.EMPTY, at, courses)
                .map { listOf(it.course.courseNo, it.periods, it.start.hour * 60 + it.start.minute, it.end.hour * 60 + it.end.minute) }
                .sortedBy { it[0] as String }
            assertEquals(at, expected, actual)
        }
    }

    @Test
    fun `a today card shows the day's changed room and time`() {
        val changes = changes(
            row(1, "end", periods = morning, values = mapOf("end" to "11:40")),
            row(2, "room", periods = morning, values = mapOf("room" to "TR-313")),
        )

        val card = card(changes, "08:00")
        assertEquals(morning, card.periods)
        val meeting = requireNotNull(card.meeting)
        assertEquals("TR-313", meeting.room)
        assertEquals(LocalTime.of(9, 10) to LocalTime.of(11, 40), meeting.start to meeting.end)
        assertEquals("the morning ends at its changed end", listOf("7", "8"), card(changes, "11:45").periods)
        assertNull("the afternoon has no change", card(changes, "11:45").meeting)
    }

    @Test
    fun `a today card for a cancelled class is cancelled, and partly cancelled is not`() {
        assertTrue(requireNotNull(card(changes(row(1, "cancelled", periods = morning)), "08:00").meeting).isCancelled)
        val partly = card(changes(row(1, "cancelled", periods = listOf("4"))), "08:00").meeting
        assertFalse(partly?.isCancelled ?: false)
    }

    @Test
    fun `a class moved later is not over at its timetable end`() {
        val changes = changes(
            row(1, "start", periods = listOf("7", "8"), values = mapOf("start" to "18:30")),
            row(2, "end", periods = listOf("7", "8"), values = mapOf("end" to "20:00")),
        )

        assertFalse(card(changes, "17:00").isFinished)
        assertTrue(card(changes, "20:01").isFinished)
    }

    @Test
    fun `with no changes a today card is the timetable's`() {
        for (at in listOf("08:00", "11:00", "12:30", "16:20", "16:21")) {
            val card = card(CourseChanges.EMPTY, at)
            val dayTime = ClassTableSelection.DayTime(4, minute(at))
            assertEquals(at, ClassTableSelection.carouselBlock(course(), 4, minute(at)), card.periods)
            assertEquals(at, ClassTableSelection.isFinishedAt(course(), dayTime), card.isFinished)
            assertNull(at, card.meeting)
        }
    }
}
