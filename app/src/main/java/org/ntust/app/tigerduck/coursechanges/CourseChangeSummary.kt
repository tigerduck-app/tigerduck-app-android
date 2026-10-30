package org.ntust.app.tigerduck.coursechanges

import org.ntust.app.tigerduck.shared.Course
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One line of the course detail's 課程異動 list. Ported from iOS `CourseDetailSheet.changeRows`' rows. */
data class CourseChangeRow(val day: LocalDate, val summary: String)

/**
 * The course detail's changes: the meeting its 教室 and 時間 cards show, and the 課程異動 list of
 * upcoming days. The list shows only when the detail has a date. Ported from iOS `CourseDetailSheet`.
 */
object CourseChangeSummary {
    const val MAX_ROWS = 8

    /**
     * The meeting the cards show: the tapped class when there is one, else the merged meeting of
     * [periods] (or of the whole day's schedule) on [date]; null when it has no change or there is
     * no date.
     */
    fun shownMeeting(
        course: Course,
        slot: ClassSlot?,
        periods: List<String>?,
        date: LocalDate?,
        changes: CourseChanges,
    ): ClassSlot? {
        val meeting = slot ?: date?.let { day ->
            (periods ?: course.schedule[day.dayOfWeek.value])?.let { CourseMeetings.meeting(course, it, day, changes) }
        }
        return meeting?.takeIf { it.changes.isNotEmpty() }
    }

    /**
     * At most [MAX_ROWS] days' changes in a line each, from [days] as
     * [CourseMeetings.upcomingChanges] gives them. The room, time or cancellation of the meeting the
     * cards show ([shown]) is left to the cards; its notes stay, as the cards have no place for them.
     * [cancelled] is the 停課 word in the app's language, and [locale] picks each note's language.
     */
    fun rows(days: List<ChangedDay>, shown: ClassSlot?, cancelled: String, locale: Locale): List<CourseChangeRow> =
        days.asSequence()
            .map { CourseChangeRow(it.day, summary(it.meetings, shown, cancelled, locale)) }
            .filter { it.summary.isNotEmpty() }
            .take(MAX_ROWS)
            .toList()

    private fun summary(meetings: List<ClassSlot>, shown: ClassSlot?, cancelled: String, locale: Locale): String {
        val parts = mutableListOf<String>()
        for (meeting in meetings) {
            if (shown != null && meeting.date == shown.date && meeting.periods.any { it in shown.periods }) {
                // The cards show the tapped class; a room its meeting's other classes move to they do not.
                parts += meeting.changes
                    .filter { change -> change.kind == CourseChange.Kind.ROOM && shown.periods.none(change::covers) }
                    .mapNotNull { it.room }
                continue
            }
            if (meeting.isCancelled) {
                parts += cancelled
                continue
            }
            if (meeting.hasChangedRoom) parts += meeting.room
            if (meeting.hasChangedTime) parts += "${hm(meeting.start)} - ${hm(meeting.end)}"
        }
        parts += meetings.flatMap { it.notes }.distinctBy { it.id }.mapNotNull { it.note(locale) }
        return parts.distinct().joinToString(" · ")
    }

    // Class times are on a 24-hour clock in every locale.
    private val hourMinute = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    private fun hm(time: LocalTime): String = time.format(hourMinute)
}
