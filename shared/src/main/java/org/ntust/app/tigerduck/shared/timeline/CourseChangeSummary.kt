package org.ntust.app.tigerduck.shared.timeline

import org.ntust.app.tigerduck.shared.Course
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One line of the course detail's 課程異動 list. Ported from iOS `CourseDetailSheet.changeRows`' rows. */
data class CourseChangeRow(val day: LocalDate, val summary: String)

/**
 * The words the 課程異動 list writes, in the app's language: 停課 (`course_card_cancelled`), 評量
 * (`course_change_assessment`) and 線上 (`course_change_online`).
 */
data class CourseChangeWords(val cancelled: String, val assessment: String, val online: String)

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
     * cards show ([shown]) is left to the cards; its notes stay, as the cards have no place for them,
     * and so does the time of each meeting [shown] spans with others, as the cards show only its ends.
     * Each held meeting's assessments and online classes follow, then the notes, as `評量 · 期中考`
     * and `線上 · <description>`. [locale] picks each note, name and description's language.
     */
    fun rows(days: List<ChangedDay>, shown: ClassSlot?, words: CourseChangeWords, locale: Locale): List<CourseChangeRow> =
        days.asSequence()
            .map { CourseChangeRow(it.day, summary(it.meetings, shown, words, locale)) }
            .filter { it.summary.isNotEmpty() }
            .take(MAX_ROWS)
            .toList()

    private fun summary(meetings: List<ClassSlot>, shown: ClassSlot?, words: CourseChangeWords, locale: Locale): String {
        val parts = mutableListOf<String>()
        for (meeting in meetings) {
            if (shown != null && meeting.date == shown.date && meeting.periods.any { it in shown.periods }) {
                // The cards show the tapped class; a room its meeting's other classes are held in they
                // do not, unless it is the tapped class's own.
                val shownPlaces = shown.periods.mapNotNull { placeChange(meeting.changes, it)?.id }
                parts += meeting.periods.filter { it !in shown.periods }
                    .mapNotNull { placeChange(meeting.changes, it) }
                    .filter { it.kind == CourseChange.Kind.ROOM && it.id !in shownPlaces }
                    .mapNotNull { it.room }
                    .distinct()
                // A block over more meetings shows only its outer ends and is cancelled only whole, so
                // this meeting's own cancellation, else its own time, says which part changed.
                if (shown.periods.any { it !in meeting.periods }) {
                    if (meeting.isCancelled) {
                        parts += words.cancelled
                    } else if (meeting.hasChangedTime) {
                        parts += "${hm(meeting.start)} - ${hm(meeting.end)}"
                    }
                }
                continue
            }
            if (meeting.isCancelled) {
                parts += words.cancelled
                continue
            }
            // An online class is listed with its description below, not as a room.
            meeting.movedRooms(online = null).takeIf { it.isNotEmpty() }?.let { parts += it.joinToString(", ") }
            if (meeting.hasChangedTime) parts += "${hm(meeting.start)} - ${hm(meeting.end)}"
        }
        val held = meetings.filter { !it.isCancelled }
        parts += (held.flatMap { it.changes }.filter { it.kind == CourseChange.Kind.ASSESSMENT } + held.flatMap { it.onlineChanges } +
            meetings.flatMap { it.notes })
            .distinctBy { it.id }
            .mapNotNull { listed(it, words, locale) }
        return parts.distinct().joinToString(" · ")
    }

    /** How [change] reads in the list: a note's text, `評量 · <name>`, or `線上` and its description. As iOS `listedText`. */
    private fun listed(change: CourseChange, words: CourseChangeWords, locale: Locale): String? {
        val detail = change.note(locale)?.takeIf { it.isNotEmpty() }
        return when (change.kind) {
            CourseChange.Kind.ASSESSMENT -> detail?.let { "${words.assessment} · $it" }
            CourseChange.Kind.ONLINE -> listOfNotNull(words.online, detail).joinToString(" · ")
            else -> detail
        }
    }

    // Class times are on a 24-hour clock in every locale.
    private val hourMinute = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    private fun hm(time: LocalTime): String = time.format(hourMinute)
}
