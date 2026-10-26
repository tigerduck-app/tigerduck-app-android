package org.ntust.app.tigerduck.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.Holiday
import org.ntust.app.tigerduck.academic.QuietDays
import org.ntust.app.tigerduck.academic.SemesterTerm
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.widget.WidgetDataLoader.TomorrowFirst
import java.time.LocalDate

/** Covers [WidgetDataLoader.computeTomorrowFirst], the Next Class widget's look beyond today. */
class WidgetTomorrowFirstTest {

    private val autumnTerm = AcademicCalendar(
        revision = 1L,
        terms = listOf(
            SemesterTerm("1151", LocalDate.parse("2026-09-07"), LocalDate.parse("2026-12-25")),
        ),
        holidays = listOf(
            // A Monday.
            Holiday(1, "教師節", "Teachers' Day", LocalDate.parse("2026-09-28"), LocalDate.parse("2026-09-28")),
        ),
    )

    private val timetable = listOf(
        Course.fromSchedule("MON", "Monday", schedule = mapOf(1 to listOf("2"))),
        Course.fromSchedule("TUE", "Tuesday", schedule = mapOf(2 to listOf("1"))),
    )

    private fun tomorrowFirst(today: String, optedIn: Set<Int> = emptySet()): TomorrowFirst? =
        WidgetDataLoader.computeTomorrowFirst(
            timetable, LocalDate.parse(today), QuietDays(autumnTerm, optedIn),
        )

    @Test
    fun `on a Friday a holiday Monday is passed over for Tuesday's first class`() {
        assertEquals(TomorrowFirst("TUE", weekday = 2, periodId = "1"), tomorrowFirst("2026-09-25"))
    }

    @Test
    fun `a holiday the user opted back into keeps its first class`() {
        assertEquals(
            TomorrowFirst("MON", weekday = 1, periodId = "2"),
            tomorrowFirst("2026-09-25", optedIn = setOf(1)),
        )
    }

    @Test
    fun `a quiet weekend before the term does not hide its first class`() {
        assertEquals(TomorrowFirst("MON", weekday = 1, periodId = "2"), tomorrowFirst("2026-09-04"))
    }

    @Test
    fun `no class is offered after the term ends`() {
        assertNull(tomorrowFirst("2026-12-25"))
    }
}
