package org.ntust.app.tigerduck.coursechanges

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * One row of the school's course-changes feed: a class cancelled, moved to another room, started
 * or ended at another time, or given a note, on the class days the server resolved for it.
 * Ported from iOS `CourseChange`.
 */
data class CourseChange(
    /** Unique across terms. */
    val id: Int,
    val courseNo: String,
    val kind: Kind,
    /** NTUST period codes the change covers; null covers every period the course meets that day. */
    val periods: List<String>?,
    val room: String?,
    /** The new time as `HH:mm`, Taipei time. */
    val start: String?,
    val end: String?,
    val noteZh: String?,
    /** `note_en`, or [noteZh] when the feed has none. */
    val noteEn: String?,
) {
    enum class Kind(val wire: String) {
        CANCELLED("cancelled"),
        ROOM("room"),
        START("start"),
        END("end"),
        NOTE("note");

        companion object {
            fun of(wire: String): Kind? = entries.firstOrNull { it.wire == wire }
        }
    }

    fun covers(period: String): Boolean = periods?.contains(period) ?: true

    /** The note in the app's language: Chinese in either script, English otherwise. */
    fun note(locale: Locale): String? = if (locale.language == "zh") noteZh else noteEn
}

/**
 * The current term's course changes from `/v3/calendar/course-changes`, by course and class day.
 * [term] only tells whether the feed has answered; rows match by course number and day alone.
 * Ported from iOS `CourseChanges`.
 */
class CourseChanges private constructor(
    val term: String,
    private val byCourseDay: Map<Key, List<CourseChange>>,
) {
    private data class Key(val courseNo: String, val day: LocalDate)

    fun changes(courseNo: String, day: LocalDate): List<CourseChange> = byCourseDay[Key(courseNo, day)].orEmpty()

    /** The days [courseNo] has a change on, earliest first. */
    fun days(courseNo: String): List<LocalDate> = byCourseDay.keys.filter { it.courseNo == courseNo }.map { it.day }.sorted()

    fun isCancelled(courseNo: String, day: LocalDate, period: String): Boolean =
        changes(courseNo, day).any { it.kind == CourseChange.Kind.CANCELLED && it.covers(period) }

    override fun equals(other: Any?): Boolean =
        other is CourseChanges && term == other.term && byCourseDay == other.byCourseDay

    override fun hashCode(): Int = 31 * term.hashCode() + byCourseDay.hashCode()

    override fun toString(): String = "CourseChanges(term=$term, ${byCourseDay.values.sumOf { it.size }} entries)"

    companion object {
        /** No feed yet: every screen looks as it did before course changes. */
        val EMPTY = CourseChanges("", emptyMap())

        /**
         * The feed's 200 body. Read by hand rather than by Gson, so a row of an unexpected shape is
         * dropped and the rest still shows; a body that is not an object with a term and a changes
         * array throws, and the caller keeps the copy it has.
         */
        fun decode(json: String): CourseChanges {
            val value = JSONTokener(json).nextValue()
            if (value !is JSONObject) throw JSONException("not a course changes feed")
            val term = value.opt("term") as? String ?: throw JSONException("course changes feed without a term")
            val rows = value.opt("changes") as? JSONArray ?: throw JSONException("course changes feed without changes")
            val index = LinkedHashMap<Key, MutableList<CourseChange>>()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val change = change(row) ?: continue
                val days = stringList(row, "class_days") ?: continue
                for (day in days.mapNotNull(::day)) {
                    index.getOrPut(Key(change.courseNo, day)) { mutableListOf() } += change
                }
            }
            return CourseChanges(term, index)
        }

        /** Null for a row of the wrong shape, a kind this build does not know, or a row missing its kind's value. */
        private fun change(row: JSONObject): CourseChange? {
            val id = when (val raw = row.opt("id")) {
                is Int -> raw
                is Long -> raw.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                else -> null
            } ?: return null
            val courseNo = row.opt("course_no") as? String ?: return null
            val kind = (row.opt("kind") as? String)?.let(CourseChange.Kind::of) ?: return null
            val periods = if (row.isNull("periods")) null else stringList(row, "periods") ?: return null
            val strings = listOf("room", "start", "end", "note_zh", "note_en").associateWith { key ->
                if (row.isNull(key)) null else row.opt(key) as? String ?: return null
            }
            val value = when (kind) {
                CourseChange.Kind.CANCELLED -> null
                CourseChange.Kind.ROOM -> strings["room"]
                CourseChange.Kind.START -> strings["start"]
                CourseChange.Kind.END -> strings["end"]
                CourseChange.Kind.NOTE -> strings["note_zh"]
            }
            if (kind != CourseChange.Kind.CANCELLED && value.isNullOrEmpty()) return null
            return CourseChange(
                id = id,
                courseNo = courseNo,
                kind = kind,
                periods = periods,
                room = strings["room"],
                start = strings["start"],
                end = strings["end"],
                noteZh = strings["note_zh"],
                noteEn = strings["note_en"] ?: strings["note_zh"],
            )
        }

        /** An absent or null key reads as an empty list; anything but an array of strings as null. */
        private fun stringList(row: JSONObject, key: String): List<String>? {
            if (row.isNull(key)) return emptyList()
            val array = row.opt(key) as? JSONArray ?: return null
            return (0 until array.length()).map { array.opt(it) as? String ?: return null }
        }

        private fun day(text: String): LocalDate? =
            try {
                LocalDate.parse(text)
            } catch (_: DateTimeParseException) {
                null
            }
    }
}
