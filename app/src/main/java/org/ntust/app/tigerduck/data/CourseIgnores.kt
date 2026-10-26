package org.ntust.app.tigerduck.data

import org.ntust.app.tigerduck.data.model.Assignment

/**
 * Courses whose every assignment the user ignores, held as
 * "<semester>:<courseNo>" keys: the shape of the Apple app's
 * `ignored_courses.json`, and the key the sync derives from a course
 * override.
 *
 * An assignment keeps no record of its own for this. It counts as ignored
 * while its course is, new ones included, and goes back to whatever it was,
 * marked done or ignored on its own, once the course no longer is.
 */
object CourseIgnores {

    fun key(semester: String, courseNo: String): String = "$semester:$courseNo"

    fun semesterOf(key: String): String = key.substringBefore(':')

    /** The course numbers [keys] ignore in [semester]. */
    fun courseNosIn(semester: String, keys: Set<String>): Set<String> {
        val prefix = key(semester, "")
        return keys.asSequence()
            .filter { it.startsWith(prefix) }
            .mapTo(mutableSetOf()) { it.removePrefix(prefix) }
    }

    /**
     * Ids of the assignments in [all] whose course [keys] ignore, each in the
     * term it was fetched for. The cache keeps an earlier term's assignments
     * while Moodle has no course for the new one yet. [semester], the term in
     * session, stands in for one fetched before assignments recorded theirs.
     */
    fun assignmentIds(all: List<Assignment>, keys: Set<String>, semester: String): Set<String> {
        if (keys.isEmpty()) return emptySet()
        return all.asSequence()
            .filter { key(it.semester ?: semester, it.courseNo) in keys }
            .mapTo(mutableSetOf()) { it.assignmentId }
    }
}
