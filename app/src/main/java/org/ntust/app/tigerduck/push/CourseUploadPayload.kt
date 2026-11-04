// The body of POST /sync/courses/upload.
//
// Split out of PushApiClient.uploadCourses so what goes on the wire can be
// asserted without a server. Two of the three decisions below were made after
// the wrong version of them shipped, and neither failure was visible from the
// client: one pinned a colour server-side that no later palette change could
// dislodge, the other pushed one device's private rename out as the name every
// device would render. A malformed field here is not a crash, it is a value
// quietly replicated to the rest of the account.

package org.ntust.app.tigerduck.push

import org.ntust.app.tigerduck.data.TranscriptTerms
import org.ntust.app.tigerduck.shared.Course

internal object CourseUploadPayload {

    /**
     * Build the upload body for [courses] in [semester].
     *
     * [forceKeys] names courses whose server-side tombstone should be cleared
     * — an explicit re-add by the user, not a side effect of a sync. Omitted
     * from the body entirely when empty, so a routine upload can never
     * resurrect something the user deleted on another device.
     *
     * [rosterTerms] names the terms whose listed courses are the school's
     * whole roster (backend 3.10.1): the backend enrolls the listed school
     * courses of each and drops the others. Omitted when empty, as an upload
     * that is no roster changes no course's enrollment; a backend before
     * 3.10.1 ignores the field. See [CourseUpload.of] for when a fetch sends
     * one, as iOS `PushAPI.CourseUploadRequest.rosterTerms` (tigerduck-app#914).
     */
    fun build(
        courses: List<Course>,
        semester: String,
        forceKeys: List<String> = emptyList(),
        rosterTerms: List<String> = emptyList(),
    ): Map<String, Any> {
        val items = items(courses, semester)
        // Only real choices. Sending the auto-computed colour for every course
        // made the server store all of them as explicit overrides on first
        // upload — its upsert is create-only (`if override.color_hex is None`),
        // so whichever generated hex landed first was then pinned server-side
        // and no later palette change could take effect. The server already
        // drops null entries; this is the client half of that contract.
        val overrides = courses.mapNotNull { c ->
            c.customColorHex?.let { hex ->
                mapOf(
                    // "client:{semester}:{course_no}" is the key the upload
                    // route derives for these same rows. An override whose key
                    // does not match one of them is silently skipped.
                    "course_key" to key(semester, c.courseNo),
                    "color_hex" to hex,
                )
            }
        }
        val payload = mutableMapOf<String, Any>(
            "courses" to items,
            "course_overrides" to overrides,
        )
        if (forceKeys.isNotEmpty()) payload["force_keys"] = forceKeys
        if (rosterTerms.isNotEmpty()) payload["roster_terms"] = rosterTerms
        return payload
    }

    /**
     * `/v4`'s body for [courses] in [semester]: the courses, and [adds], by key, each hand-add's
     * stamp in epoch ms ([handAdds]). No `course_overrides`, which `/v4` refuses: a color goes only
     * as its course's override PATCH. No `force_keys` either: an add lifts a tombstone by its stamp.
     */
    fun buildV4(courses: List<Course>, semester: String, rosterTerms: List<String>, adds: Map<String, Long>): Map<String, Any> {
        val payload = mutableMapOf<String, Any>("courses" to items(courses, semester))
        if (adds.isNotEmpty()) {
            payload["adds"] = adds.map { (courseKey, atMs) -> mapOf("course_key" to courseKey, "hlc" to clockOf(atMs)) }
        }
        if (rosterTerms.isNotEmpty()) payload["roster_terms"] = rosterTerms
        return payload
    }

    /**
     * The adds a `/v4` upload of [courses] must name, by key (X9): every course added by hand
     * ([isAddedByHand]), and every one the caller adds now ([addedAt], [forceKeys]), whoever
     * uploads them, a school roster included. `/v4` takes every other course of an upload for the
     * school's roster, and would turn a hand-added one into a school row. Each is stamped with the
     * caller's [addedAt] entry, else 0, the oldest. Never a copy of a school row merged down from
     * the server: named at 0, the row takes an add the student never made, which keeps every
     * roster from dropping it, and a term whose courses are all named goes as no roster.
     */
    fun handAdds(courses: List<Course>, semester: String, forceKeys: List<String>, addedAt: Map<String, Long>): Map<String, Long> =
        courses.filter { isAddedByHand(it) || it.courseNo in addedAt || key(semester, it.courseNo) in forceKeys }
            .associate { key(semester, it.courseNo) to (addedAt[it.courseNo] ?: 0L) }

    /**
     * Whether [course] was added by hand, on this phone or another: added here, with no Moodle id
     * ([TranscriptTerms.isAddedHere], as the iPhone tells it), or a copy merged down of a row the
     * backend holds as added by hand ([Course.isUserAddedOnServer] true). A copy of a school row is
     * manual here too, and is none; one no snapshot has told about goes in no upload ([sendable]).
     */
    fun isAddedByHand(course: Course): Boolean = TranscriptTerms.isAddedHere(course) || course.isUserAddedOnServer == true

    /**
     * Whether [course] is a copy merged down from the server ([Course.isManual], with a Moodle id)
     * that no `/v4` snapshot has told what its row is ([Course.isUserAddedOnServer] null).
     */
    fun isUntoldCopy(course: Course): Boolean =
        course.isManual && !course.moodleIdNumber.isNullOrEmpty() && course.isUserAddedOnServer == null

    /**
     * [courses] as a `/v4` upload of [semester] sends them: without a copy no snapshot has told about
     * ([isUntoldCopy]), unless the caller adds it now ([addedAt], [forceKeys]), as Use Local does.
     * Each way such a copy could go writes what the backend keeps: named, a school row takes an add
     * that keeps every roster from dropping it; listed unnamed, a `user_added` row turns into a
     * school row. Left out, nothing changes: an upload with no roster drops nothing, and a roster
     * lists no such copy, since the school's fetch tells every copy it lists (`ClassTableCourseMerge
     * .mergeFetched`). Until a pull tells the copy, uploads do not refresh its row.
     */
    fun sendable(courses: List<Course>, semester: String, forceKeys: List<String>, addedAt: Map<String, Long>): List<Course> =
        courses.filter { !isUntoldCopy(it) || it.courseNo in addedAt || key(semester, it.courseNo) in forceKeys }

    /** The key the upload route derives for a course: "client:{semester}:{course_no}". */
    fun key(semester: String, courseNo: String): String = "client:$semester:$courseNo"

    private fun items(courses: List<Course>, semester: String): List<Map<String, Any?>> =
        courses.map { c ->
            mapOf(
                "semester" to semester,
                "course_no" to c.courseNo,
                // courseName, not displayName. This field is `user_courses
                // .course_name` server-side — the shared default every device
                // renders when it has no override of its own. displayName
                // folds in customCourseName, so a private rename ("微積分" →
                // "早八地獄") would land there as the canonical name: it would
                // reach the user's other devices even with course-name sync
                // switched off, and "revert to default" would then restore the
                // rename instead of the real title. A rename travels on its
                // own channel — patchCourseOverride(customName=, locale=),
                // which the syncCourseNames pref actually gates.
                "course_name" to c.courseName,
                "course_name_en" to null,
                "moodle_id" to c.moodleIdNumber,
                "credits" to c.credits.toDouble(),
                "classroom" to c.classroom,
                "instructors" to c.instructor
                    .split(",", "，", "、")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
                "schedule_json" to c.schedule.mapKeys { it.key.toString() },
                "classroom_map" to c.classroomMap,
            )
        }
}
