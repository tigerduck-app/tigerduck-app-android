package org.ntust.app.tigerduck.liveactivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.data.model.Course
import java.time.LocalDate
import java.time.LocalTime
import java.util.Date

/**
 * Covers the term gate in `buildTodaySlots`.
 *
 * The timetable is cached weeks before 開學 because 選課 opens ahead of the
 * term, so the schedule alone is not evidence that classes have started.
 * Without the gate the Live Update would render 上課中 / 即將上課 — and
 * `todaySlotsAfter` would arm boundary alarms — for days before 開學.
 *
 * `todaySlotsAfter` is exercised rather than `resolve`, since the latter needs
 * a Context-backed `LiveActivityPreferences`. Both run through the same
 * gated `buildTodaySlots`.
 */
class LiveActivityResolverTermGateTest {

    private val resolver = LiveActivityResolver()

    /** Meets Monday, period 3 (10:20–11:10). */
    private val mondayCourse = Course.fromSchedule(
        courseNo = "AT1234",
        courseName = "微積分",
        schedule = mapOf(1 to listOf("3")),
    )

    private fun at(date: LocalDate, time: LocalTime): Date =
        Date.from(date.atTime(time).atZone(AppConstants.TAIPEI_ZONE).toInstant())

    @Test
    fun `a Monday before 開學 surfaces no slots`() {
        // 2026-08-31 is a Monday one week before 開學 (2026-09-07, also a
        // Monday). Same course, same weekday, same time of day — the only
        // difference from the test below is which side of 開學 it falls on.
        val slots = resolver.todaySlotsAfter(
            listOf(mondayCourse),
            at(LocalDate.of(2026, 8, 31), LocalTime.of(8, 0)),
        )

        assertTrue("no class notifications before 開學", slots.isEmpty())
    }

    @Test
    fun `the same Monday inside the term surfaces its slot`() {
        val slots = resolver.todaySlotsAfter(
            listOf(mondayCourse),
            at(LocalDate.of(2026, 9, 7), LocalTime.of(8, 0)),
        )

        assertEquals(1, slots.size)
        assertEquals("AT1234", slots.first().course.courseNo)
    }

    @Test
    fun `a Monday after the last day of classes surfaces no slots`() {
        // 2026-12-28, the first Monday after 2026-12-25.
        val slots = resolver.todaySlotsAfter(
            listOf(mondayCourse),
            at(LocalDate.of(2026, 12, 28), LocalTime.of(8, 0)),
        )

        assertTrue(slots.isEmpty())
    }

    @Test
    fun `every Monday in the run-up to 開學 stays empty`() {
        for (weeksBefore in 1..4) {
            val date = LocalDate.of(2026, 9, 7).minusWeeks(weeksBefore.toLong())
            val slots = resolver.todaySlotsAfter(
                listOf(mondayCourse),
                at(date, LocalTime.of(8, 0)),
            )
            assertTrue("$date is before 開學 and must surface no slots", slots.isEmpty())
        }
    }
}
