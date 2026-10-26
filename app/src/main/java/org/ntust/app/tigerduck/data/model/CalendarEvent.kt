package org.ntust.app.tigerduck.data.model

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.core.strings.R
import java.time.LocalTime
import java.util.Date

data class CalendarEvent(
    val eventId: String,
    val title: String,
    val date: Date,
    val sourceRaw: String // "moodle", "school", "exam", "holiday", "semester"
) {
    val source: EventSource
        get() = EventSource.fromRaw(sourceRaw)

    /**
     * Whether this row is a whole day rather than a moment, so a time on it
     * ("00:00") says nothing. Holidays and term boundaries are days by
     * construction. A school row is one when the feed gave a date with no
     * time, which `CalendarService` parses to midnight in Taipei. A Moodle
     * deadline at midnight is a real deadline and keeps its time. The same
     * rule as the iPhone's `SDCalendarEvent.isAllDay`.
     *
     * A getter, not a field: this class is cached through Gson.
     */
    val isAllDay: Boolean
        get() = when (source) {
            EventSource.HOLIDAY, EventSource.SEMESTER -> true
            EventSource.SCHOOL -> date.toInstant().atZone(AppConstants.TAIPEI_ZONE).toLocalTime() == LocalTime.MIDNIGHT
            EventSource.MOODLE, EventSource.EXAM -> false
        }

    /**
     * The id of the Moodle assignment this row is the deadline of, which a tap
     * on the row opens, or null for any other row. A getter, as [isAllDay].
     */
    val moodleAssignmentId: String?
        get() = eventId.removePrefix(MOODLE_ID_PREFIX)
            .takeIf { source == EventSource.MOODLE && eventId.startsWith(MOODLE_ID_PREFIX) && it.isNotEmpty() }

    companion object {
        private const val MOODLE_ID_PREFIX = "moodle-"

        /** The id of the row for the deadline of the Moodle assignment [assignmentId]. */
        fun moodleEventId(assignmentId: String) = "$MOODLE_ID_PREFIX$assignmentId"
    }
}

enum class EventSource(val raw: String) {
    MOODLE("moodle"),
    SCHOOL("school"),
    EXAM("exam"),

    /**
     * A school holiday, from the published academic calendar. Distinct from
     * [SCHOOL] because these are the only events the user can act on —
     * tapping one offers the "still remind me" toggle — and because they are
     * the ones that silence class reminders.
     */
    HOLIDAY("holiday"),

    /**
     * The first or last day of a term, from the same feed.
     *
     * Deliberately not [HOLIDAY]: a term boundary is an announcement, there
     * is still class that day, and nothing is silenced. It was folded into
     * holidays at first and the rows read "假日" — which was simply wrong.
     */
    SEMESTER("semester");

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            MOODLE -> R.string.calendar_source_moodle
            SCHOOL -> R.string.calendar_source_school
            EXAM -> R.string.calendar_source_exam
            HOLIDAY -> R.string.calendar_source_holiday
            SEMESTER -> R.string.calendar_source_semester
        }

    val color: Color
        get() = when (this) {
            MOODLE -> Color(0xFF4A90D9)
            SCHOOL -> Color(0xFFFF9500)
            EXAM -> Color(0xFFFF3B30)
            // Green reads as "no class" against school orange and exam red,
            // and is the one colour not already spoken for.
            HOLIDAY -> Color(0xFF34C759)
            // Indigo: the boundary is neither a day off nor a deadline, so
            // it should not borrow holiday green or exam red.
            SEMESTER -> Color(0xFF5856D6)
        }

    companion object {
        fun fromRaw(raw: String): EventSource =
            entries.firstOrNull { it.raw == raw } ?: SCHOOL
    }
}
