package org.ntust.app.tigerduck.data.model

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import org.ntust.app.tigerduck.R
import java.util.Date

data class CalendarEvent(
    val eventId: String,
    val title: String,
    val date: Date,
    val sourceRaw: String // "moodle", "school", "exam", "holiday", "semester"
) {
    val source: EventSource
        get() = EventSource.fromRaw(sourceRaw)
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
