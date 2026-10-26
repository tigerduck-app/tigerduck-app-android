package org.ntust.app.tigerduck.academic

import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.EventSource
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * Turns the published academic calendar into calendar-screen rows.
 *
 * A row on every day a holiday covers, as the iPhone has it, and one at each
 * end of a term. The screen lists the selected day's events only, so a day in
 * the middle of a typhoon break or 寒假 shows the holiday, and its toggle,
 * too. Every day's row maps back to the one holiday ([holidayIdFor]), so the
 * toggle on any of them applies to all of it.
 *
 * Pure, so the shape of what lands on the calendar is testable without a
 * screen. Titles come in as already-localized strings because resource
 * lookup needs a Context and this does not.
 */
object AcademicCalendarEvents {

    /** Stable id prefix for a holiday row, also used to route taps back to
     *  the holiday the toggle applies to. */
    const val HOLIDAY_PREFIX = "holiday:"

    /** Between a holiday row's holiday id and its day, which keeps each day's id its own. */
    private const val HOLIDAY_DAY_SEPARATOR = "@"
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
        val holidays = calendar.holidays.flatMap { holiday ->
            val title = holiday.name(languageTag)
            generateSequence(holiday.start) { it.plusDays(1) }
                .takeWhile { !it.isAfter(holiday.end) }
                .map { day ->
                    CalendarEvent(
                        eventId = "$HOLIDAY_PREFIX${holiday.id}$HOLIDAY_DAY_SEPARATOR$day",
                        title = title,
                        date = day.toDate(zone),
                        sourceRaw = EventSource.HOLIDAY.raw,
                    )
                }
                .toList()
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
            ?.substringBefore(HOLIDAY_DAY_SEPARATOR)
            ?.toIntOrNull()

    private fun LocalDate.toDate(zone: ZoneId): Date =
        Date.from(atStartOfDay(zone).toInstant())
}
