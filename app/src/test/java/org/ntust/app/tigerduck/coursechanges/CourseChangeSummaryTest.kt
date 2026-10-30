package org.ntust.app.tigerduck.coursechanges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.changes
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.course
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.feed
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.row
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.slots
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.thursday
import java.util.Locale

/**
 * The course detail's 課程異動 list and the meeting its cards show (tigerduck-app
 * `CourseChangesTests`, `CourseDetailSheet.changeRows` and `shownMeeting`).
 */
class CourseChangeSummaryTest {

    private val morning = listOf("2", "3", "4")

    private fun rows(days: List<ChangedDay>, shown: ClassSlot?, locale: Locale = Locale.ENGLISH) =
        CourseChangeSummary.rows(days, shown, cancelled = "停課", locale = locale)

    @Test
    fun `the changes list shows upcoming days earliest first and at most eight`() {
        val thursdays = (-1L..9L).map { thursday.plusWeeks(it) }
        val changes = CourseChanges.decode(
            feed(
                thursdays.reversed().mapIndexed { index, day -> row(index, "cancelled", days = listOf(day.toString())) } +
                    row(99, "room", days = listOf("2026-11-06"), values = mapOf("room" to "TR-313")),
            ),
        )

        val days = CourseMeetings.upcomingChanges(course(), thursday, changes)
        assertEquals("today and later, without the Friday the course does not meet", thursdays.drop(1), days.map { it.day })
        assertTrue(days.all { day -> day.meetings.size == 2 && day.meetings.all { it.isCancelled } })
        val rows = rows(days, shown = null)
        assertEquals(thursdays.subList(1, 9), rows.map { it.day })
        assertTrue(rows.all { it.summary == "停課" })
    }

    @Test
    fun `the changes list leaves the shown meeting's room and time to the cards`() {
        val changes = changes(
            row(1, "room", periods = morning, values = mapOf("room" to "TR-313")),
            row(2, "start", periods = listOf("7", "8"), days = listOf("2026-11-12"), values = mapOf("start" to "14:40")),
            row(3, "note", periods = listOf("7"), days = listOf("2026-11-12"), values = mapOf("note_zh" to "Quiz")),
        )
        val days = CourseMeetings.upcomingChanges(course(), thursday, changes)
        val today = CourseMeetings.meeting(course(), morning, thursday, changes)!!
        val nextWeek = days.last().meetings.first()

        assertEquals(listOf("TR-313", "14:40 - 16:20 · Quiz"), rows(days, shown = null).map { it.summary })
        assertEquals(listOf("14:40 - 16:20 · Quiz"), rows(days, shown = today).map { it.summary })
        assertEquals(listOf("TR-313", "Quiz"), rows(days, shown = nextWeek).map { it.summary })
    }

    @Test
    fun `a tapped class shows its own changes while a timetable block shows its meeting`() {
        val changes = changes(
            row(1, "cancelled", periods = listOf("2")),
            row(2, "room", periods = listOf("4"), values = mapOf("room" to "TR-313")),
        )
        val slots = slots(changes)
        assertEquals(listOf(listOf("2"), listOf("3"), listOf("4"), listOf("7", "8")), slots.map { it.periods })
        fun shown(slot: ClassSlot?, periods: List<String>? = null) =
            CourseChangeSummary.shownMeeting(course(), slot, periods, thursday, changes)

        assertEquals(true, shown(slots[0])?.isCancelled)
        assertNull(shown(slots[1]))
        assertEquals("TR-313", shown(slots[2])?.room)
        val block = shown(null, morning)!!
        assertEquals(morning, block.periods)
        assertFalse(block.isCancelled)
        assertEquals("TR-313", block.room)
        val days = CourseMeetings.upcomingChanges(course(), thursday, changes)
        assertTrue(rows(days, shown(slots[2])).isEmpty())
        assertEquals(listOf("TR-313"), rows(days, shown(slots[0])).map { it.summary })
    }

    @Test
    fun `a note on several meetings shows once, in the app's language, and a repeated value once`() {
        val changes = changes(
            row(1, "room", values = mapOf("room" to "TR-313")),
            row(2, "note", values = mapOf("note_zh" to "帶筆電", "note_en" to "Bring a laptop")),
        )
        val days = CourseMeetings.upcomingChanges(course(), thursday, changes)
        assertEquals(2, days.single().meetings.size)

        assertEquals(listOf("TR-313 · Bring a laptop"), rows(days, shown = null).map { it.summary })
        assertEquals(listOf("TR-313 · 帶筆電"), rows(days, shown = null, locale = Locale.TRADITIONAL_CHINESE).map { it.summary })
    }

    @Test
    fun `the cancelled word is the caller's`() {
        val days = CourseMeetings.upcomingChanges(course(), thursday, changes(row(1, "cancelled", periods = listOf("7", "8"))))

        assertEquals(listOf("Cancelled"), CourseChangeSummary.rows(days, null, cancelled = "Cancelled", locale = Locale.ENGLISH).map { it.summary })
    }

    @Test
    fun `without a slot the cards show the day's meeting, and nothing without a date or a change`() {
        val changes = changes(row(1, "room", periods = morning, values = mapOf("room" to "TR-313")))

        assertEquals(listOf("2", "3", "4", "7", "8"), CourseChangeSummary.shownMeeting(course(), null, null, thursday, changes)?.periods)
        assertNull("no date", CourseChangeSummary.shownMeeting(course(), null, morning, null, changes))
        assertNull("no change", CourseChangeSummary.shownMeeting(course(), null, listOf("7", "8"), thursday, changes))
        assertNull("a day the course does not meet", CourseChangeSummary.shownMeeting(course(), null, null, thursday.plusDays(1), changes))
    }
}
