package org.ntust.app.tigerduck.push

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

// The snapshot of `GET /sync/full` and its types, which the full syncs, the re-enable check and
// Use Local read: protocol 2's, the one `/v4` serves. A field marked "protocol 2" is one that
// shape added to what the app read of `/v3`'s; SnapshotStamps.kt reads the stamps it adds.

data class CourseOverrideResult(
    val moodleCourseId: String,
    val courseNo: String?,
    val colorHex: String?,
    /** Per language; an empty name is one the user cleared. */
    val customNames: Map<String, String> = emptyMap(),
    /** The term of the course row the override belongs to. */
    val semester: String? = null,
    /**
     * Whether every assignment of the course is ignored. Null when the
     * backend did not send the field, which a backend older than it does.
     */
    val ignoreAssignments: Boolean? = null,
    /**
     * When a device last set [ignoreAssignments], either way. Null on a row
     * made for a colour or a name, or remade after the backend dropped it
     * with its course row.
     */
    val ignoreAssignmentsUpdatedAt: String? = null,
    /** The course's numeric Moodle id, once a client has sent it. */
    val moodleNumericCourseId: Int? = null,
    /**
     * When a device last set [colorHex], as the backend's ISO-8601 time.
     * Null from a backend that does not send it, and on a row whose colour
     * came with an upload rather than a pick.
     */
    val colorHexUpdatedAt: String? = null,
    /** The course as `/v4`'s override PATCH names it, `client:{term}:{course no}`. Protocol 2. */
    val courseKey: String? = null,
    /** The clock value of the edit that set [colorHex]. Protocol 2. */
    val colorHexHlc: Long? = null,
    /** The clock value of the edit that set each language's name in [customNames]. Protocol 2. */
    val customNamesHlc: Map<String, Long>? = null,
    /** The clock value of the edit that set [ignoreAssignments]. Protocol 2. */
    val ignoreAssignmentsHlc: Long? = null,
)

data class ServerCourse(
    val courseNo: String,
    val courseName: String,
    val semester: String,
    val instructors: List<String> = emptyList(),
    val credits: Float = 0f,
    val classroom: String = "",
    val enrolledCount: Int = 0,
    val maxCount: Int = 0,
    val moodleId: String? = null,
    val scheduleJson: String = "{}",
    val classroomMapJson: String = "{}",
    val version: Int = 1,
    val updatedAt: String? = null,
    /**
     * The server row's id. A course keeps it through every upload and gets a
     * new one only when its row is deleted and made again; see
     * `CourseIgnoreReconciler.recreated`. Null from a snapshot without it.
     */
    val id: Long? = null,
    /**
     * Where the row came from: `user_added` for a course added by hand on
     * some device. Null from a backend that does not send it.
     */
    val source: String? = null,
    /**
     * `enrolled`, or `dropped` once a roster upload stopped listing the
     * course (backend 3.10.1). Null, from an older backend, counts as
     * enrolled.
     */
    val enrollmentStatus: String? = null,
    /** The clock value of the hand-add that keeps the course listed; null without one. Protocol 2. */
    val addedHlc: Long? = null,
) {
    /** A roster upload dropped the course; see [enrollmentStatus]. */
    val isDropped: Boolean get() = enrollmentStatus == "dropped"

    /** Added by hand, here or on another device; see [source]. A roster never drops such a row. */
    val isUserAdded: Boolean get() = source == "user_added"
}

data class CourseTombstone(
    val courseKey: String,
    val courseNo: String?,
    val semester: String,
    val deletedAt: String,
    /** The clock value of the hide, removal or reset that wrote it. Protocol 2. */
    val hlc: Long? = null,
    /** What wrote it; null for a kind this build does not know. Protocol 2. */
    val kind: TombstoneKind? = null,
)

data class BackendSyncResult(
    val ignoredIds: Set<String>,
    val completedIds: Set<String>,
    val courseOverrides: List<CourseOverrideResult>,
    val serverCourseNos: Set<String>,
    val serverCourses: List<ServerCourse> = emptyList(),
    val currentRevision: Long,
    val coursesResetAt: String? = null,
    val tombstones: List<CourseTombstone> = emptyList(),
    /** When the request went out (epoch millis) — what the snapshot is as of. */
    val fetchedAtMs: Long = 0L,
    /**
     * Holidays the user opted back into on some device. Null means the
     * server did not send the section at all — an older backend — which is
     * not the same as "the user has none", so the local set is left alone.
     */
    val holidayOverrides: Set<Int>? = null,
    /**
     * The terms the backend asks this account's roster of (backend 3.10.1): none of it arrived in
     * a week. Empty from an older backend, which sends no `roster_requests`.
     */
    val rosterRequests: List<String> = emptyList(),
    /** The backend's clock as the snapshot began, which every write it holds is no later than. Protocol 2. */
    val serverHlc: Long? = null,
    /** The clock value of the last full course reset. Protocol 2. */
    val coursesResetHlc: Long? = null,
    /** Each term's last course reset, as a clock value. Protocol 2. */
    val termResets: Map<String, Long>? = null,
    /**
     * Every mark the backend holds, by Moodle assignment id, `none` included: a cleared mark is
     * a stamped `none` (LWW D9). Protocol 2.
     */
    val marks: Map<String, StampedValue<String>>? = null,
    /** Every holiday choice the backend holds, by holiday id, `notify: false` included. Protocol 2. */
    val holidays: Map<Int, StampedValue<Boolean>>? = null,
)

/**
 * The snapshot `GET /sync/full` answers, as the syncs read it; its `roster_requests` are
 * [parseRosterRequests]'s, and its fetch time the caller's. [heard] is told the largest clock value
 * read, which no edit this device makes may be stamped below (LWW D4).
 */
internal fun parseFullSync(json: JSONObject, heard: (Long) -> Unit = {}): BackendSyncResult {
    // The snapshot's assignment rows are not read as assignments: every
    // cached assignment comes from Moodle itself (MoodleService). They
    // only key the overrides below by server row id, for a server whose
    // overrides carry no moodle_assignment_id of their own.
    val arr = json.optJSONArray("assignments") ?: return BackendSyncResult(
        emptySet(), emptySet(), emptyList(), emptySet(), emptyList(), json.optLong("current_revision", 0)
    )
    val marks = parseMarks(json, assignmentKeys(arr))
    val courses = parseCourses(json)

    Log.d("SyncApi", "[fullSync] courseMoodleIdToNo: ${courses.moodleIdToNo.size} entries, courses: ${courses.rows.size}")

    val courseOverrides = parseCourseOverrides(json, courses.moodleIdToNo)
    val result = BackendSyncResult(
        ignoredIds = marks.ignored,
        completedIds = marks.completed,
        courseOverrides = courseOverrides,
        serverCourseNos = courses.numbers,
        serverCourses = courses.rows,
        currentRevision = json.optLong("current_revision", 0),
        coursesResetAt = nullableString(json, "courses_reset_at"),
        tombstones = parseTombstones(json),
        holidayOverrides = parseHolidayOverrides(json),
        marks = marks.stamped,
    )
    return result.withSnapshotStamps(json).also { read -> read.clockValues().maxOrNull()?.let(heard) }
}

/** Each snapshot assignment row's server id, to the Moodle assignment id it carries. */
private fun assignmentKeys(arr: JSONArray): Map<Int, String> {
    val pkToMoodleId = mutableMapOf<Int, String>()
    for (i in 0 until arr.length()) {
        val a = arr.getJSONObject(i)
        val pk = a.optInt("id", -1)
        val mid = a.optInt("moodle_assignment_id", -1)
        if (pk > 0 && mid > 0) pkToMoodleId[pk] = mid.toString()
    }
    return pkToMoodleId
}

/** The marks of `assignment_overrides`, by Moodle assignment id; [stamped] null without the section. */
private class SnapshotMarks(val ignored: Set<String>, val completed: Set<String>, val stamped: Map<String, StampedValue<String>>?)

private fun parseMarks(json: JSONObject, pkToMoodleId: Map<Int, String>): SnapshotMarks {
    val ignoredIds = mutableSetOf<String>()
    val completedIds = mutableSetOf<String>()
    val overArr = json.optJSONArray("assignment_overrides")
    val stamped = if (overArr != null) mutableMapOf<String, StampedValue<String>>() else null
    if (overArr != null) {
        for (i in 0 until overArr.length()) {
            val o = overArr.getJSONObject(i)
            val status = o.optString("local_status", "none")
            val mid = o.optInt("moodle_assignment_id", -1)
            val moodleId = if (mid > 0) mid.toString()
                else pkToMoodleId[o.optInt("user_assignment_id", -1)] ?: continue
            when (status) {
                "ignored", "archived" -> ignoredIds.add(moodleId)
                "locally_completed" -> completedIds.add(moodleId)
            }
            stamped?.keepLater(moodleId, StampedValue(nullableString(o, "local_status") ?: "none", nullableLong(o, "local_status_hlc")))
        }
    }
    return SnapshotMarks(ignoredIds, completedIds, stamped)
}

/**
 * The snapshot's `courses`: each row with a course number, those numbers, which tell what the
 * server dropped, and each Moodle id's course number, which keys an override without its own.
 */
private class SnapshotCourses(val moodleIdToNo: Map<String, String>, val numbers: Set<String>, val rows: List<ServerCourse>)

private fun parseCourses(json: JSONObject): SnapshotCourses {
    val courseMoodleIdToNo = mutableMapOf<String, String>()
    val serverCourseNos = mutableSetOf<String>()
    val serverCourses = mutableListOf<ServerCourse>()
    val coursesArr = json.optJSONArray("courses")
    if (coursesArr != null) {
        for (i in 0 until coursesArr.length()) {
            val c = coursesArr.getJSONObject(i)
            val courseNo = nullableString(c, "course_no")
            parseServerCourse(c)?.let { course ->
                serverCourseNos.add(course.courseNo)
                serverCourses.add(course)
            }
            val mId = nullableString(c, "moodle_id") ?: continue
            if (courseNo != null) {
                courseMoodleIdToNo[mId] = courseNo
            } else {
                val cName = nullableString(c, "course_name") ?: continue
                val bracketEnd = cName.indexOf("】")
                if (bracketEnd >= 0) {
                    val rest = cName.substring(bracketEnd + 1).trim()
                    val code = rest.split(" ", limit = 2).firstOrNull()?.takeIf { it.isNotEmpty() }
                    if (code != null) courseMoodleIdToNo[mId] = code
                }
            }
        }
    }
    return SnapshotCourses(courseMoodleIdToNo, serverCourseNos, serverCourses)
}

private fun parseTombstones(json: JSONObject): List<CourseTombstone> {
    val tombstones = mutableListOf<CourseTombstone>()
    val tombArr = json.optJSONArray("course_tombstones")
    if (tombArr != null) {
        for (i in 0 until tombArr.length()) {
            val t = tombArr.getJSONObject(i)
            tombstones.add(CourseTombstone(
                courseKey = t.optString("course_key", ""),
                courseNo = nullableString(t, "course_no"),
                semester = t.optString("semester", ""),
                deletedAt = t.optString("deleted_at", ""),
                hlc = nullableLong(t, "hlc"),
                kind = TombstoneKind.of(nullableString(t, "kind")),
            ))
        }
    }
    return tombstones
}

/** `notify: false` rows are stored server-side so a device can tell
 *  "turned it off" from "never set it"; only the true ones belong in the
 *  opted-in set. */
private fun parseHolidayOverrides(json: JSONObject): Set<Int>? {
    val arr = json.optJSONArray("holiday_overrides") ?: return null
    val out = mutableSetOf<Int>()
    for (i in 0 until arr.length()) {
        val row = arr.optJSONObject(i) ?: continue
        if (row.optBoolean("notify", false)) out.add(row.optInt("holiday_id", -1))
    }
    out.remove(-1)
    return out
}

/** One of the snapshot's `courses`, or null for a row without a `course_no`. */
internal fun parseServerCourse(row: JSONObject): ServerCourse? {
    val courseNo = nullableString(row, "course_no") ?: return null
    val instructors = mutableListOf<String>()
    val instrArr = row.optJSONArray("instructors")
    if (instrArr != null) {
        for (j in 0 until instrArr.length()) {
            instrArr.optString(j)?.takeIf { it.isNotEmpty() }?.let { instructors.add(it) }
        }
    }
    return ServerCourse(
        courseNo = courseNo,
        courseName = nullableString(row, "course_name") ?: courseNo,
        semester = nullableString(row, "semester") ?: "",
        instructors = instructors,
        credits = row.optDouble("credits", 0.0).toFloat(),
        classroom = nullableString(row, "classroom") ?: "",
        enrolledCount = row.optInt("enrolled_count", 0),
        maxCount = row.optInt("max_count", 0),
        moodleId = nullableString(row, "moodle_id"),
        scheduleJson = row.optJSONObject("schedule_json")?.toString() ?: "{}",
        classroomMapJson = row.optJSONObject("classroom_map")?.toString() ?: "{}",
        version = row.optInt("version", 1),
        updatedAt = nullableString(row, "updated_at"),
        id = serverCourseRowId(row),
        source = nullableString(row, "source"),
        enrollmentStatus = nullableString(row, "enrollment_status"),
        addedHlc = nullableLong(row, "added_hlc"),
    )
}

/** A snapshot course row's `id`, or null when it has none. */
internal fun serverCourseRowId(row: JSONObject): Long? =
    if (row.isNull("id")) null else row.optLong("id", 0L).takeIf { it > 0 }

/**
 * The snapshot's `course_overrides`. [courseMoodleIdToNo] names the course of
 * an override that carries no `course_no` of its own.
 */
internal fun parseCourseOverrides(json: JSONObject, courseMoodleIdToNo: Map<String, String>): List<CourseOverrideResult> {
    val rows = json.optJSONArray("course_overrides") ?: return emptyList()
    val overrides = mutableListOf<CourseOverrideResult>()
    for (i in 0 until rows.length()) {
        val co = rows.optJSONObject(i) ?: continue
        val moodleId = nullableString(co, "moodle_id") ?: continue
        val namesObj = co.optJSONObject("custom_names")
        val names = mutableMapOf<String, String>()
        // Kept when empty: "" is a name the user cleared (tigerduck-backend#122),
        // which the merge takes off this phone too.
        if (namesObj != null) {
            for (key in namesObj.keys()) {
                names[key] = namesObj.optString(key, "")
            }
        }
        val resolvedNo = nullableString(co, "course_no") ?: courseMoodleIdToNo[moodleId]
        val colorHex = nullableString(co, "color_hex")
        Log.d("SyncApi", "[fullSync] override moodleId=$moodleId courseNo=$resolvedNo colorHex=$colorHex names=${names.size}")
        overrides.add(
            CourseOverrideResult(
                moodleCourseId = moodleId,
                courseNo = resolvedNo,
                colorHex = colorHex,
                customNames = names,
                semester = nullableString(co, "semester"),
                ignoreAssignments = if (co.isNull("ignore_assignments")) {
                    null
                } else {
                    co.optBoolean("ignore_assignments")
                },
                ignoreAssignmentsUpdatedAt = nullableString(co, "ignore_assignments_updated_at"),
                moodleNumericCourseId = co.optLong("moodle_course_id", 0L)
                    .takeIf { it in 1..Int.MAX_VALUE }
                    ?.toInt(),
                colorHexUpdatedAt = nullableString(co, "color_hex_updated_at"),
                courseKey = nullableString(co, "course_key"),
                colorHexHlc = nullableLong(co, "color_hex_hlc"),
                customNamesHlc = clockMap(co.optJSONObject("custom_names_hlc")),
                ignoreAssignmentsHlc = nullableLong(co, "ignore_assignments_hlc"),
            )
        )
    }
    return overrides
}

private fun nullableString(obj: JSONObject, key: String): String? {
    if (obj.isNull(key)) return null
    val v = obj.optString(key, "")
    return v.ifBlank { null }
}
