package org.ntust.app.tigerduck.ui.screen.classtable

import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.QuietDays
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.PeriodTimes
import org.ntust.app.tigerduck.shared.blockClassroom
import org.ntust.app.tigerduck.shared.time.TaipeiClock
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import org.ntust.app.tigerduck.shared.timeline.CourseMeetings
import org.ntust.app.tigerduck.util.IcsFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * The class table's term as an iCalendar file: one weekly event per meeting, a run of consecutive
 * periods of a course on a day as the class table merges them, at the timetable's period times,
 * from its first meeting to the term's last day, with each school holiday that takes a class as an
 * EXDATE. The weekly timetable only: course changes and the user's skip marks are not in it, nor
 * are clubs, which no [ShownClassTable] holds. Ported from iOS `ClassTableCalendarFile`.
 */
object ClassTableCalendarFile {
    /** A term's days, and the days classes do not meet on in it. */
    data class Term(val code: String, val start: LocalDate, val end: LocalDate, val quietDays: QuietDays)

    /** One meeting's weekly series, its times in Taipei. */
    data class Event(
        val uid: String,
        val summary: String,
        val location: String,
        val description: String,
        val start: LocalDateTime,
        val end: LocalDateTime,
        /** The later meetings a holiday takes. */
        val excluded: List<LocalDateTime>,
    )

    /**
     * Term [code] as the academic calendar has it; null when it has no such term. The user's own
     * table keeps the holidays they still have class on ([optedIn]); another student's drops every
     * holiday, since their choices are not on this phone.
     */
    fun term(code: String, ownTable: Boolean, calendar: AcademicCalendar, optedIn: Set<Int>): Term? {
        val term = calendar.terms.firstOrNull { it.code == code } ?: return null
        return Term(code, term.start, term.end, QuietDays(calendar, if (ownTable) optedIn else emptySet()))
    }

    /** The events of the table [shown] draws, under its person's uids on someone else's. */
    fun events(shown: ShownClassTable, term: Term): List<Event> = events(shown.courses, term, shown.person?.id)

    /** [owner] is the person whose table it is, null for the user's own. */
    fun events(courses: List<Course>, term: Term, owner: String?): List<Event> = courses.flatMap { course ->
        course.schedule.keys.filter { it in WEEKDAYS }.sorted().flatMap { weekday ->
            val firstDay = term.start.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(weekday)))
            val days = generateSequence(firstDay) { it.plusWeeks(1) }.takeWhile { !it.isAfter(term.end) }.toList()
            CourseMeetings.buildSlots(listOf(course), firstDay, CourseChanges.EMPTY).mapNotNull { meeting ->
                // RFC 5545 counts DTSTART as a meeting even when an EXDATE names it, so a series
                // starts at the first meeting a holiday leaves.
                val held = days.indexOfFirst { it !in term.quietDays }
                if (held < 0) return@mapNotNull null
                Event(
                    // A calendar keeps one event per UID (RFC 5545 §3.8.4.7), so another student's
                    // table adds its owner to them.
                    uid = "${term.code}-${course.courseNo}-$weekday-${meeting.periods.first()}" +
                        owner?.let { "-$it" }.orEmpty() + "@tigerduck.app",
                    summary = course.displayName,
                    location = course.blockClassroom(weekday, meeting.periods),
                    description = listOf(course.instructor, course.courseNo).filter { it.isNotEmpty() }.joinToString("\n"),
                    start = days[held].atTime(meeting.start),
                    end = days[held].atTime(meeting.end),
                    excluded = days.drop(held + 1).filter { it in term.quietDays }.map { it.atTime(meeting.start) },
                )
            }
        }
    }

    /** Whether any of [courses] meets in a timed period on a weekday column, without building the events. */
    fun hasTimedMeeting(courses: List<Course>): Boolean = courses.any { course ->
        course.schedule.any { (weekday, periods) -> weekday in WEEKDAYS && periods.any { it in PeriodTimes.mapping } }
    }

    /** [events] as the file's text: CRLF line ends, lines folded at 75 octets. [now] is its DTSTAMP. */
    fun text(events: List<Event>, term: Term, now: Instant): String {
        val zone = TaipeiClock.zone
        // The last second of the term's last day, in UTC: under a start with a time zone, RFC 5545
        // §3.3.10 has UNTIL in UTC.
        val until = term.end.plusDays(1).atStartOfDay(zone).minusSeconds(1).toInstant()
        // Taipei has kept one offset all year since 1980, so one standard rule covers every class.
        val offset = zone.rules.getStandardOffset(now).id.replace(":", "")
        val lines = mutableListOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//TigerDuck//Class Table//EN", "CALSCALE:GREGORIAN",
            "BEGIN:VTIMEZONE", "TZID:${zone.id}", "BEGIN:STANDARD", "DTSTART:19700101T000000",
            "TZOFFSETFROM:$offset", "TZOFFSETTO:$offset", "END:STANDARD", "END:VTIMEZONE",
        )
        for (event in events) {
            lines += listOf(
                "BEGIN:VEVENT",
                "UID:" + IcsFormat.escaped(event.uid),
                "DTSTAMP:" + utc(now),
                "DTSTART;TZID=${zone.id}:" + LOCAL.format(event.start),
                "DTEND;TZID=${zone.id}:" + LOCAL.format(event.end),
                "RRULE:FREQ=WEEKLY;UNTIL=" + utc(until),
            )
            lines += event.excluded.map { "EXDATE;TZID=${zone.id}:" + LOCAL.format(it) }
            lines += "SUMMARY:" + IcsFormat.escaped(event.summary)
            if (event.location.isNotEmpty()) lines += "LOCATION:" + IcsFormat.escaped(event.location)
            if (event.description.isNotEmpty()) lines += "DESCRIPTION:" + IcsFormat.escaped(event.description)
            lines += "END:VEVENT"
        }
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n", transform = IcsFormat::folded) + "\r\n"
    }

    private fun utc(at: Instant): String = UTC.format(at.atOffset(ZoneOffset.UTC))

    private val WEEKDAYS = 1..7

    private val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT)

    private val UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
}
