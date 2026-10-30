package org.ntust.app.tigerduck.ui.screen.home

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.at
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.changes
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.course
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.row
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture.thursday
import org.ntust.app.tigerduck.coursechanges.CourseChanges
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import java.time.LocalDate
import java.util.Date

/**
 * Home's time slider on the fixture's Thursday, CC900 meeting at periods 2 to 4 and 7 to 8, as the
 * school changed it: the classes come from the day's pieces, as the iPhone's `TimeSliderViewModel`.
 */
class TimeSliderCourseChangesTest {

    private val morning = listOf("2", "3", "4")

    @Before
    fun freezeClock() {
        AppClock.setOverride(ClockOverride(at("10:30").toEpochMilli(), frozen = true, savedAtRealMillis = 0L))
    }

    @After
    fun releaseClock() {
        AppClock.setOverride(null)
    }

    private fun slider(
        changes: CourseChanges,
        courses: List<Course> = listOf(course()),
        skipped: (Course, LocalDate) -> Boolean = { _, _ -> false },
        isQuietDay: (LocalDate) -> Boolean = { false },
    ) = TimeSliderViewModel().apply {
        configure(courses, changes = changes, skipped = skipped, isQuietDay = isQuietDay)
    }

    private fun TimeSliderViewModel.on(day: LocalDate) = timeSlots.filter { it.localDate == day }

    private fun date(text: String, day: LocalDate = thursday) = Date.from(at(text, day))

    @Test
    fun `a cancelled class stays on the slider while the rest of its meeting runs as its own class`() {
        val slider = slider(changes(row(1, "cancelled", periods = listOf("4"))))

        val today = slider.on(thursday)
        assertEquals(listOf("CC900_20261105_2", "CC900_20261105_4", "CC900_20261105_7"), today.map { it.id })
        assertEquals(listOf(false, true, false), today.map { it.isCancelled })
        assertEquals(date("11:10"), today[0].end)
        assertEquals("a week on, the meeting is whole again", 2, slider.on(thursday.plusWeeks(1)).size)
    }

    @Test
    fun `a moved class has its own times and room`() {
        val slider = slider(
            changes(
                row(1, "start", periods = morning, values = mapOf("start" to "09:40")),
                row(2, "room", periods = morning, values = mapOf("room" to "TR-313")),
            ),
        )

        val moved = slider.on(thursday).first()
        assertEquals(date("09:40") to date("12:10"), moved.start to moved.end)
        assertTrue(moved.hasChangedTime)
        assertTrue(moved.hasChangedRoom)
        assertEquals("TR-313", moved.classroom)
        val afternoon = slider.on(thursday).last()
        assertFalse(afternoon.hasChangedTime || afternoon.hasChangedRoom)
        assertEquals("T4-501", afternoon.classroom)
    }

    @Test
    fun `quiet days and skipped classes are left off, a cancelled class is not`() {
        val cancelled = changes(row(1, "cancelled"))
        val skipThursday: (Course, LocalDate) -> Boolean = { _, day -> day == thursday }

        assertEquals(2, slider(cancelled).on(thursday).size)
        assertTrue(slider(cancelled, skipped = skipThursday).on(thursday).isEmpty())
        assertTrue(slider(cancelled, isQuietDay = { it == thursday }).on(thursday).isEmpty())
        assertEquals(2, slider(cancelled, skipped = skipThursday).on(thursday.plusWeeks(1)).size)
    }

    @Test
    fun `the thumb takes the first class going on that is not cancelled`() {
        // CC901 meets alongside CC900 (衝堂), so both are in class at 10:30.
        val other = Course.fromSchedule(courseNo = "CC901", courseName = "Networks", classroom = "T4-502", schedule = mapOf(4 to listOf("3")))
        val cancelled = changes(row(1, "cancelled", periods = morning))

        val clash = slider(cancelled, courses = listOf(course(), other))
        val state = clash.currentCourseState as CourseState.InClass
        assertEquals(listOf("CC900", "CC901"), state.slots.map { it.course.courseNo })
        assertEquals("CC901", clash.thumbSlot?.course?.courseNo)
        assertNull("a cancelled class alone leaves the thumb plain", slider(cancelled).thumbSlot)
        assertEquals("CC900", slider(CourseChanges.EMPTY).thumbSlot?.course?.courseNo)
    }

    @Test
    fun `a new feed rebuilds the slider`() {
        val slider = slider(CourseChanges.EMPTY)
        assertFalse(slider.on(thursday).any { it.isCancelled })

        slider.configure(listOf(course()), changes = changes(row(1, "cancelled")))

        assertTrue(slider.on(thursday).all { it.isCancelled })
    }
}
