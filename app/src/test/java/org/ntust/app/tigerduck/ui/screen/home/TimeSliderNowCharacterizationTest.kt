package org.ntust.app.tigerduck.ui.screen.home

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.at
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.changes
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.course
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.row
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.thursday
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import java.time.LocalDate
import java.util.Date

/**
 * What Home's time slider says is going on, pinned before its window moved onto `ClassTimeline`.
 * The real [TimeSliderViewModel.courseState] runs on the fixture's Thursday 2026-11-05 (CC900 at
 * periods 2 to 4, 09:10 to 12:10, and 7 to 8, 14:20 to 16:20), the clock frozen there so no test
 * reads the real date. Each state is written as text: a slot is `id start..end`, a cancelled one
 * ends in `!`.
 */
class TimeSliderNowCharacterizationTest {

    @Before
    fun freezeClock() {
        AppClock.setOverride(ClockOverride(at("10:30").toEpochMilli(), frozen = true, savedAtRealMillis = 0L))
    }

    @After
    fun releaseClock() {
        AppClock.setOverride(null)
    }

    private fun slider(
        changes: CourseChanges = CourseChanges.EMPTY,
        courses: List<Course> = listOf(course()),
        skipped: (Course, LocalDate) -> Boolean = { _, _ -> false },
        isQuietDay: (LocalDate) -> Boolean = { false },
    ) = TimeSliderViewModel().apply { configure(courses, changes, skipped, isQuietDay) }

    private fun shown(date: Date): String =
        date.toInstant().atZone(AppConstants.TAIPEI_ZONE).toLocalDateTime().toString().substring(5)

    private fun text(slot: CourseTimeSlot?): String =
        if (slot == null) "-" else "${slot.id} ${shown(slot.start)}..${shown(slot.end)}${if (slot.isCancelled) "!" else ""}"

    private fun state(slider: TimeSliderViewModel, time: String, day: LocalDate = thursday): String =
        when (val s = slider.courseState(Date.from(at(time, day)))) {
            is CourseState.InClass -> "in " + s.slots.joinToString(" | ") { text(it) }
            is CourseState.Between -> "between ${text(s.previous)} / ${text(s.next)}"
            is CourseState.BeforeFirst -> "before ${text(s.next)}"
            is CourseState.AfterLast -> "after ${text(s.previous)}"
        }

    private val morning = "CC900_20261105_2 11-05T09:10..11-05T12:10"
    private val afternoon = "CC900_20261105_7 11-05T14:20..11-05T16:20"
    private val lastWeek = "CC900_20261029_7 10-29T14:20..10-29T16:20"
    private val nextWeek = "CC900_20261112_2 11-12T09:10..11-12T12:10"

    private val endsAt1145 = changes(row(1, "end", periods = listOf("2", "3", "4"), values = mapOf("end" to "11:45")))
    private val morningEarly = "CC900_20261105_2 11-05T09:10..11-05T11:45"

    @Test
    fun `the four moments on the timetable`() {
        val s = slider()
        assertEquals("in $morning", state(s, "11:19"))
        assertEquals("in $morning", state(s, "11:20"))
        assertEquals("in $morning", state(s, "12:09"))
        assertEquals("between $morning / $afternoon", state(s, "12:10"))
    }

    @Test
    fun `the four moments when the school ended the morning at 11-45`() {
        val s = slider(endsAt1145)
        assertEquals("in $morningEarly", state(s, "11:19"))
        assertEquals("in $morningEarly", state(s, "11:20"))
        assertEquals("between $morningEarly / $afternoon", state(s, "12:09"))
        assertEquals("between $morningEarly / $afternoon", state(s, "12:10"))
    }

    @Test
    fun `a class is in at its start minute and the gap between its periods is part of it`() {
        val s = slider()
        assertEquals("between $lastWeek / $morning", state(s, "09:09"))
        assertEquals("in $morning", state(s, "09:10"))
        assertEquals("contiguous periods are one class across the break", "in $morning", state(s, "10:10"))
        assertEquals("in $morning", state(s, "11:15"))
    }

    @Test
    fun `a cancelled class is shown and counts as the one going on`() {
        val s = slider(changes(row(1, "cancelled", periods = listOf("3"))))
        val first = "CC900_20261105_2 11-05T09:10..11-05T10:00"
        val cancelled = "CC900_20261105_3 11-05T10:20..11-05T11:10!"
        val last = "CC900_20261105_4 11-05T11:20..11-05T12:10"
        assertEquals("in $first", state(s, "09:59"))
        assertEquals("between $first / $cancelled", state(s, "10:10"))
        assertEquals("in $cancelled", state(s, "10:20"))
        assertEquals("in $cancelled", state(s, "11:09"))
        assertEquals("between $cancelled / $last", state(s, "11:10"))
        assertEquals("in $last", state(s, "11:20"))
        assertTrue(s.timeSlots.filter { it.isCancelled }.size == s.timeSlots.count { it.id.contains("_3") })
        assertEquals(null, slider(changes(row(1, "cancelled"))).thumbSlot)
    }

    @Test
    fun `a class cancelled whole is still the state at its time`() {
        val s = slider(changes(row(1, "cancelled")))
        assertEquals("in CC900_20261105_2 11-05T09:10..11-05T12:10!", state(s, "10:00"))
        assertEquals("in CC900_20261105_7 11-05T14:20..11-05T16:20!", state(s, "15:00"))
    }

    @Test
    fun `classes that overlap are all in, by start, and each leaves at its own end`() {
        val other = Course.fromSchedule(courseNo = "CC901", courseName = "Networks", classroom = "T4-502", schedule = mapOf(4 to listOf("3")))
        val s = slider(courses = listOf(course(), other))
        val clash = "CC901_20261105_3 11-05T10:20..11-05T11:10"
        assertEquals("in $morning", state(s, "10:19"))
        assertEquals("in $morning | $clash", state(s, "10:20"))
        assertEquals("in $morning | $clash", state(s, "11:09"))
        assertEquals("in $morning", state(s, "11:10"))
        assertEquals(2, s.slotLayouts.getValue("CC900_20261105_2").laneCount)
        assertEquals(listOf(0, 1), listOf(s.slotLayouts.getValue("CC900_20261105_2").lane, s.slotLayouts.getValue("CC901_20261105_3").lane))
    }

    @Test
    fun `before the first class and after the last are the ends of the window`() {
        val s = slider()
        val first = "CC900_20261008_2 10-08T09:10..10-08T12:10"
        val last = "CC900_20261203_7 12-03T14:20..12-03T16:20"
        assertEquals("before $first", state(s, "08:00", LocalDate.of(2026, 10, 8)))
        assertEquals("after $last", state(s, "16:20", LocalDate.of(2026, 12, 3)))
        assertEquals("after $last", state(s, "20:00", LocalDate.of(2026, 12, 4)))
    }

    @Test
    fun `between classes, across midnight and on a day with none`() {
        val s = slider()
        assertEquals("between $morning / $afternoon", state(s, "12:30"))
        assertEquals("between $afternoon / $nextWeek", state(s, "23:30"))
        assertEquals("between $afternoon / $nextWeek", state(s, "00:30", thursday.plusDays(1)))
        assertEquals("between $afternoon / $nextWeek", state(s, "12:00", thursday.plusDays(1)))
        assertEquals("between $lastWeek / $morning", state(s, "06:00"))
    }

    @Test
    fun `a quiet day and a skipped class leave their time to the days either side`() {
        assertEquals(
            "between $lastWeek / $nextWeek",
            state(slider(isQuietDay = { it == thursday }), "10:30"),
        )
        assertEquals(
            "between $lastWeek / $nextWeek",
            state(slider(skipped = { _, day -> day == thursday }), "10:30"),
        )
    }

    @Test
    fun `a split meeting is a class per piece`() {
        val s = slider(changes(row(1, "room", periods = listOf("3"), values = mapOf("room" to "TR-313"))))
        val one = "CC900_20261105_2 11-05T09:10..11-05T10:00"
        val two = "CC900_20261105_3 11-05T10:20..11-05T11:10"
        val three = "CC900_20261105_4 11-05T11:20..11-05T12:10"
        assertEquals("in $one", state(s, "09:30"))
        assertEquals("between $one / $two", state(s, "10:10"))
        assertEquals("in $two", state(s, "10:30"))
        assertEquals("between $two / $three", state(s, "11:15"))
        assertEquals("in $three", state(s, "12:09"))
    }

    @Test
    fun `a moved class is in from its changed start`() {
        val s = slider(changes(row(1, "start", periods = listOf("2", "3", "4"), values = mapOf("start" to "09:40"))))
        val moved = "CC900_20261105_2 11-05T09:40..11-05T12:10"
        assertEquals("between $lastWeek / $moved", state(s, "09:39"))
        assertEquals("in $moved", state(s, "09:40"))
        assertEquals("between $moved / $afternoon", state(s, "12:10"))
    }

    @Test
    fun `a period with no known time drops its meeting and leaves the rest`() {
        fun only(periods: List<String>) = slider(courses = listOf(course(periods)))

        assertFalse(only(listOf("Z")).hasCourses)
        assertTrue(only(listOf("Z")).timeSlots.isEmpty())
        assertEquals("a course with only unknown periods is no state but between nothing", "between - / -", state(only(listOf("Z")), "10:30"))

        val mixed = only(listOf("3", "Z"))
        assertEquals(listOf("CC900_20261105_3"), mixed.timeSlots.filter { it.localDate == thursday }.map { it.id })
        assertEquals("in CC900_20261105_3 11-05T10:20..11-05T11:10", state(mixed, "10:30"))

        val edge = only(listOf("Z", "3", "4"))
        assertEquals("the unknown period is a run of its own", "in CC900_20261105_3 11-05T10:20..11-05T12:10", state(edge, "11:15"))
    }
}
