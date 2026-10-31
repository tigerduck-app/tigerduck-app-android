package org.ntust.app.tigerduck.data.model

import org.ntust.app.tigerduck.data.CourseIgnores

/**
 * A change to whether a course's assignments are ignored that the backend
 * has not confirmed yet, kept in `pending_course_ignores.json` and sent again
 * on the next sync.
 *
 * Every field is nullable or primitive: Gson builds this class without its
 * constructor (see CLAUDE.md), so a field added later reads back from an
 * older file as null or zero rather than crashing.
 */
data class PendingCourseIgnore(
    val semester: String?,
    val courseNo: String?,
    /**
     * The override PATCH's path segment: the course's Moodle idnumber, or
     * "<semester><courseNo>" for a course without one, as the backend keys
     * a course added by hand.
     */
    val moodleId: String?,
    /** The course's numeric Moodle id, when this device knows it. */
    val moodleCourseId: Int?,
    val ignore: Boolean,
    /** When the user made the change; tells it from a later one for the same course. */
    val changedAtMs: Long,
    /** How many times the backend refused it; see CourseIgnoreSync, which parks it after five. */
    val attempts: Int = 0,
    /** Sent as the change's `hlc`; null on a row written before it was kept, which goes out unstamped. */
    val madeAtMs: Long? = null,
) {
    /** Whether [other] is this change, however many times either was refused. */
    fun isSameChange(other: PendingCourseIgnore): Boolean = copy(attempts = 0) == other.copy(attempts = 0)

    /** The [CourseIgnores] key, or null for a row too damaged to send. */
    val key: String?
        get() {
            val term = semester?.takeIf { it.isNotBlank() } ?: return null
            val no = courseNo?.takeIf { it.isNotBlank() } ?: return null
            if (moodleId.isNullOrBlank()) return null
            return CourseIgnores.key(term, no)
        }
}
