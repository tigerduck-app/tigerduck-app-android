// The body of POST /sync/courses/upload.
//
// Split out of PushApiClient.uploadCourses so what goes on the wire can be
// asserted without a server. The course name below was decided after the
// wrong version of it shipped, and the failure was not visible from the
// client: one device's private rename went out as the name every device
// would render. A malformed field here is not a crash, it is a value quietly
// replicated to the rest of the account.

package org.ntust.app.tigerduck.push

import org.ntust.app.tigerduck.shared.Course

internal object CourseUploadPayload {

    /**
     * The body for [courses] in [semester]: the courses, and [adds], by key, each hand-add's stamp
     * in epoch ms ([handAdds]). No `course_overrides`, which the backend refuses: a color goes only
     * as its course's override PATCH. No `force_keys` either: an add lifts a tombstone by its stamp.
     *
     * [rosterTerms] names the terms whose listed courses are the school's whole roster (backend
     * 3.10.1): the backend enrolls the listed school courses of each and drops the others. Omitted
     * when empty, as an upload that is no roster changes no course's enrollment. See
     * [CourseUpload.of] for when a fetch sends one, as iOS
     * `PushAPI.CourseUploadRequest.rosterTerms` (tigerduck-app#914).
     */
    fun build(courses: List<Course>, semester: String, rosterTerms: List<String>, adds: Map<String, Long>): Map<String, Any> {
        val payload = mutableMapOf<String, Any>("courses" to items(courses, semester))
        if (adds.isNotEmpty()) {
            payload["adds"] = adds.map { (courseKey, atMs) -> mapOf("course_key" to courseKey, "hlc" to clockOf(atMs)) }
        }
        if (rosterTerms.isNotEmpty()) payload["roster_terms"] = rosterTerms
        return payload
    }

    /**
     * What the upload ledger fingerprints [courses] of [semester] by ([courseUploadFingerprint]):
     * the body the upload carried before it spoke `/v4` alone, with each course's picked color as
     * `course_overrides`, though no upload carries one now. Kept byte for byte, so a list the
     * backend accepted from an earlier build is still the same list, and goes again only once it
     * changes (a color included), or six hours on, as before.
     */
    fun ledgerBody(courses: List<Course>, semester: String, rosterTerms: List<String>): Map<String, Any> {
        val colors = courses.mapNotNull { c ->
            c.customColorHex?.let { hex -> mapOf("course_key" to key(semester, c.courseNo), "color_hex" to hex) }
        }
        val payload = mutableMapOf<String, Any>("courses" to items(courses, semester), "course_overrides" to colors)
        if (rosterTerms.isNotEmpty()) payload["roster_terms"] = rosterTerms
        return payload
    }

    /**
     * The adds an upload of [courses] must name, by key (X9): every course added by hand
     * ([Course.isManual]), and every one the caller adds now ([addedAt], [forceKeys]), whoever
     * uploads them, a school roster included. The backend takes every other course of an upload
     * for the school's roster, and would turn a hand-added one into a school row. Each is stamped
     * with the caller's [addedAt] entry, else 0, the oldest, which changes nothing the backend
     * already holds.
     */
    fun handAdds(courses: List<Course>, semester: String, forceKeys: List<String>, addedAt: Map<String, Long>): Map<String, Long> =
        courses.filter { it.isManual || it.courseNo in addedAt || key(semester, it.courseNo) in forceKeys }
            .associate { key(semester, it.courseNo) to (addedAt[it.courseNo] ?: 0L) }

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
