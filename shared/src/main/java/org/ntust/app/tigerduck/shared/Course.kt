package org.ntust.app.tigerduck.shared

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class Course(
    val courseNo: String,
    val courseName: String,
    val instructor: String = "",
    val credits: Int = 0,
    val classroom: String = "",
    val enrolledCount: Int = 0,
    val maxCount: Int = 0,
    /** JSON: {"1":["3","4"],"3":["6","7"]} — keys = weekday (1=Mon..7=Sun) */
    val scheduleJson: String = "{}",
    /**
     * JSON: {"1-3":"T3-101","3-6":"T4-101"} — keys = "weekday-period". Lets
     * [classroom] hold the deduped union of all rooms while still resolving
     * the *right* room for a given (weekday, period). Empty for cached
     * courses written before this field existed; consumers fall back to the
     * flat [classroom] string in that case.
     */
    val classroomMapJson: String = "{}",
    val moodleIdNumber: String? = null,
    /** User-picked tile color as "#RRGGBB". Null means hash-based palette assignment. */
    val customColorHex: String? = null,
    /**
     * True when the user added this course manually via the `+` sheet. Manual
     * courses must survive refreshes even if the NTUST enrolment list or
     * Moodle list doesn't include them.
     */
    val isManual: Boolean = false,
    /**
     * User-supplied display name override. When non-null, [displayName] returns
     * this instead of the derived [courseName]. Lets the user customize labels
     * without losing the underlying default — flipping the abbreviation toggle
     * still updates [courseName], but anything with a non-null override stays
     * visually unchanged. Reverting to default sets this back to null.
     */
    val customCourseName: String? = null,
) {
    /** Resolved name for display: user override if set, else the derived default. */
    val displayName: String
        get() = customCourseName ?: courseName

    @Transient
    @Volatile
    private var _cachedSchedule: Map<Int, List<String>>? = null

    @Transient
    @Volatile
    private var _cachedClassroomMap: Map<String, String>? = null

    val schedule: Map<Int, List<String>>
        get() {
            _cachedSchedule?.let { return it }
            synchronized(this) {
                _cachedSchedule?.let { return it }
                val type = object : TypeToken<Map<String, List<String>>>() {}.type
                val raw: Map<String, List<String>> = try {
                    scheduleGson.fromJson(scheduleJson, type) ?: emptyMap()
                } catch (e: Exception) {
                    emptyMap()
                }
                val parsed = raw.mapKeys { it.key.toIntOrNull() ?: 0 }
                    .filterKeys { it != 0 }
                _cachedSchedule = parsed
                return parsed
            }
        }

    /** Parsed classroom map: "weekday-period" → room string (may itself be comma-joined). */
    val classroomMap: Map<String, String>
        get() {
            _cachedClassroomMap?.let { return it }
            synchronized(this) {
                _cachedClassroomMap?.let { return it }
                val type = object : TypeToken<Map<String, String>>() {}.type
                val parsed: Map<String, String> = try {
                    scheduleGson.fromJson(classroomMapJson, type) ?: emptyMap()
                } catch (e: Exception) {
                    emptyMap()
                }
                _cachedClassroomMap = parsed
                return parsed
            }
        }

    /**
     * Returns the classroom(s) for [weekday], deduped and comma-joined in
     * chronological period order. Falls back to the deduped flat [classroom]
     * string when no per-period map data exists (e.g. cached courses from
     * before this field was introduced, or a row that genuinely has no rooms).
     */
    fun classroom(weekday: Int): String {
        val map = classroomMap
        if (map.isEmpty()) return dedupRooms(classroom)
        val periods = schedule[weekday] ?: return dedupRooms(classroom)
        val sorted = periods.sortedBy(::periodOrder)
        val seen = LinkedHashSet<String>()
        for (period in sorted) {
            val raw = map["$weekday-$period"] ?: continue
            for (part in splitRooms(raw)) seen.add(part)
        }
        return if (seen.isEmpty()) dedupRooms(classroom) else seen.joinToString(", ")
    }

    companion object {
        private val scheduleGson = Gson()
        private val roomSeparators = Regex("[,，、]")

        /** Split a raw classroom string by common separators, trim, drop empties. */
        fun splitRooms(raw: String): List<String> =
            raw.split(roomSeparators).map { it.trim() }.filter { it.isNotEmpty() }

        /** Dedup a flat classroom string that may contain separator-joined duplicates. */
        fun dedupRooms(raw: String): String {
            val seen = LinkedHashSet<String>()
            for (part in splitRooms(raw)) seen.add(part)
            return if (seen.isEmpty()) raw else seen.joinToString(", ")
        }

        fun fromSchedule(
            courseNo: String,
            courseName: String,
            instructor: String = "",
            credits: Int = 0,
            classroom: String = "",
            enrolledCount: Int = 0,
            maxCount: Int = 0,
            schedule: Map<Int, List<String>> = emptyMap(),
            classroomMap: Map<String, String> = emptyMap(),
            moodleIdNumber: String? = null,
            isManual: Boolean = false,
        ): Course {
            val stringKeyMap = schedule.mapKeys { it.key.toString() }
            val json = scheduleGson.toJson(stringKeyMap)
            val mapJson = scheduleGson.toJson(classroomMap)
            return Course(
                courseNo = courseNo,
                courseName = courseName,
                instructor = instructor,
                credits = credits,
                classroom = classroom,
                enrolledCount = enrolledCount,
                maxCount = maxCount,
                scheduleJson = json,
                classroomMapJson = mapJson,
                moodleIdNumber = moodleIdNumber,
                isManual = isManual,
            )
        }

        fun courseNoFromMoodleId(moodleId: String): String =
            if (moodleId.length > 4) moodleId.drop(4) else moodleId
    }
}
