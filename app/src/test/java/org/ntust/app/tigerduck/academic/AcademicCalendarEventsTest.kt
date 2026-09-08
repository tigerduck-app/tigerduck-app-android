package org.ntust.app.tigerduck.academic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.EventSource
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

class AcademicCalendarEventsTest {

    private val taipei = ZoneId.of("Asia/Taipei")

    private val calendar = AcademicCalendar(
        revision = 1L,
        terms = listOf(
            SemesterTerm("1151", LocalDate.parse("2026-09-07"), LocalDate.parse("2026-12-25")),
        ),
        holidays = listOf(
            Holiday(
                4, "中秋節", "Mid-Autumn",
                LocalDate.parse("2026-09-25"), LocalDate.parse("2026-09-27"),
            ),
        ),
    )

    private fun events(languageTag: String = "en-US"): List<CalendarEvent> =
        AcademicCalendarEvents.eventsFor(
            calendar = calendar,
            languageTag = languageTag,
            startTitle = { "Start of $it" },
            endTitle = { "End of $it" },
            formatCode = { "${it.take(3)}-${it.drop(3)}" },
            zone = taipei,
        )

    @Test
    fun `a multi-day holiday produces one row, not one per day`() {
        // Eight identical rows for a week-long break would bury the Moodle
        // deadlines the calendar exists to show.
        val holidays = events().filter { it.eventId.startsWith("holiday:") }
        assertEquals(1, holidays.size)
    }

    @Test
    fun `the holiday row sits on its first day`() {
        val holiday = events().single { it.eventId == "holiday:4" }
        assertEquals(taipeiDate("2026-09-25"), holiday.date)
    }

    @Test
    fun `a term contributes exactly a start and an end`() {
        val boundaries = events().filterNot { it.eventId.startsWith("holiday:") }
        assertEquals(
            listOf("Start of 115-1", "End of 115-1"),
            boundaries.map { it.title },
        )
        assertEquals(taipeiDate("2026-09-07"), boundaries[0].date)
        assertEquals(taipeiDate("2026-12-25"), boundaries[1].date)
    }

    @Test
    fun `a term boundary is not filed as a holiday`() {
        // The distinction is user-visible: the row's source label is what
        // used to read "假日" on a day that is an ordinary school day.
        val (boundaries, holidays) =
            events().partition { !it.eventId.startsWith("holiday:") }
        assertTrue(holidays.isNotEmpty())
        assertTrue(holidays.all { it.source == EventSource.HOLIDAY })
        assertTrue(boundaries.isNotEmpty())
        assertTrue(boundaries.all { it.source == EventSource.SEMESTER })
    }

    @Test
    fun `the holiday title follows the language tag`() {
        assertEquals("Mid-Autumn", events("en-US").single { it.eventId == "holiday:4" }.title)
        assertEquals("中秋節", events("zh-Hant-TW").single { it.eventId == "holiday:4" }.title)
    }

    @Test
    fun `a holiday row maps back to its holiday`() {
        val holiday = events().single { it.eventId == "holiday:4" }
        assertEquals(4, AcademicCalendarEvents.holidayIdFor(holiday))
    }

    @Test
    fun `a term boundary maps to no holiday`() {
        // Boundaries are announcements, not days off — there is nothing to
        // opt back into, so the toggle must not appear on them.
        val boundary = events().single { it.eventId == "term-start:1151" }
        assertNull(AcademicCalendarEvents.holidayIdFor(boundary))
    }

    @Test
    fun `an unrelated event maps to no holiday`() {
        val moodle = CalendarEvent("moodle-99", "HW1", Date(0), EventSource.MOODLE.raw)
        assertNull(AcademicCalendarEvents.holidayIdFor(moodle))
    }

    @Test
    fun `an empty calendar produces no rows`() {
        val empty = AcademicCalendarEvents.eventsFor(
            AcademicCalendar.EMPTY, "en-US", { it }, { it }, { it }, taipei,
        )
        assertTrue(empty.isEmpty())
    }

    private fun taipeiDate(iso: String): Date =
        Date.from(LocalDate.parse(iso).atStartOfDay(taipei).toInstant())
}
