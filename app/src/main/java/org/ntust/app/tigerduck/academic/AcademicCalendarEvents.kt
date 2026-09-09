package org.ntust.app.tigerduck.academic

import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.EventSource
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * Turns the published academic calendar into calendar-screen rows.
 *
 * One event per holiday and one at each end of a term — not one per day.
 * A week-long 寒假 is a single thing that happened, and eight identical rows
 * would bury the Moodle deadlines the screen exists to show.
 *
 * Pure, so the shape of what lands on the calendar is testable without a
 * screen. Titles come in as already-localized strings because resource
 * lookup needs a Context and this does not.
 */
object AcademicCalendarEvents {

    /** Stable id prefix for a holiday row, also used to route taps back to
     *  the holiday the toggle applies to. */
    const val HOLIDAY_PREFIX = "holiday:"
    private const val TERM_START_PREFIX = "term-start:"
    private const val TERM_END_PREFIX = "term-end:"

    /**
     * @param languageTag picks the holiday's Chinese or English name.
     * @param startTitle formats a term's opening row, e.g. `"Start of %1$s"`.
     * @param endTitle formats a term's closing row.
     * @param formatCode renders "1151" the way the rest of the app does
     *   ("115-1"), passed in so this stays free of UI helpers.
     */
    fun eventsFor(
        calendar: AcademicCalendar,
        languageTag: String,
        startTitle: (String) -> String,
        endTitle: (String) -> String,
        formatCode: (String) -> String,
        zone: ZoneId,
    ): List<CalendarEvent> {
        val holidays = calendar.holidays.map { holiday ->
            CalendarEvent(
                eventId = "$HOLIDAY_PREFIX${holiday.id}",
                title = holiday.name(languageTag),
                date = holiday.start.toDate(zone),
                sourceRaw = EventSource.HOLIDAY.raw,
            )
        }
        val boundaries = calendar.terms.flatMap { term ->
            val label = formatCode(term.code)
            listOf(
                CalendarEvent(
                    eventId = "$TERM_START_PREFIX${term.code}",
                    title = startTitle(label),
                    date = term.start.toDate(zone),
                    sourceRaw = EventSource.SEMESTER.raw,
                ),
                CalendarEvent(
                    eventId = "$TERM_END_PREFIX${term.code}",
                    title = endTitle(label),
                    date = term.end.toDate(zone),
                    sourceRaw = EventSource.SEMESTER.raw,
                ),
            )
        }
        return holidays + boundaries
    }

    /**
     * The holiday a calendar row belongs to, or null when the row is a term
     * boundary or something else entirely.
     *
     * Boundaries deliberately return null: they are announcements, not days
     * off, so there is nothing to opt back into.
     */
    fun holidayIdFor(event: CalendarEvent): Int? =
        event.eventId.removePrefix(HOLIDAY_PREFIX)
            .takeIf { event.eventId.startsWith(HOLIDAY_PREFIX) }
            ?.toIntOrNull()

    private fun LocalDate.toDate(zone: ZoneId): Date =
        Date.from(atStartOfDay(zone).toInstant())
}
