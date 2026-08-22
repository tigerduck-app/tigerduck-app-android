package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Period ids are not sortable as strings ("10" < "2", and the evening slots
 * are lettered), and periods are not evenly spaced in wall-clock time. Most
 * of these exist because of one or the other.
 */
class ClassTableSelectionTest {

    private fun course(
        no: String,
        schedule: Map<Int, List<String>>,
        classroom: String = "",
        classroomMap: Map<String, String> = emptyMap(),
    ) = Course.fromSchedule(
        courseNo = no,
        courseName = "Course $no",
        classroom = classroom,
        schedule = schedule,
        classroomMap = classroomMap,
    )

    private fun calendar(dayOfWeek: Int, hour: Int, minute: Int): Calendar =
        GregorianCalendar(TimeZone.getTimeZone("Asia/Taipei")).apply {
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }

    // --- dayTimeFrom -------------------------------------------------------

    @Test
    fun `calendar weekdays become monday-first indices`() {
        assertEquals(1, ClassTableSelection.dayTimeFrom(calendar(Calendar.MONDAY, 0, 0)).weekday)
        assertEquals(5, ClassTableSelection.dayTimeFrom(calendar(Calendar.FRIDAY, 0, 0)).weekday)
        assertEquals(7, ClassTableSelection.dayTimeFrom(calendar(Calendar.SUNDAY, 0, 0)).weekday)
    }

    @Test
    fun `minute of day counts from midnight`() {
        assertEquals(
            14 * 60 + 25,
            ClassTableSelection.dayTimeFrom(calendar(Calendar.MONDAY, 14, 25)).minuteOfDay,
        )
    }

    // --- coursesOn ---------------------------------------------------------

    @Test
    fun `today's courses are ordered by when they actually start`() {
        // "10" sorts before "2" as a string; chronologically it is last.
        val late = course("LATE", mapOf(1 to listOf("10")))
        val early = course("EARLY", mapOf(1 to listOf("2")))
        val ordered = ClassTableSelection.coursesOn(listOf(late, early), weekday = 1)
        assertEquals(listOf("EARLY", "LATE"), ordered.map { it.courseNo })
    }

    @Test
    fun `a course spanning several periods is ordered by its first`() {
        val spanning = course("SPAN", mapOf(1 to listOf("6", "3")))
        val single = course("ONE", mapOf(1 to listOf("4")))
        val ordered = ClassTableSelection.coursesOn(listOf(single, spanning), weekday = 1)
        assertEquals(listOf("SPAN", "ONE"), ordered.map { it.courseNo })
    }

    @Test
    fun `evening lettered periods sort after numbered ones`() {
        val evening = course("EVE", mapOf(1 to listOf("A")))
        val morning = course("AM", mapOf(1 to listOf("1")))
        val ordered = ClassTableSelection.coursesOn(listOf(evening, morning), weekday = 1)
        assertEquals(listOf("AM", "EVE"), ordered.map { it.courseNo })
    }

    @Test
    fun `courses on other days are excluded`() {
        val monday = course("MON", mapOf(1 to listOf("1")))
        val tuesday = course("TUE", mapOf(2 to listOf("1")))
        assertEquals(
            listOf("MON"),
            ClassTableSelection.coursesOn(listOf(monday, tuesday), weekday = 1).map { it.courseNo },
        )
    }

    // --- timeRange ---------------------------------------------------------

    @Test
    fun `time range spans first period start to last period end`() {
        val c = course("A", mapOf(1 to listOf("1", "2")))
        assertEquals("08:10 - 10:00", ClassTableSelection.timeRange(c, weekday = 1))
    }

    @Test
    fun `time range uses chronological order, not list order`() {
        val c = course("A", mapOf(1 to listOf("2", "1")))
        assertEquals("08:10 - 10:00", ClassTableSelection.timeRange(c, weekday = 1))
    }

    @Test
    fun `a single period still yields a range`() {
        val c = course("A", mapOf(1 to listOf("3")))
        assertEquals("10:20 - 11:10", ClassTableSelection.timeRange(c, weekday = 1))
    }

    @Test
    fun `no time range for a day the course does not meet`() {
        val c = course("A", mapOf(1 to listOf("1")))
        assertNull(ClassTableSelection.timeRange(c, weekday = 3))
    }

    // --- classroom ---------------------------------------------------------

    @Test
    fun `classroom narrows to the tapped weekday and period`() {
        val c = course(
            "A",
            schedule = mapOf(1 to listOf("1"), 3 to listOf("1")),
            classroom = "TR-101, TR-202",
            classroomMap = mapOf("1-1" to "TR-101", "3-1" to "TR-202"),
        )
        assertEquals("TR-202", ClassTableSelection.classroom(c, weekday = 3, periodId = "1"))
    }

    @Test
    fun `with no weekday the classroom is the deduplicated union`() {
        val c = course("A", mapOf(1 to listOf("1")), classroom = "TR-101, TR-101")
        assertEquals("TR-101", ClassTableSelection.classroom(c, weekday = null, periodId = null))
    }

    // --- isFinishedAt ------------------------------------------------------

    @Test
    fun `a class is finished once its last period has ended`() {
        val c = course("A", mapOf(1 to listOf("1", "2")))  // ends 10:00
        val after = ClassTableSelection.DayTime(weekday = 1, minuteOfDay = 10 * 60 + 1)
        assertTrue(ClassTableSelection.isFinishedAt(c, after))
    }

    @Test
    fun `a class is not finished during its last period`() {
        val c = course("A", mapOf(1 to listOf("1", "2")))
        val during = ClassTableSelection.DayTime(weekday = 1, minuteOfDay = 9 * 60 + 30)
        assertFalse(ClassTableSelection.isFinishedAt(c, during))
    }

    @Test
    fun `exactly at the end minute is not yet finished`() {
        val c = course("A", mapOf(1 to listOf("1")))  // ends 09:00
        val atEnd = ClassTableSelection.DayTime(weekday = 1, minuteOfDay = 9 * 60)
        assertFalse(ClassTableSelection.isFinishedAt(c, atEnd))
    }

    @Test
    fun `a course that does not meet today is never finished`() {
        // False rather than true on purpose: this greys out a course, so
        // guessing would hide a class that is still running.
        val c = course("A", mapOf(1 to listOf("1")))
        val tuesdayEvening = ClassTableSelection.DayTime(weekday = 2, minuteOfDay = 23 * 60)
        assertFalse(ClassTableSelection.isFinishedAt(c, tuesdayEvening))
    }

    @Test
    fun `finished is judged by the last period, not the first`() {
        val c = course("A", mapOf(1 to listOf("1", "9")))  // 08:10 .. 17:20
        val afterFirstOnly = ClassTableSelection.DayTime(weekday = 1, minuteOfDay = 12 * 60)
        assertFalse(ClassTableSelection.isFinishedAt(c, afterFirstOnly))
    }
}
