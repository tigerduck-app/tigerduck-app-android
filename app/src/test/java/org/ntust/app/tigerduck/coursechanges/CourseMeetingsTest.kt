package org.ntust.app.tigerduck.coursechanges

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.at
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.changes
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.course
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.feed
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.row
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.slots
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.thursday
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.time
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.timetable
import org.ntust.app.tigerduck.shared.Course
import java.time.LocalDate

/**
 * A course's meetings on one day as the school changed them, split into the classes that run
 * (tigerduck-app `CourseChangesTests` and `CourseTimeSlotTests`). Course numbers are made up.
 */
class CourseMeetingsTest {

    private val morning = listOf("2", "3", "4")

    // Cancellations and rooms

    @Test
    fun `a cancellation without periods cancels every meeting that day only`() {
        val changes = changes(row(1, "cancelled"))

        assertEquals(listOf(true, true), slots(changes).map { it.isCancelled })
        assertEquals(listOf(false, false), slots(changes, thursday.plusWeeks(1)).map { it.isCancelled })
    }

    @Test
    fun `one meeting moves room while the other is cancelled`() {
        val changes = changes(
            row(1, "room", periods = morning, values = mapOf("room" to "TR-313")),
            row(2, "cancelled", periods = listOf("7", "8")),
        )

        val slots = slots(changes)
        assertEquals(listOf(morning, listOf("7", "8")), slots.map { it.periods })
        assertEquals(listOf(false, true), slots.map { it.isCancelled })
        assertEquals(listOf("TR-313", "T4-501"), slots.map { it.room })
    }

    @Test
    fun `meeting slots leave out a cancelled meeting`() {
        val changes = changes(row(1, "cancelled", periods = morning))

        val slots = CourseMeetings.meetingSlots(listOf(course()), thursday, changes, isClassDay = { true })
        assertEquals(listOf(listOf("7", "8")), slots.map { it.periods })
    }

    // Fragments

    @Test
    fun `a room change over part of a moved meeting splits it without overlap`() {
        val changes = changes(
            row(1, "start", periods = morning, values = mapOf("start" to "10:00")),
            row(2, "end", periods = morning, values = mapOf("end" to "13:00")),
            row(3, "room", periods = listOf("4"), values = mapOf("room" to "TR-313")),
        )

        val slots = slots(changes)
        assertEquals(listOf(listOf("2", "3"), listOf("4"), listOf("7", "8")), slots.map { it.periods })
        assertEquals(listOf(time("10:00"), time("11:20"), time("14:20")), slots.map { it.start })
        assertEquals(listOf(time("11:10"), time("13:00"), time("16:20")), slots.map { it.end })
        assertEquals(listOf("T4-501", "TR-313", "T4-501"), slots.map { it.room })
    }

    private enum class MatrixTime { NONE, START, END, BOTH, INVALID_START }

    private enum class MatrixPartial { NONE, ROOM, CANCELLATION, BOTH }

    private data class Piece(val periods: List<String>, val cancelled: Boolean, val room: String)

    /**
     * A three-period meeting under every mix of a whole-meeting time change and a change to some of
     * its periods, as fragments, as one meeting and as the classes that meet.
     */
    @Test
    fun `a meeting combines its time and its partial changes`() {
        for (timeCase in MatrixTime.entries) for (partial in MatrixPartial.entries) {
            val case = "$timeCase with $partial"
            val rows = mutableListOf<JSONObject>()
            if (timeCase == MatrixTime.START || timeCase == MatrixTime.BOTH) rows += row(1, "start", values = mapOf("start" to "09:40"))
            if (timeCase == MatrixTime.END || timeCase == MatrixTime.BOTH) rows += row(2, "end", values = mapOf("end" to "11:50"))
            if (timeCase == MatrixTime.INVALID_START) rows += row(1, "start", values = mapOf("start" to "13:00"))
            if (partial == MatrixPartial.ROOM || partial == MatrixPartial.BOTH) rows += row(3, "room", periods = listOf("4"), values = mapOf("room" to "TR-313"))
            if (partial == MatrixPartial.CANCELLATION || partial == MatrixPartial.BOTH) rows += row(4, "cancelled", periods = listOf("2"))
            val changes = CourseChanges.decode(feed(rows))
            val course = course(morning)
            val start = if (timeCase == MatrixTime.START || timeCase == MatrixTime.BOTH) time("09:40") else time("09:10")
            val end = if (timeCase == MatrixTime.END || timeCase == MatrixTime.BOTH) time("11:50") else time("12:10")
            val pieces = when (partial) {
                MatrixPartial.NONE -> listOf(Piece(morning, false, "T4-501"))
                MatrixPartial.ROOM -> listOf(Piece(listOf("2", "3"), false, "T4-501"), Piece(listOf("4"), false, "TR-313"))
                MatrixPartial.CANCELLATION -> listOf(Piece(listOf("2"), true, "T4-501"), Piece(listOf("3", "4"), false, "T4-501"))
                MatrixPartial.BOTH -> listOf(Piece(listOf("2"), true, "T4-501"), Piece(listOf("3"), false, "T4-501"), Piece(listOf("4"), false, "TR-313"))
            }

            val fragments = CourseMeetings.buildSlots(listOf(course), thursday, changes)
            assertEquals(case, pieces.map { it.periods }, fragments.map { it.periods })
            assertEquals(case, pieces.map { it.cancelled }, fragments.map { it.isCancelled })
            assertEquals(case, pieces.map { it.room }, fragments.map { it.room })
            assertEquals(case, start, fragments.first().start)
            assertEquals(case, end, fragments.last().end)
            for ((left, right) in fragments.zipWithNext()) {
                assertEquals(case, timetable(left.periods.last(), end = true), left.end)
                assertEquals(case, timetable(right.periods.first(), end = false), right.start)
            }

            val meeting = CourseMeetings.meeting(course, morning, thursday, changes)!!
            assertEquals(case, morning, meeting.periods)
            assertEquals(case, start, meeting.start)
            assertEquals(case, end, meeting.end)
            assertEquals(case, if (start == time("09:10")) null else time("09:10"), meeting.originalStart)
            assertEquals(case, if (end == time("12:10")) null else time("12:10"), meeting.originalEnd)
            assertFalse(case, meeting.isCancelled)
            assertEquals(case, if (partial == MatrixPartial.ROOM || partial == MatrixPartial.BOTH) "TR-313" else "T4-501", meeting.room)

            val meets = CourseMeetings.meetingSlots(listOf(course), thursday, changes, isClassDay = { true })
            assertEquals(case, pieces.filter { !it.cancelled }.map { it.periods }, meets.map { it.periods })
        }
    }

    @Test
    fun `a fragment the new times leave empty is dropped`() {
        val changes = changes(
            row(1, "start", periods = morning, values = mapOf("start" to "10:30")),
            row(2, "cancelled", periods = listOf("2")),
        )

        val slots = slots(changes)
        assertEquals(listOf(listOf("3", "4"), listOf("7", "8")), slots.map { it.periods })
        assertEquals(time("10:30"), slots.first().start)
        assertEquals(time("12:10"), slots.first().end)
        val meeting = CourseMeetings.meeting(course(), listOf("2"), thursday, changes)!!
        assertEquals(morning, meeting.periods)
        assertFalse(meeting.isCancelled)
        assertEquals(time("10:30"), meeting.start)
        assertEquals(time("09:10"), meeting.originalStart)
    }

    @Test
    fun `a dropped outer fragment leaves the meeting times on the next one`() {
        val changes = changes(
            row(1, "start", periods = morning, values = mapOf("start" to "10:05")),
            row(2, "end", periods = listOf("7", "8"), values = mapOf("end" to "15:15")),
            row(3, "room", periods = listOf("3", "4", "7"), values = mapOf("room" to "TR-313")),
        )

        val slots = slots(changes)
        assertEquals(listOf(listOf("3", "4"), listOf("7")), slots.map { it.periods })
        assertEquals(listOf(time("10:05"), time("14:20")), slots.map { it.start })
        assertEquals(listOf(time("12:10"), time("15:15")), slots.map { it.end })
        assertEquals(listOf(time("10:20"), null), slots.map { it.originalStart })
        assertEquals(listOf(null, time("15:10")), slots.map { it.originalEnd })
        val moved = CourseMeetings.meeting(course(), listOf("3"), thursday, changes)!!
        assertEquals(time("10:05") to time("12:10"), moved.start to moved.end)
        val shortened = CourseMeetings.meeting(course(), listOf("7"), thursday, changes)!!
        assertEquals(time("14:20") to time("15:15"), shortened.start to shortened.end)
    }

    @Test
    fun `a time change that would end before it starts is dropped but the rest stays`() {
        val changes = changes(
            row(1, "start", periods = morning, values = mapOf("start" to "15:00")),
            row(2, "room", periods = morning, values = mapOf("room" to "TR-313")),
            row(3, "end", periods = listOf("7", "8"), values = mapOf("end" to "13:00")),
            row(4, "cancelled", periods = listOf("7", "8")),
        )

        val slots = slots(changes)
        assertEquals(listOf(time("09:10"), time("14:20")), slots.map { it.start })
        assertEquals(listOf(time("12:10"), time("16:20")), slots.map { it.end })
        assertEquals(listOf(false, false), slots.map { it.hasChangedTime })
        assertEquals(listOf("TR-313", "T4-501"), slots.map { it.room })
        assertEquals(listOf(false, true), slots.map { it.isCancelled })
    }

    @Test
    fun `a new start and end move their meeting and keep the timetable times`() {
        val changes = changes(
            row(1, "start", periods = morning, values = mapOf("start" to "09:40")),
            row(2, "end", periods = morning, values = mapOf("end" to "12:40")),
        )

        val slots = slots(changes)
        assertEquals(time("09:40"), slots[0].start)
        assertEquals(time("12:40"), slots[0].end)
        assertEquals(time("09:10"), slots[0].originalStart)
        assertEquals(time("12:10"), slots[0].originalEnd)
        assertTrue(slots[0].hasChangedTime)
        assertEquals(time("14:20"), slots[1].start)
        assertNull(slots[1].originalStart)
        assertNull(slots[1].originalEnd)
        assertFalse(slots[1].hasChangedTime)
    }

    @Test
    fun `a change over part of a meeting splits it`() {
        val changes = changes(row(1, "cancelled", periods = listOf("4")))

        val slots = slots(changes)
        assertEquals(listOf("CC900_20261105_2", "CC900_20261105_4", "CC900_20261105_7"), slots.map { it.id })
        assertEquals(listOf(false, true, false), slots.map { it.isCancelled })
        assertEquals(time("11:10"), slots[0].end)
    }

    @Test
    fun `a note rides on the meetings it touches without splitting them`() {
        val changes = changes(row(1, "note", periods = listOf("3"), values = mapOf("note_zh" to "帶筆電", "note_en" to "Bring a laptop")))

        val slots = slots(changes)
        assertEquals(listOf(morning, listOf("7", "8")), slots.map { it.periods })
        assertEquals(listOf("Bring a laptop"), slots[0].notes.map { it.noteEn })
        assertTrue(slots[1].notes.isEmpty())
        assertFalse(slots[0].isCancelled)
    }

    // The merged meeting

    @Test
    fun `a meeting is cancelled only when every period of it is`() {
        val partly = changes(
            row(1, "cancelled", periods = listOf("4")),
            row(2, "room", periods = listOf("3"), values = mapOf("room" to "TR-313")),
        )
        val whole = changes(
            row(1, "cancelled"),
            row(2, "start", periods = listOf("7", "8"), values = mapOf("start" to "14:40")),
        )

        val kept = CourseMeetings.meeting(course(), morning, thursday, partly)!!
        assertFalse(kept.isCancelled)
        assertEquals(morning, kept.periods)
        assertEquals(time("09:10") to time("12:10"), kept.start to kept.end)
        assertTrue(kept.hasChangedRoom)
        assertEquals("TR-313", kept.room)
        val gone = CourseMeetings.meeting(course(), morning, thursday, whole)!!
        assertTrue(gone.isCancelled)
        assertFalse(gone.hasChangedRoom)
        assertFalse(gone.hasChangedTime)
        val afternoon = CourseMeetings.meeting(course(), listOf("7", "8"), thursday, whole)!!
        assertTrue(afternoon.hasChangedTime)
        assertEquals(time("14:40") to time("16:20"), afternoon.start to afternoon.end)
        assertEquals(listOf(morning), CourseMeetings.changedMeetings(course(), thursday, partly).map { it.periods })
    }

    @Test
    fun `a merged meeting spans its parts and keeps the timetable order`() {
        val later = changes(row(1, "end", periods = listOf("3"), values = mapOf("end" to "13:00")))
        val earlier = changes(row(1, "start", periods = listOf("3"), values = mapOf("start" to "08:30")))

        val longer = CourseMeetings.meeting(course(), morning, thursday, later)!!
        assertEquals(morning, longer.periods)
        assertEquals(time("09:10") to time("13:00"), longer.start to longer.end)
        assertEquals(null to time("12:10"), longer.originalStart to longer.originalEnd)
        val sooner = CourseMeetings.meeting(course(), morning, thursday, earlier)!!
        assertEquals(morning, sooner.periods)
        assertEquals(time("08:30") to time("12:10"), sooner.start to sooner.end)
        assertEquals(time("09:10") to null, sooner.originalStart to sooner.originalEnd)
    }

    @Test
    fun `a meeting over periods the course does not have that day is null`() {
        assertNull(CourseMeetings.meeting(course(), listOf("9"), thursday, CourseChanges.EMPTY))
        assertNull(CourseMeetings.meeting(course(), morning, thursday.plusDays(1), CourseChanges.EMPTY))
    }

    @Test
    fun `the changed classes are the pieces of each meeting with more than a note`() {
        val changes = changes(
            row(1, "cancelled", periods = listOf("4")),
            row(2, "note", periods = listOf("7"), values = mapOf("note_zh" to "小考")),
        )

        assertEquals(listOf(listOf("2", "3"), listOf("4")), CourseMeetings.changedClasses(course(), thursday, changes).map { it.periods })
        assertEquals(
            "a partial cancellation leaves its meeting unchanged; a note does not",
            listOf(listOf("7", "8")),
            CourseMeetings.changedMeetings(course(), thursday, changes).map { it.periods },
        )
        assertTrue(CourseMeetings.changedClasses(course(), thursday.plusWeeks(1), changes).isEmpty())
        assertTrue(CourseMeetings.changedMeetings(course(), thursday.plusWeeks(1), changes).isEmpty())
    }

    // Skips and endings

    @Test
    fun `a cancelled slot id reads as skipped`() {
        val changes = changes(row(1, "cancelled", periods = listOf("7", "8")))
        val courses = listOf(course())

        assertTrue(CourseMeetings.isSkipped("CC900_20261105_7", courses, changes))
        assertFalse(CourseMeetings.isSkipped("CC900_20261105_2", courses, changes))
        assertFalse(CourseMeetings.isSkipped("CC900_20261112_7", courses, changes))
    }

    @Test
    fun `a class is over once the cancelled later piece of its meeting begins`() {
        val changes = changes(row(1, "cancelled", periods = listOf("4")))
        val id = slots(CourseChanges.EMPTY).first().id
        val courses = listOf(course())

        assertTrue(CourseMeetings.hasEnded(id, courses, at("11:30"), changes))
        assertFalse(CourseMeetings.hasEnded(id, courses, at("10:30"), changes))
        assertFalse(CourseMeetings.hasEnded(id, courses, at("11:30"), CourseChanges.EMPTY))
        val split = changes(row(2, "room", periods = listOf("4"), values = mapOf("room" to "TR-313")))
        assertTrue("its own piece ends at 11:10 (#905)", CourseMeetings.hasEnded(id, courses, at("11:30"), split))
    }

    @Test
    fun `a class whose first piece a change dropped ends with the rest of its meeting`() {
        val changes = changes(
            row(1, "start", periods = morning, values = mapOf("start" to "10:30")),
            row(2, "end", periods = morning, values = mapOf("end" to "11:00")),
            row(3, "room", periods = listOf("2"), values = mapOf("room" to "TR-313")),
        )
        val id = slots(CourseChanges.EMPTY).first().id
        val courses = listOf(course())
        assertEquals(listOf("3", "4"), slots(changes).first().periods)

        assertTrue("#904", CourseMeetings.hasEnded(id, courses, at("11:30"), changes))
        assertFalse(CourseMeetings.hasEnded(id, courses, at("10:45"), changes))
    }

    @Test
    fun `an id that names no class has not ended`() {
        val courses = listOf(course())

        assertFalse(CourseMeetings.hasEnded("901", courses, at("23:00"), CourseChanges.EMPTY))
        assertFalse(CourseMeetings.hasEnded("CC900_2026-11-05_2", courses, at("23:00"), CourseChanges.EMPTY))
        assertFalse(CourseMeetings.hasEnded("CC901_20261105_2", courses, at("23:00"), CourseChanges.EMPTY))
        assertTrue(CourseMeetings.hasEnded("CC900_20261105_2", courses, at("23:00"), CourseChanges.EMPTY))
    }

    // What is running now

    @Test
    fun `the current class is the changed one and a cancelled class is not running`() {
        val changes = changes(
            row(1, "room", periods = morning, values = mapOf("room" to "TR-313")),
            row(2, "cancelled", periods = listOf("7", "8")),
        )

        val now = CourseMeetings.ongoingClasses(listOf(course()), at("10:30"), changes).first()
        assertEquals(morning to "TR-313", now.periods to now.room)
        val partly = changes(row(1, "room", periods = listOf("4"), values = mapOf("room" to "TR-313")))
        val piece = CourseMeetings.ongoingClasses(listOf(course()), at("10:30"), partly).first()
        assertEquals(listOf("2", "3"), piece.periods)
        assertNull(
            "the running piece has no change of its own, so the detail shows none",
            CourseChangeSummary.shownMeeting(course(), piece, piece.periods, piece.date, partly),
        )
        assertTrue(CourseMeetings.ongoingClasses(listOf(course()), at("14:30"), changes).isEmpty())
        assertEquals(1, CourseMeetings.ongoingClasses(listOf(course()), at("14:30"), CourseChanges.EMPTY).size)
    }

    @Test
    fun `a class runs from its changed start up to but not including its changed end`() {
        val changes = changes(
            row(1, "start", periods = morning, values = mapOf("start" to "09:40")),
            row(2, "end", periods = morning, values = mapOf("end" to "11:50")),
        )
        val courses = listOf(course())

        assertTrue(CourseMeetings.ongoingClasses(courses, at("09:30"), changes).isEmpty())
        assertEquals(1, CourseMeetings.ongoingClasses(courses, at("09:40"), changes).size)
        assertTrue(CourseMeetings.ongoingClasses(courses, at("11:50"), changes).isEmpty())
        assertTrue("another day's changes do not apply", CourseMeetings.ongoingClasses(courses, at("09:30", thursday.plusWeeks(1)), changes).isNotEmpty())
    }

    // Slots and their ids (tigerduck-app `CourseTimeSlotTests`)

    private val tuesday: LocalDate = LocalDate.of(2026, 9, 22)

    private fun tuesdayCourse(courseNo: String, periods: List<String>) =
        Course.fromSchedule(courseNo = courseNo, courseName = courseNo, schedule = mapOf(2 to periods))

    /** As the app keeps skips: course number to its skipped `yyyy-MM-dd` days. */
    private fun skips(vararg entries: Pair<String, List<String>>): (Course, LocalDate) -> Boolean {
        val map = entries.toMap()
        return { course, day -> map[course.courseNo]?.contains(day.toString()) == true }
    }

    @Test
    fun `meeting slots hold the day's blocks`() {
        val slots = CourseMeetings.meetingSlots(listOf(tuesdayCourse("CC901", listOf("3", "4", "7"))), tuesday, CourseChanges.EMPTY, isClassDay = { true })

        assertEquals(listOf("CC901_20260922_3", "CC901_20260922_7"), slots.map { it.id })
        assertEquals(listOf(listOf("3", "4"), listOf("7")), slots.map { it.periods })
    }

    @Test
    fun `meeting slots are empty on a day classes do not meet`() {
        val slots = CourseMeetings.meetingSlots(listOf(tuesdayCourse("CC901", listOf("3", "4"))), tuesday, CourseChanges.EMPTY, isClassDay = { false })

        assertTrue(slots.isEmpty())
    }

    @Test
    fun `meeting slots leave out a course skipped that day`() {
        val slots = CourseMeetings.meetingSlots(
            listOf(tuesdayCourse("CC901", listOf("3")), tuesdayCourse("CC902", listOf("6"))),
            tuesday,
            CourseChanges.EMPTY,
            isClassDay = { true },
            skipped = skips("CC901" to listOf("2026-09-22")),
        )

        assertEquals(listOf("CC902"), slots.map { it.course.courseNo })
    }

    @Test
    fun `a slot id tells whether its class is skipped`() {
        val courses = listOf(tuesdayCourse("CC901", listOf("3")), tuesdayCourse("custom_CC904", listOf("6")), tuesdayCourse("CC902", listOf("6")))
        val skipped = skips("CC901" to listOf("2026-09-22"), "custom_CC904" to listOf("2026-09-22"))

        fun isSkipped(id: String) = CourseMeetings.isSkipped(id, courses, CourseChanges.EMPTY, skipped)
        assertTrue(isSkipped("CC901_20260922_3"))
        assertTrue("a course number with an underscore reads from the right", isSkipped("custom_CC904_20260922_6"))
        assertFalse(isSkipped("CC901_20260929_3"))
        assertFalse(isSkipped("CC902_20260922_6"))
        assertFalse(isSkipped("CC903_20260922_3"))
        assertFalse(isSkipped("901"))
        assertFalse(isSkipped("CC901_2026-09-22_3"))
    }

    @Test
    fun `a slot id is the one build slots writes`() {
        val course = tuesdayCourse("CC901", listOf("3"))
        val slot = CourseMeetings.buildSlots(listOf(course), tuesday, CourseChanges.EMPTY).first()

        assertTrue(CourseMeetings.isSkipped(slot.id, listOf(course), CourseChanges.EMPTY, skips("CC901" to listOf("2026-09-22"))))
    }

    @Test
    fun `a meeting moved to two rooms over its periods names both, in period order`() {
        val changes = CourseChangeFixture.changes(
            CourseChangeFixture.row(1, "room", periods = listOf("4"), values = mapOf("room" to "TR-315")),
            CourseChangeFixture.row(2, "room", periods = listOf("2"), values = mapOf("room" to "TR-214")),
        )
        val merged = CourseMeetings.meeting(CourseChangeFixture.course(), listOf("2", "3", "4"), CourseChangeFixture.thursday, changes)!!
        assertEquals("TR-214, TR-315", merged.room)
        val fragments = CourseChangeFixture.slots(changes).filter { "7" !in it.periods }
        assertEquals(listOf("TR-214", "T4-501", "TR-315"), fragments.map { it.room })
    }
}
