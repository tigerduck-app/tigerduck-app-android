package org.ntust.app.tigerduck.shared.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.changes
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.course
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.row
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.slots
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.thursday
import java.util.Locale

/**
 * The assessment and the online class, the kinds the app asks the feed for (tigerduck-app
 * `CourseChangesTests`, openspec `course-change-assessments`): an assessment marks only its own
 * day's meeting and never a cancelled one, and an online class is a room change to 線上.
 */
class CourseChangeKindsTest {

    private val morning = listOf("2", "3", "4")
    private val midterm = mapOf("note_zh" to "期中考", "note_en" to "Midterm exam")
    private val words = CourseChangeWords(cancelled = "Cancelled", assessment = "Assessment", online = "Online")

    private fun rows(changes: CourseChanges, shown: ClassSlot? = null, locale: Locale = Locale.ENGLISH) =
        CourseChangeSummary.rows(CourseMeetings.upcomingChanges(course(), thursday, changes), shown, words, locale)
            .map { it.summary }

    @Test
    fun `the feed reads both kinds, and an assessment without a name is dropped`() {
        val changes = changes(
            row(1, "assessment", periods = morning, values = midterm),
            row(2, "assessment", periods = listOf("7", "8")),
            row(3, "online", periods = listOf("7", "8")),
        )

        val thatDay = changes.changes("CC900", thursday)
        assertEquals(listOf(3, 1), thatDay.map { it.id })
        assertEquals(listOf(CourseChange.Kind.ONLINE, CourseChange.Kind.ASSESSMENT), thatDay.map { it.kind })
        assertEquals("Midterm exam", thatDay.last().note(Locale.ENGLISH))
    }

    @Test
    fun `an assessment marks only the meeting of its day`() {
        val changes = changes(row(1, "assessment", periods = morning, values = midterm))

        assertEquals(listOf(1, null), slots(changes).map { it.assessment?.id })
        assertTrue(slots(changes, thursday.plusWeeks(1)).all { it.assessment == null })
        val meeting = CourseMeetings.meeting(course(), morning, thursday, changes)!!
        assertEquals(1, meeting.assessment?.id)
        assertFalse("an assessment is not a note", meeting.notes.isNotEmpty())
        assertFalse(meeting.hasChangedRoom || meeting.hasChangedTime || meeting.isCancelled)
    }

    @Test
    fun `a cancellation wins over an assessment, and an exam keeps its new room`() {
        val changes = changes(
            row(1, "assessment", values = midterm),
            row(2, "cancelled", periods = listOf("7", "8")),
            row(3, "room", periods = morning, values = mapOf("room" to "TR-313")),
        )

        val slots = slots(changes)
        assertEquals(listOf(1, null), slots.map { it.assessment?.id })
        assertEquals(listOf(false, true), slots.map { it.isCancelled })
        assertEquals("TR-313", slots.first().room("Online"))
        assertTrue(slots.first().hasChangedRoom)
        assertEquals(listOf("TR-313 · Cancelled · Assessment · Midterm exam"), rows(changes))
    }

    @Test
    fun `an assessment or an online class over only cancelled periods leaves the held ones unmarked`() {
        val changes = changes(
            row(1, "assessment", periods = listOf("4"), values = midterm),
            row(2, "online", periods = listOf("4")),
            row(3, "cancelled", periods = listOf("4")),
            row(4, "assessment", periods = listOf("7", "8"), values = midterm),
        )

        val morningClass = CourseMeetings.meeting(course(), morning, thursday, changes)!!
        assertFalse(morningClass.isCancelled)
        assertNull(morningClass.assessment)
        assertFalse(morningClass.hasChangedRoom)
        assertEquals(4, CourseMeetings.meeting(course(), listOf("7", "8"), thursday, changes)?.assessment?.id)
    }

    @Test
    fun `an online class shows the online word as a changed room and splits its meeting`() {
        val changes = changes(row(1, "online", periods = listOf("2", "3"), values = mapOf("note_zh" to "", "note_en" to "")))

        val slots = slots(changes)
        assertEquals(listOf(listOf("2", "3"), listOf("4"), listOf("7", "8")), slots.map { it.periods })
        assertEquals(listOf("線上", "T4-501", "T4-501"), slots.map { it.room("線上") })
        assertEquals(listOf(true, false, false), slots.map { it.hasChangedRoom })
        assertEquals(listOf(1, null, null), slots.map { it.online?.id })
        val meeting = CourseMeetings.meeting(course(), morning, thursday, changes)!!
        assertEquals("線上", meeting.room("線上"))
        assertEquals("only rooms the school named", emptyList<String>(), meeting.movedRooms(online = null))
        assertEquals("an empty description is none", listOf("Online"), rows(changes))
    }

    @Test
    fun `a meeting with only an assessment or an online class is a changed class, a note alone is not`() {
        val changes = changes(
            row(1, "assessment", periods = morning, values = midterm),
            row(2, "note", periods = listOf("7"), values = mapOf("note_zh" to "小考")),
        )

        assertEquals(listOf(morning), CourseMeetings.changedClasses(course(), thursday, changes).map { it.periods })
        val online = changes(row(1, "online", periods = listOf("7", "8")))
        assertEquals(listOf(listOf("7", "8")), CourseMeetings.changedClasses(course(), thursday, online).map { it.periods })
    }

    @Test
    fun `the watch's changed classes leave out a meeting only assessed, but not one that lost a period`() {
        val changes = changes(
            row(1, "assessment", periods = morning, values = midterm),
            row(2, "cancelled", periods = listOf("7")),
            row(3, "start", periods = listOf("7", "8"), values = mapOf("start" to "15:30")),
        )

        assertEquals(listOf(listOf("8")), CourseMeetings.changedTimetableClasses(course(), thursday, changes).map { it.periods })
        assertEquals(listOf(morning, listOf("8")), CourseMeetings.changedClasses(course(), thursday, changes).map { it.periods })
    }

    @Test
    fun `the changes list names an assessment and an online class, even of the meeting the cards show`() {
        val changes = changes(
            row(1, "assessment", periods = morning, values = midterm),
            row(2, "online", periods = listOf("7", "8"), values = mapOf("note_zh" to "請自行學習 Module 5", "note_en" to "Study Module 5")),
            row(3, "online", days = listOf("2026-11-12")),
            row(4, "room", periods = morning, days = listOf("2026-11-19"), values = mapOf("room" to "TR-313")),
            row(5, "online", periods = listOf("7", "8"), days = listOf("2026-11-19")),
        )
        val shown = CourseMeetings.meeting(course(), morning, thursday, changes)

        assertEquals(
            listOf("Assessment · Midterm exam · Online · Study Module 5", "Online", "TR-313 · Online"),
            rows(changes),
        )
        assertEquals("Assessment · Midterm exam · Online · Study Module 5", rows(changes, shown).first())
        assertEquals("評量 · 期中考 · 線上 · 請自行學習 Module 5", CourseChangeSummary.rows(
            CourseMeetings.upcomingChanges(course(), thursday, changes), null,
            CourseChangeWords(cancelled = "停課", assessment = "評量", online = "線上"), Locale.TRADITIONAL_CHINESE,
        ).first().summary)
    }

    @Test
    fun `the changes list names every assessment of a day, and none of a cancelled meeting`() {
        val changes = changes(
            row(1, "assessment", periods = listOf("2"), values = midterm),
            row(2, "assessment", periods = listOf("4"), values = mapOf("note_zh" to "小考", "note_en" to "Quiz")),
            row(3, "assessment", periods = listOf("7", "8"), days = listOf("2026-11-12"), values = midterm),
            row(4, "cancelled", periods = listOf("7", "8"), days = listOf("2026-11-12")),
        )

        assertEquals(listOf("Assessment · Quiz · Assessment · Midterm exam", "Cancelled"), rows(changes))
    }
}
