package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.Holiday
import org.ntust.app.tigerduck.academic.QuietDays
import org.ntust.app.tigerduck.academic.SemesterTerm
import org.ntust.app.tigerduck.coursechanges.CourseChangeFixture
import org.ntust.app.tigerduck.coursechanges.CourseChanges
import org.ntust.app.tigerduck.shared.Course
import java.time.LocalDate
import java.time.LocalTime
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

    // --- the course detail -------------------------------------------------

    @Test
    fun `the course detail shows the course as it is now, renamed from the detail included`() {
        val picked = course("CS1", emptyMap())
        val renamed = picked.copy(customCourseName = "Algorithms I")

        assertEquals("Algorithms I", ClassTableSelection.detailCourse(picked, listOf(renamed), emptyList(), fromAnotherTerm = false)?.customCourseName)
        assertEquals(
            "a carousel course of the live term is found there",
            "Algorithms I",
            ClassTableSelection.detailCourse(picked, emptyList(), listOf(renamed), fromAnotherTerm = true)?.customCourseName,
        )
        assertEquals("one no longer listed keeps what was picked", picked, ClassTableSelection.detailCourse(picked, emptyList(), emptyList(), fromAnotherTerm = false))
        assertNull("nothing selected, nothing to show", ClassTableSelection.detailCourse(null, listOf(renamed), emptyList(), fromAnotherTerm = false))
    }

    @Test
    fun `a course of one number in two terms is shown from the term it was picked in`() {
        val shown = course("CS1", emptyMap()).copy(instructor = "Old term")
        val live = course("CS1", emptyMap()).copy(instructor = "This term")

        assertEquals(
            "picked from the today strip while the grid shows an older term",
            live,
            ClassTableSelection.detailCourse(live, listOf(shown), listOf(live), fromAnotherTerm = true),
        )
        assertEquals(
            "picked from the grid",
            shown,
            ClassTableSelection.detailCourse(shown, listOf(shown), listOf(live), fromAnotherTerm = false),
        )
    }

    @Test
    fun `a rename reaches the live term's copy, even of a course the grid's term does not list`() {
        val live = course("CS9", emptyMap())

        assertEquals(
            live,
            ClassTableSelection.renameTarget("CS9", courses = emptyList(), liveCourses = listOf(live), fromAnotherTerm = false),
        )
        assertEquals(
            listOf(live.copy(customCourseName = "Compilers")),
            ClassTableSelection.renamed(listOf(live), "CS9", "Compilers"),
        )
        assertNull(ClassTableSelection.renameTarget("CS0", listOf(live), listOf(live), fromAnotherTerm = false))
    }

    @Test
    fun `a rename from the today strip targets the live term's course, not the grid's of the same number`() {
        val shown = course("CS1", emptyMap()).copy(moodleIdNumber = "1142CS1")
        val live = course("CS1", emptyMap()).copy(moodleIdNumber = "1151CS1")

        assertEquals(
            "picked from the today strip",
            live,
            ClassTableSelection.renameTarget("CS1", listOf(shown), listOf(live), fromAnotherTerm = true),
        )
        assertEquals(
            "picked from the grid",
            shown,
            ClassTableSelection.renameTarget("CS1", listOf(shown), listOf(live), fromAnotherTerm = false),
        )
    }

    @Test
    fun `a course picked from someone else's class table is theirs`() {
        assertTrue(ClassTableSelection.isSomeoneElses(showingSomeoneElse = true, fromLiveTerm = false))
    }

    @Test
    fun `a course picked from the live term is the user's own whatever the class table shows`() {
        assertFalse(ClassTableSelection.isSomeoneElses(showingSomeoneElse = true, fromLiveTerm = true))
        assertFalse(ClassTableSelection.isSomeoneElses(showingSomeoneElse = false, fromLiveTerm = true))
    }

    @Test
    fun `a course picked from the user's own class table is theirs`() {
        assertFalse(ClassTableSelection.isSomeoneElses(showingSomeoneElse = false, fromLiveTerm = false))
    }

    @Test
    fun `the detail's title is the custom name, else the full name, else the course name`() {
        val plain = course("CS1", emptyMap())
        assertEquals("Mine", ClassTableSelection.detailTitle(plain.copy(customCourseName = "Mine"), fullName = "Full"))
        assertEquals("Full", ClassTableSelection.detailTitle(plain, fullName = "Full"))
        assertEquals(plain.displayName, ClassTableSelection.detailTitle(plain, fullName = null))
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

    // --- todayCourses ------------------------------------------------------

    private val autumnTerm = AcademicCalendar(
        revision = 1L,
        terms = listOf(
            SemesterTerm("1151", LocalDate.parse("2026-09-07"), LocalDate.parse("2026-12-25")),
        ),
        holidays = listOf(
            Holiday(1, "教師節", "Teachers' Day", LocalDate.parse("2026-09-28"), LocalDate.parse("2026-09-28")),
        ),
    )

    private val timetable = listOf(
        course("LATE", mapOf(1 to listOf("10"))),
        course("TUE", mapOf(2 to listOf("1"))),
        course("EARLY", mapOf(1 to listOf("2"))),
    )

    private fun carousel(today: String, optedIn: Set<Int> = emptySet()): List<String> =
        ClassTableSelection.todayCourses(
            timetable, LocalDate.parse(today), QuietDays(autumnTerm, optedIn),
        ).map { it.courseNo }

    @Test
    fun `the carousel lists today's classes in the order they are taught`() {
        assertEquals(listOf("EARLY", "LATE"), carousel("2026-10-05"))
    }

    @Test
    fun `the carousel lists no class on a holiday the user has not opted back into`() {
        assertEquals(emptyList<String>(), carousel("2026-09-28"))
    }

    @Test
    fun `the carousel lists a holiday's classes once the user opts back in`() {
        assertEquals(listOf("EARLY", "LATE"), carousel("2026-09-28", optedIn = setOf(1)))
    }

    @Test
    fun `the carousel lists no class outside the term`() {
        assertEquals(emptyList<String>(), carousel("2027-01-04"))
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
    fun `exactly at the end minute the class is finished`() {
        val c = course("A", mapOf(1 to listOf("1")))  // ends 09:00
        val atEnd = ClassTableSelection.DayTime(weekday = 1, minuteOfDay = 9 * 60)
        assertTrue(ClassTableSelection.isFinishedAt(c, atEnd))
        assertFalse(ClassTableSelection.isFinishedAt(c, ClassTableSelection.DayTime(1, 9 * 60 - 1)))
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

    // ---- the today carousel's card for a course meeting twice ----

    private val twiceOnMonday = Course.fromSchedule(
        "CS3005301", "Data Structures",
        schedule = mapOf(1 to listOf("1", "2", "6", "7")),
    )

    @Test
    fun `a carousel card stands for the next block, then the last one`() {
        assertEquals(listOf("1", "2"), ClassTableSelection.carouselBlock(twiceOnMonday, 1, 7 * 60))
        // After the morning block (08:10–10:00): the afternoon one.
        assertEquals(listOf("6", "7"), ClassTableSelection.carouselBlock(twiceOnMonday, 1, 11 * 60))
        // After both: still the afternoon one, finished.
        assertEquals(listOf("6", "7"), ClassTableSelection.carouselBlock(twiceOnMonday, 1, 18 * 60))
        assertNull(ClassTableSelection.carouselBlock(twiceOnMonday, 2, 7 * 60))
    }

    // ---- a class is over from its end minute (D2: start <= now < end) ----
    // CC900 meets on Thursday 2026-11-05, periods 2 to 4 (09:10-12:10) and 7 to 8 (14:20-16:20).

    private fun minute(hhmm: String): Int = LocalTime.parse(hhmm).let { it.hour * 60 + it.minute }

    private fun ongoingAt(hhmm: String, changes: CourseChanges = CourseChanges.EMPTY) =
        ClassTableSelection.ongoingClasses(listOf(CourseChangeFixture.course()), CourseChangeFixture.thursday, minute(hhmm), changes)

    private fun cardAt(hhmm: String, changes: CourseChanges = CourseChanges.EMPTY) =
        ClassTableSelection.todayCard(CourseChangeFixture.course(), CourseChangeFixture.thursday, minute(hhmm), changes)

    @Test
    fun `the class table ends a class at its end minute`() {
        assertEquals(listOf("2", "3", "4"), ongoingAt("12:09").single().periods)
        assertEquals(listOf("2", "3", "4"), cardAt("12:09").periods)
        assertFalse(cardAt("12:09").isFinished)

        assertTrue(ongoingAt("12:10").isEmpty())
        assertEquals("the card moves on to the next block", listOf("7", "8"), cardAt("12:10").periods)
        assertEquals(
            "the carousel moves on to the next block",
            listOf("7", "8"),
            ClassTableSelection.carouselBlock(CourseChangeFixture.course(), 4, minute("12:10")),
        )
    }

    @Test
    fun `the class table ends the last class of the day at its end minute`() {
        assertFalse(cardAt("16:19").isFinished)
        assertTrue(cardAt("16:20").isFinished)
        assertTrue(ongoingAt("16:20").isEmpty())
    }

    @Test
    fun `the class table ends a class the school ended early at its changed end minute`() {
        val changes = CourseChangeFixture.changes(
            CourseChangeFixture.row(1, "end", periods = listOf("2", "3", "4"), values = mapOf("end" to "11:45")),
        )

        assertEquals(listOf("2", "3", "4"), ongoingAt("11:44", changes).single().periods)
        assertTrue(ongoingAt("11:45", changes).isEmpty())
        assertEquals(listOf("7", "8"), cardAt("11:45", changes).periods)
    }

    @Test
    fun `the class table finishes a changed class that was the day's last at its changed end minute`() {
        val changes = CourseChangeFixture.changes(
            CourseChangeFixture.row(1, "cancelled", periods = listOf("2", "3", "4")),
            CourseChangeFixture.row(2, "end", periods = listOf("7", "8"), values = mapOf("end" to "15:45")),
        )

        assertFalse(cardAt("15:44", changes).isFinished)
        assertTrue(cardAt("15:45", changes).isFinished)
    }

    // ---- the block the course detail opens on (tigerduck-app-android#243) ----

    private val morningAndAfternoon = course("CS4", mapOf(1 to listOf("3", "4", "7", "8")))

    @Test
    fun `a tapped period opens its own block, not the whole day`() {
        assertEquals(listOf("3", "4"), ClassTableSelection.block(morningAndAfternoon, 1, "4"))
        assertEquals(listOf("7", "8"), ClassTableSelection.block(morningAndAfternoon, 1, "8"))
        assertEquals("14:20 - 16:20", ClassTableSelection.timeRange(listOf("7", "8")))
    }

    @Test
    fun `a period the course does not take opens the next block, as a conflict pick names the cluster's first`() {
        assertEquals(listOf("7", "8"), ClassTableSelection.block(morningAndAfternoon, 1, "6"))
        assertNull("nothing after the last block", ClassTableSelection.block(morningAndAfternoon, 1, "9"))
        assertNull("no block on a day it does not meet", ClassTableSelection.block(morningAndAfternoon, 2, "3"))
    }

    @Test
    fun `blocks either side of a row the grid leaves out are one cell, and open together`() {
        val c = course("CS5", mapOf(1 to listOf("3", "4", "6", "7")))
        val drawn = listOf("1", "2", "3", "4", "6", "7", "8", "9", "10")

        assertEquals(listOf("3", "4", "6", "7"), ClassTableSelection.block(c, 1, "6", drawn))
        assertEquals("with period 5 drawn", listOf("6", "7"), ClassTableSelection.block(c, 1, "6"))
    }

    @Test
    fun `the detail's time is the opened block's, else the whole day's`() {
        assertEquals(
            "10:20 - 12:10",
            ClassTableSelection.detailTimeRange(morningAndAfternoon, 1, block = listOf("3", "4")),
        )
        assertEquals("10:20 - 16:20", ClassTableSelection.detailTimeRange(morningAndAfternoon, 1, block = null))
    }
}

