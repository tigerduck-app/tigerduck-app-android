package org.ntust.app.tigerduck.ui.screen.calendar

import android.text.format.DateFormat
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.model.EventSource
import org.ntust.app.tigerduck.util.replaceIosArg
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

/** The day [this] falls on in Taipei, the zone every calendar day is read in. */
internal fun Date.taipeiDate(): LocalDate = toInstant().atZone(AppConstants.TAIPEI_ZONE).toLocalDate()

/**
 * [events] filed under their Taipei date, so the month grid reads a cell's
 * events by key. Each cell used to scan every event, building two
 * `Calendar`s per event, on every recomposition of every pager page.
 */
internal fun groupByTaipeiDay(events: List<CalendarEvent>): Map<LocalDate, List<CalendarEvent>> =
    events.groupBy { it.date.taipeiDate() }

/** Dots one month cell can show before they run out of room. */
private const val MAX_DOTS = 3

/**
 * The sources a day's dots show, one dot per source: Moodle, school and exam
 * first, then holidays and term boundaries, at most [MAX_DOTS]. The order of
 * the iPhone's month view, which is also [EventSource]'s declaration order.
 */
internal fun dotSources(events: List<CalendarEvent>): List<EventSource> =
    events.map { it.source }.distinct().sorted().take(MAX_DOTS)

/**
 * The heading over a day's events: the date in [locale]'s own month-day form
 * ("10月14日", "Oct 14"), with [todaySuffix] when it is [today]. The iPhone
 * heads the list the same way.
 */
internal fun dayTitle(date: LocalDate, today: LocalDate, locale: Locale, todaySuffix: String): String {
    val formatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
    val title = formatter.format(date)
    if (date != today) return title
    // The suffix is " (Today)" in every locale but Japanese, whose "（今日）"
    // carries its own spacing in the full-width brackets. aapt drops the
    // leading space of an unquoted string resource, so put it back before
    // an ASCII bracket. tigerduck-app/app-translation#21 (PR #22) makes the
    // generator keep edge spaces; once the submodule includes it the
    // resource arrives as " (Today)", this branch no longer runs, and the
    // check can go. Both forms give exactly one space meanwhile.
    return if (todaySuffix.startsWith("(")) "$title $todaySuffix" else title + todaySuffix
}

/**
 * What TalkBack reads for a day cell: [template] (`a11y_calendar_day`, "%1$@, %2$lld events")
 * filled with [date] in [locale]'s full form and [eventCount], as the iPhone labels the cell.
 * The full date because the cell shows only its number; the count because the dots are
 * colour alone.
 */
internal fun dayCellDescription(template: String, date: LocalDate, eventCount: Int, locale: Locale): String =
    template
        .replaceIosArg(1, DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(date))
        .replaceIosArg(2, eventCount.toLong())
