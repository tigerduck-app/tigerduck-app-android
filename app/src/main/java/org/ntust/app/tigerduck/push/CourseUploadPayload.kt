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

import org.ntust.app.tigerduck.shared.Course

internal object CourseUploadPayload {

    /**
     * Build the upload body for [courses] in [semester].
     *
     * [forceKeys] names courses whose server-side tombstone should be cleared
     * — an explicit re-add by the user, not a side effect of a sync. Omitted
     * from the body entirely when empty, so a routine upload can never
     * resurrect something the user deleted on another device.
     */
    fun build(
        courses: List<Course>,
        semester: String,
        forceKeys: List<String> = emptyList(),
    ): Map<String, Any> {
        val items = courses.map { c ->
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
                    "course_key" to "client:$semester:${c.courseNo}",
                    "color_hex" to hex,
                )
            }
        }
        val payload = mutableMapOf<String, Any>(
            "courses" to items,
            "course_overrides" to overrides,
        )
        if (forceKeys.isNotEmpty()) payload["force_keys"] = forceKeys
        return payload
    }
}
