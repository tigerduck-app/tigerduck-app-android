package org.ntust.app.tigerduck.data.model

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import org.ntust.app.tigerduck.R
import java.util.Date

data class CalendarEvent(
    val eventId: String,
    val title: String,
    val date: Date,
    val sourceRaw: String // "moodle", "school", "exam"
) {
    val source: EventSource
        get() = EventSource.fromRaw(sourceRaw)
}

enum class EventSource(val raw: String) {
    MOODLE("moodle"),
    SCHOOL("school"),
    EXAM("exam");

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            MOODLE -> R.string.calendar_source_moodle
            SCHOOL -> R.string.calendar_source_school
            EXAM -> R.string.calendar_source_exam
        }

    val color: Color
        get() = when (this) {
            MOODLE -> Color(0xFF4A90D9)
            SCHOOL -> Color(0xFFFF9500)
            EXAM -> Color(0xFFFF3B30)
        }

    companion object {
        fun fromRaw(raw: String): EventSource =
            entries.firstOrNull { it.raw == raw } ?: SCHOOL
    }
}
