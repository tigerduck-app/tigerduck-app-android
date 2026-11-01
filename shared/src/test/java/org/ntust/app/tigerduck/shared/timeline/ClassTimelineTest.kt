package org.ntust.app.tigerduck.shared.timeline

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.at
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.changes
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.course
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.row
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.thursday
import java.time.Instant
import java.util.TimeZone

/**
 * What is going on and what comes next, from the school's timetable under its changes. CC900
 * meets on Thursday 2026-11-05 in periods 2 to 4 (09:10 to 12:10) and 7 to 8 (14:20 to 16:20).
 * "Now" is read from [AppClock], as every screen reads it, so the debug override moves it.
 */
class ClassTimelineTest {

    private val morning = listOf("2", "3", "4")
    private val afternoon = listOf("7", "8")
    private val courses = listOf(course())
    private val zone = TimeZone.getDefault()

    @After
    fun tearDown() {
        AppClock.setOverride(null)
        TimeZone.setDefault(zone)
    }

    /** Sets the debug clock to [text] on [day], Taipei time, and reads "now" back from it. */
    private fun now(text: String, day: java.time.LocalDate = thursday): Instant {
        AppClock.setOverride(ClockOverride(at(text, day).toEpochMilli(), frozen = true, savedAtRealMillis = System.currentTimeMillis()))
        return AppClock.instant()
    }

    private fun ongoingIds(text: String, changes: CourseChanges = CourseChanges.EMPTY): List<String> =
        ClassTimeline.ongoing(courses, now(text), changes).map { it.id }

    // The end rule: a class is on from its start minute and over from its end minute

    @Test
    fun `a class is over at the timetable end minute and on at its start minute`() {
        assertEquals(emptyList<String>(), ongoingIds("09:09:59"))
        assertEquals(listOf("CC900_20261105_2"), ongoingIds("09:10:00"))
        assertEquals(listOf("CC900_20261105_2"), ongoingIds("12:09:59"))
        assertEquals(emptyList<String>(), ongoingIds("12:10:00"))
    }

    @Test
    fun `a class the school ends early is over at the changed end minute`() {
        val early = changes(row(1, "end", periods = morning, values = mapOf("end" to "11:45")))

        assertEquals(listOf("CC900_20261105_2"), ongoingIds("11:44:59", early))
        assertEquals(emptyList<String>(), ongoingIds("11:45:00", early))
        assertEquals(emptyList<String>(), ongoingIds("12:09:00", early))
    }

    @Test
    fun `a class the school ends late runs to the changed end minute`() {
        val late = changes(row(1, "end", periods = morning, values = mapOf("end" to "12:30")))

        assertEquals(listOf("CC900_20261105_2"), ongoingIds("12:29:59", late))
        assertEquals(emptyList<String>(), ongoingIds("12:30:00", late))
    }

    @Test
    fun `a class the school starts late is not on before the changed start`() {
        val late = changes(row(1, "start", periods = morning, values = mapOf("start" to "09:40")))

        assertEquals(emptyList<String>(), ongoingIds("09:39:59", late))
        assertEquals(listOf("CC900_20261105_2"), ongoingIds("09:40:00", late))
    }

    @Test
    fun `a cancelled class is never ongoing and another day is not touched`() {
        val cancelled = changes(row(1, "cancelled", periods = morning))

        assertEquals(emptyList<String>(), ongoingIds("10:30", cancelled))
        assertEquals(listOf("CC900_20261105_7"), ongoingIds("15:00", cancelled))
        val nextWeek = thursday.plusWeeks(1)
        assertEquals(
            listOf("CC900_20261112_2"),
            ClassTimeline.ongoing(courses, now("10:30", nextWeek), cancelled).map { it.id },
        )
    }

    @Test
    fun `only the running piece of a split meeting is ongoing`() {
        val split = changes(row(1, "room", periods = listOf("4"), values = mapOf("room" to "TR-313")))

        assertEquals(listOf(listOf("2", "3")), ClassTimeline.ongoing(courses, now("10:30"), split).map { it.periods })
        assertEquals(listOf(listOf("4")), ClassTimeline.ongoing(courses, now("11:30"), split).map { it.periods })
    }

    @Test
    fun `the school day is Taipei's whatever zone the phone is in`() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))

        assertEquals(listOf("CC900_20261105_2"), ongoingIds("09:10:00"))
        assertEquals(emptyList<String>(), ongoingIds("12:10:00"))
    }

    // What is next

    @Test
    fun `the next class starts after now, so one starting this minute is on and not next`() {
        assertEquals("CC900_20261105_2", ClassTimeline.next(courses, now("08:00"), CourseChanges.EMPTY, daysAhead = 7)?.id)
        assertEquals("CC900_20261105_7", ClassTimeline.next(courses, now("09:10:00"), CourseChanges.EMPTY, daysAhead = 7)?.id)
        assertEquals("CC900_20261105_7", ClassTimeline.next(courses, now("12:10:00"), CourseChanges.EMPTY, daysAhead = 0)?.id)
        assertNull(ClassTimeline.next(courses, now("14:20:00"), CourseChanges.EMPTY, daysAhead = 0))
    }

    @Test
    fun `the next class is placed by its changed start and a cancelled one is passed over`() {
        val moved = changes(
            row(1, "start", periods = afternoon, values = mapOf("start" to "14:50")),
            row(2, "cancelled", periods = morning),
        )

        val next = ClassTimeline.next(courses, now("08:00"), moved, daysAhead = 0)
        assertEquals("CC900_20261105_7", next?.id)
        assertEquals(at("14:50"), next?.startsAt)
    }

    @Test
    fun `the lookahead reaches daysAhead days past today and no further`() {
        val after = now("17:00")

        assertNull(ClassTimeline.next(courses, after, CourseChanges.EMPTY, daysAhead = 6))
        assertEquals(thursday.plusWeeks(1), ClassTimeline.next(courses, after, CourseChanges.EMPTY, daysAhead = 7)?.date)
    }

    @Test
    fun `a week of cancelled classes leaves the next one to the week after`() {
        val cancelledToday = changes(row(1, "cancelled", periods = afternoon))
        val cancelledNextWeek = changes(
            row(1, "cancelled", periods = afternoon),
            row(2, "cancelled", days = listOf("2026-11-12")),
        )
        val after = now("08:00")

        assertEquals("CC900_20261105_2", ClassTimeline.next(courses, after, cancelledToday, daysAhead = 14)?.id)
        assertEquals(
            "CC900_20261119_2",
            ClassTimeline.next(courses, now("17:00"), cancelledNextWeek, daysAhead = 14)?.id,
        )
        assertNull(ClassTimeline.next(courses, now("17:00"), cancelledNextWeek, daysAhead = 13))
    }

    // Meetings and boundaries

    @Test
    fun `meetings are the day's slots with the cancelled ones included`() {
        val cancelled = changes(row(1, "cancelled", periods = afternoon))

        val slots = ClassTimeline.meetings(courses, thursday, cancelled)

        assertEquals(CourseMeetings.buildSlots(courses, thursday, cancelled), slots)
        assertEquals(listOf(false, true), slots.map { it.isCancelled })
    }

    @Test
    fun `the boundaries are the sorted distinct starts and ends of the slots not cancelled`() {
        val twin = Course.fromSchedule(courseNo = "CC901", courseName = "Linkers", classroom = "T4-502", schedule = mapOf(4 to morning))
        val cancelled = changes(row(1, "cancelled", periods = afternoon))

        assertEquals(
            listOf(at("09:10"), at("12:10")),
            ClassTimeline.boundaries(courses + twin, thursday, cancelled),
        )
        assertEquals(
            listOf(at("09:10"), at("12:10"), at("14:20"), at("16:20")),
            ClassTimeline.boundaries(courses, thursday, CourseChanges.EMPTY),
        )
        assertTrue(ClassTimeline.boundaries(courses, thursday.plusDays(1), CourseChanges.EMPTY).isEmpty())
    }

    @Test
    fun `a split meeting gives one boundary per piece and a changed end moves its boundary`() {
        val split = changes(
            row(1, "room", periods = listOf("4"), values = mapOf("room" to "TR-313")),
            row(2, "end", periods = morning, values = mapOf("end" to "11:45")),
        )

        assertEquals(
            listOf(at("09:10"), at("11:10"), at("11:20"), at("11:45"), at("14:20"), at("16:20")),
            ClassTimeline.boundaries(courses, thursday, split),
        )
    }
}
