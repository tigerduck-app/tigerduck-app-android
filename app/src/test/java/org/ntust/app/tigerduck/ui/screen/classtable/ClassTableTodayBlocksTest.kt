package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.Holiday
import org.ntust.app.tigerduck.academic.QuietDays
import org.ntust.app.tigerduck.academic.SemesterTerm
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import java.time.LocalDate
import java.time.LocalTime

/**
 * What the class table's carousel and finished checks say about a Thursday (2026-11-05) at the
 * four moments around a class boundary, with and without the school's changed end at 11:45. The
 * day and the minutes are passed in, so no row depends on the real date. Each line reads, in order:
 * the carousel's block (timetable only), the classes going on, the today-strip card (its block,
 * whether a changed meeting rides along, whether the day is over) and the timetable's finished check.
 */
class ClassTableTodayBlocksTest {

    private val day: LocalDate = CourseChangeFixture.thursday
    private val moments = listOf("11:19", "11:20", "12:09", "12:10")

    private fun minute(hhmm: String): Int = LocalTime.parse(hhmm).let { it.hour * 60 + it.minute }

    private fun course(no: String, periods: List<String>, weekday: Int = 4) =
        Course.fromSchedule(courseNo = no, courseName = "Course $no", classroom = "T4-501", schedule = mapOf(weekday to periods))

    private fun ongoing(courses: List<Course>, hhmm: String, changes: CourseChanges): String =
        ClassTableSelection.ongoingClasses(courses, day, minute(hhmm), changes)
            .joinToString(",") { it.course.courseNo + ":" + it.periods.joinToString("") }
            .ifEmpty { "none" }

    private fun line(course: Course, hhmm: String, changes: CourseChanges): String {
        val m = minute(hhmm)
        val carousel = ClassTableSelection.carouselBlock(course, 4, m)?.joinToString("") ?: "none"
        val card = ClassTableSelection.todayCard(course, day, m, changes)
        val block = card.periods?.joinToString("") ?: "none"
        val meeting = if (card.meeting != null) "meeting" else "-"
        val finished = ClassTableSelection.isFinishedAt(course, ClassTableSelection.DayTime(4, m))
        return "carousel=$carousel ongoing=${ongoing(listOf(course), hhmm, changes)} card=$block/$meeting/${card.isFinished} finished=$finished"
    }

    private fun lines(course: Course, changes: CourseChanges, at: List<String> = moments) =
        at.map { line(course, it, changes) }

    private fun end1145(periods: List<String>, no: String = "CC900") =
        CourseChangeFixture.row(1, "end", periods = periods, values = mapOf("end" to "11:45"), courseNo = no)

    private val none = CourseChanges.EMPTY

    @Test
    fun `a course with a morning and an afternoon block, split by a gap`() {
        val c = course("CC900", listOf("2", "3", "4", "7", "8"))
        assertEquals(
            listOf(
                "carousel=234 ongoing=CC900:234 card=234/-/false finished=false",
                "carousel=234 ongoing=CC900:234 card=234/-/false finished=false",
                "carousel=234 ongoing=CC900:234 card=234/-/false finished=false",
                "carousel=78 ongoing=none card=78/-/false finished=false",
            ),
            lines(c, none),
        )
        assertEquals(
            listOf(
                "carousel=234 ongoing=CC900:234 card=234/meeting/false finished=false",
                "carousel=234 ongoing=CC900:234 card=234/meeting/false finished=false",
                "carousel=234 ongoing=none card=78/-/false finished=false",
                "carousel=78 ongoing=none card=78/-/false finished=false",
            ),
            lines(c, CourseChangeFixture.changes(end1145(listOf("2", "3", "4")))),
        )
    }

    @Test
    fun `contiguous periods are one block, which a class starting at 11-20 is not on before`() {
        val c = course("CC900", listOf("3", "4", "7", "8"))
        assertEquals(
            listOf(
                "carousel=34 ongoing=CC900:34 card=34/-/false finished=false",
                "carousel=34 ongoing=CC900:34 card=34/-/false finished=false",
                "carousel=34 ongoing=CC900:34 card=34/-/false finished=false",
                "carousel=78 ongoing=none card=78/-/false finished=false",
            ),
            lines(c, none),
        )
        assertEquals(
            listOf(
                "carousel=34 ongoing=CC900:34 card=34/meeting/false finished=false",
                "carousel=34 ongoing=CC900:34 card=34/meeting/false finished=false",
                "carousel=34 ongoing=none card=78/-/false finished=false",
                "carousel=78 ongoing=none card=78/-/false finished=false",
            ),
            lines(c, CourseChangeFixture.changes(end1145(listOf("3", "4")))),
        )
    }

    @Test
    fun `a class starting at 11-20 is not on at 11-19, and the day is over from its end minute`() {
        val c = course("CC900", listOf("4"))
        assertEquals(
            listOf(
                "carousel=4 ongoing=none card=4/-/false finished=false",
                "carousel=4 ongoing=CC900:4 card=4/-/false finished=false",
                "carousel=4 ongoing=CC900:4 card=4/-/false finished=false",
                "carousel=4 ongoing=none card=4/-/true finished=true",
            ),
            lines(c, none),
        )
        assertEquals(
            listOf(
                "carousel=4 ongoing=none card=4/meeting/false finished=false",
                "carousel=4 ongoing=CC900:4 card=4/meeting/false finished=false",
                "carousel=4 ongoing=none card=4/meeting/true finished=false",
                "carousel=4 ongoing=none card=4/meeting/true finished=true",
            ),
            lines(c, CourseChangeFixture.changes(end1145(listOf("4")))),
        )
    }

    @Test
    fun `a meeting split by a cancelled period runs in pieces, and the card shows it whole`() {
        val c = course("CC900", listOf("2", "3", "4"))
        val cancelThird = CourseChangeFixture.row(2, "cancelled", periods = listOf("3"))
        assertEquals(
            listOf(
                "carousel=234 ongoing=none card=234/-/false finished=false",
                "carousel=234 ongoing=CC900:4 card=234/-/false finished=false",
                "carousel=234 ongoing=CC900:4 card=234/-/false finished=false",
                "carousel=234 ongoing=none card=234/-/true finished=true",
            ),
            lines(c, CourseChangeFixture.changes(cancelThird)),
        )
        assertEquals(
            listOf(
                "carousel=234 ongoing=none card=234/meeting/false finished=false",
                "carousel=234 ongoing=CC900:4 card=234/meeting/false finished=false",
                "carousel=234 ongoing=none card=234/meeting/true finished=false",
                "carousel=234 ongoing=none card=234/meeting/true finished=true",
            ),
            lines(c, CourseChangeFixture.changes(cancelThird, end1145(listOf("2", "3", "4")))),
        )
    }

    @Test
    fun `a cancelled class is not going on, though its card and the timetable check stand`() {
        val c = course("CC900", listOf("2", "3", "4"))
        val cancelled = CourseChangeFixture.row(2, "cancelled", periods = listOf("2", "3", "4"))
        assertEquals(
            listOf(
                "carousel=234 ongoing=none card=234/meeting/false finished=false",
                "carousel=234 ongoing=none card=234/meeting/false finished=false",
                "carousel=234 ongoing=none card=234/meeting/false finished=false",
                "carousel=234 ongoing=none card=234/meeting/true finished=true",
            ),
            lines(c, CourseChangeFixture.changes(cancelled)),
        )
        assertEquals(
            listOf(
                "carousel=234 ongoing=none card=234/meeting/false finished=false",
                "carousel=234 ongoing=none card=234/meeting/false finished=false",
                "carousel=234 ongoing=none card=234/meeting/true finished=false",
                "carousel=234 ongoing=none card=234/meeting/true finished=true",
            ),
            lines(c, CourseChangeFixture.changes(cancelled, end1145(listOf("2", "3", "4")))),
        )
    }

    @Test
    fun `a moved class runs and ends at its changed times`() {
        val c = course("CC900", listOf("7", "8"))
        assertEquals(
            List(4) { "carousel=78 ongoing=none card=78/-/false finished=false" },
            lines(c, none),
        )
        val later = CourseChangeFixture.changes(
            CourseChangeFixture.row(1, "start", periods = listOf("7", "8"), values = mapOf("start" to "11:30")),
            CourseChangeFixture.row(2, "end", periods = listOf("7", "8"), values = mapOf("end" to "12:30")),
        )
        assertEquals(
            listOf(
                "carousel=78 ongoing=none card=78/meeting/false finished=false",
                "carousel=78 ongoing=none card=78/meeting/false finished=false",
                "carousel=78 ongoing=CC900:78 card=78/meeting/false finished=false",
                "carousel=78 ongoing=CC900:78 card=78/meeting/false finished=false",
            ),
            lines(c, later),
        )
        val earlier = CourseChangeFixture.changes(
            CourseChangeFixture.row(1, "start", periods = listOf("7", "8"), values = mapOf("start" to "11:00")),
            end1145(listOf("7", "8")),
        )
        assertEquals(
            listOf(
                "carousel=78 ongoing=CC900:78 card=78/meeting/false finished=false",
                "carousel=78 ongoing=CC900:78 card=78/meeting/false finished=false",
                "carousel=78 ongoing=none card=78/meeting/true finished=false",
                "carousel=78 ongoing=none card=78/meeting/true finished=false",
            ),
            lines(c, earlier),
        )
    }

    @Test
    fun `overlapping classes are all going on, by start and then in the order given`() {
        val b = course("BB1", listOf("4"))
        val a = course("AA1", listOf("3", "4"))
        val d = course("CC1", listOf("4"))
        val all = listOf(b, a, d)
        assertEquals(
            listOf("AA1:34", "AA1:34,BB1:4,CC1:4", "AA1:34,BB1:4,CC1:4", "none"),
            moments.map { ongoing(all, it, none) },
        )
        val changes = CourseChangeFixture.changes(end1145(listOf("3", "4"), no = "AA1"))
        assertEquals(
            listOf("AA1:34", "AA1:34,BB1:4,CC1:4", "BB1:4,CC1:4", "none"),
            moments.map { ongoing(all, it, changes) },
        )
        assertEquals(
            listOf(
                "carousel=34 ongoing=AA1:34 card=34/-/false finished=false",
                "carousel=34 ongoing=AA1:34 card=34/-/false finished=false",
                "carousel=34 ongoing=AA1:34 card=34/-/false finished=false",
                "carousel=34 ongoing=none card=34/-/true finished=true",
            ),
            lines(a, none),
        )
        assertEquals(
            listOf(
                "carousel=34 ongoing=AA1:34 card=34/meeting/false finished=false",
                "carousel=34 ongoing=AA1:34 card=34/meeting/false finished=false",
                "carousel=34 ongoing=none card=34/meeting/true finished=false",
                "carousel=34 ongoing=none card=34/meeting/true finished=true",
            ),
            lines(a, changes),
        )
    }

    @Test
    fun `a class not meeting today is never going on or finished`() {
        val monday = course("MON1", listOf("2"), weekday = 1)
        val expected = List(4) { "carousel=none ongoing=none card=none/-/false finished=false" }
        assertEquals(expected, lines(monday, none))
        assertEquals(expected, lines(monday, CourseChangeFixture.changes(end1145(listOf("2"), no = "MON1"))))
    }

    @Test
    fun `a period with no time is never over, so it is never greyed out`() {
        val late = moments + "23:00"
        val only = course("UNK1", listOf("Z"))
        assertEquals(
            List(5) { "carousel=Z ongoing=none card=Z/-/false finished=false" },
            lines(only, none, late),
        )
        assertEquals(
            List(5) { "carousel=Z ongoing=none card=none/-/false finished=false" },
            lines(only, CourseChangeFixture.changes(end1145(listOf("Z"), no = "UNK1")), late),
        )
    }

    @Test
    fun `a period with no time beside a timed block comes first in the carousel, and the timed block still runs`() {
        val c = course("UNK2", listOf("3", "4", "Z"))
        assertEquals(
            listOf(
                "carousel=Z ongoing=UNK2:34 card=Z/-/false finished=false",
                "carousel=Z ongoing=UNK2:34 card=Z/-/false finished=false",
                "carousel=Z ongoing=UNK2:34 card=Z/-/false finished=false",
                "carousel=Z ongoing=none card=Z/-/true finished=true",
            ),
            lines(c, none),
        )
        assertEquals(
            listOf(
                "carousel=Z ongoing=UNK2:34 card=34/meeting/false finished=false",
                "carousel=Z ongoing=UNK2:34 card=34/meeting/false finished=false",
                "carousel=Z ongoing=none card=34/meeting/true finished=false",
                "carousel=Z ongoing=none card=34/meeting/true finished=true",
            ),
            lines(c, CourseChangeFixture.changes(end1145(listOf("3", "4"), no = "UNK2"))),
        )
    }

    @Test
    fun `a quiet day is the caller's, so the four checks still read the timetable`() {
        val calendar = AcademicCalendar(
            revision = 1L,
            terms = listOf(SemesterTerm("1151", LocalDate.parse("2026-09-07"), LocalDate.parse("2026-12-25"))),
            holidays = listOf(Holiday(1, "測試假日", "Test holiday", day, day)),
        )
        val c = course("CC900", listOf("2", "3", "4"))
        assertEquals(emptyList<Course>(), ClassTableSelection.todayCourses(listOf(c), day, QuietDays(calendar, emptySet())))
        assertEquals(listOf(c), ClassTableSelection.todayCourses(listOf(c), day, QuietDays(calendar, setOf(1))))
        assertEquals("CC900:234", ongoing(listOf(c), "11:19", none))
    }
}
