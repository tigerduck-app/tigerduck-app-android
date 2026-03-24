package com.tigerduck.app.data.model

import androidx.compose.ui.graphics.Color
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "calendar_events")
data class CalendarEvent(
    @PrimaryKey val eventId: String,
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

    val label: String
        get() = when (this) {
            MOODLE -> "Moodle"
            SCHOOL -> "學校"
            EXAM -> "考試"
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
