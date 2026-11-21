package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.SemesterTerm
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import java.time.Instant
import java.time.LocalDate

/**
 * The class table as a week from today once the course-changes feed has answered (tigerduck-app
 * `CourseChangesTests`, `ClassTableViewModel.columnDate` and `TimetableGridView.nowLineOffset`).
 */
class ClassTableThisWeekTest {

    @Test
    fun `the class table runs a week from today`() {
        val wednesday = LocalDate.of(2026, 11, 4)

        assertEquals(
            listOf("2026-11-09", "2026-11-10", "2026-11-04", "2026-11-05", "2026-11-06", "2026-11-07", "2026-11-08"),
            (1..7).map { ClassTableThisWeek.columnDate(it, wednesday).toString() },
        )
    }

    @Test
    fun `today is Taipei's day`() {
        // 2026-11-04 01:30 in Taipei, still Tuesday in UTC.
        val now = Instant.parse("2026-11-03T17:30:00Z")

        assertEquals(LocalDate.of(2026, 11, 4), ClassTableThisWeek.columnDate(3, now))
        assertEquals(LocalDate.of(2026, 11, 10), ClassTableThisWeek.columnDate(2, now))
    }

    @Test
    fun `the time line crosses a period by its share and sits in the gap between two`() {
        val periods = listOf(490..540, 550..600, 800..850)
        fun offset(minute: Int) = ClassTableThisWeek.nowLineOffset(minute, periods, rowPitch = 55f, cellHeight = 52f)

        assertNull(offset(489))
        assertEquals(0f, offset(490)!!, 0.001f)
        assertEquals(26f, offset(515)!!, 0.001f)
        assertEquals(52f, offset(540)!!, 0.001f)
        assertEquals(53.5f, offset(545)!!, 0.001f)
        assertEquals(108.5f, offset(700)!!, 0.001f)
        assertEquals(136f, offset(825)!!, 0.001f)
        assertNull(offset(851))
        assertNull("no rows, no line", ClassTableThisWeek.nowLineOffset(600, emptyList(), rowPitch = 55f, cellHeight = 52f))
    }

    @Test
    fun `a period with no length puts the line at its top`() {
        assertEquals(0f, ClassTableThisWeek.nowLineOffset(600, listOf(600..600), rowPitch = 55f, cellHeight = 52f)!!, 0.001f)
    }

    @Test
    fun `the week from today shows only for the current term once the feed has answered`() {
        assertTrue(ClassTableThisWeek.showsThisWeek(feedTerm = "1151", tableSemester = "1151", currentSemester = "1151"))
        assertFalse("before the first answer", ClassTableThisWeek.showsThisWeek(feedTerm = "", tableSemester = "1151", currentSemester = "1151"))
        assertFalse("another term", ClassTableThisWeek.showsThisWeek(feedTerm = "1151", tableSemester = "1142", currentSemester = "1151"))
        assertTrue(
            "the feed's term is not compared with the table's",
            ClassTableThisWeek.showsThisWeek(feedTerm = "1142", tableSemester = "1151", currentSemester = "1151"),
        )
    }

    private val calendar = AcademicCalendar(1L, listOf(SemesterTerm("1151", LocalDate.of(2026, 9, 16), LocalDate.of(2027, 1, 15))), emptyList())

    private fun answered(term: String) = CourseChanges.decode("""{"term":"$term","changes":[]}""")

    @Test
    fun `the week from today carries the term's week, with today's mark`() {
        val friday = LocalDate.of(2026, 11, 6)
        val week = ClassTableThisWeek.weekOf(friday, answered("1151"), tableSemester = "1151", currentSemester = "1151", calendar)

        assertEquals(friday, week?.today)
        assertEquals(8, week?.termWeek)
        assertNull("before the first answer", ClassTableThisWeek.weekOf(friday, CourseChanges.EMPTY, "1151", "1151", calendar))
        assertNull("another term", ClassTableThisWeek.weekOf(friday, answered("1151"), "1142", "1151", calendar))
    }

    @Test
    fun `a day outside the term, or a term the calendar lacks, shows today's mark with no week`() {
        val summer = ClassTableThisWeek.weekOf(LocalDate.of(2027, 1, 20), answered("1151"), "1151", "1151", calendar)
        assertEquals(LocalDate.of(2027, 1, 20), summer?.today)
        assertNull(summer?.termWeek)
        val unknown = ClassTableThisWeek.weekOf(LocalDate.of(2026, 11, 6), answered("1151"), "1151", "1151", AcademicCalendar.EMPTY)
        assertNull(unknown?.termWeek)
    }
}
