// Which of a fetch's uploads goes as its term's whole roster (tigerduck-app-android#595).
//
// The backend (3.10.1) reads a term named in `roster_terms` as the school's whole roster: it
// enrolls each school course the upload lists and drops the term's others, but those added by
// hand or hidden. Before it, a dropped course stayed enrolled there for good, and every device
// that had not seen the drop showed it and got its class reminders.

package org.ntust.app.tigerduck.push

import org.ntust.app.tigerduck.shared.Course

/**
 * A fetch's upload of a term's courses: what goes, and the terms it is the whole roster of
 * ([rosterTerms], `roster_terms`), empty for an upload that is none.
 */
internal data class CourseUpload(val courses: List<Course>, val rosterTerms: List<String>) {
    companion object {
        /**
         * [courses], [semester]'s list as a fetch saved it, as an upload. [roster] is the list
         * the school gave in this fetch: course selection's answer, or a past term's roster read
         * off its final transcript; null for anything else, the transcript of a running term, the
         * cache or Moodle, which go as no roster. It goes as one only when the upload lists every
         * course of it that [hidden], the course numbers hidden in the term, does not name: the
         * backend drops a course a roster leaves out, so a course whose lookup failed with no copy
         * to stand in must not lose its row. A hidden course goes in no upload, and its row has
         * its tombstone, which the backend keeps out of the roster.
         *
         * A roster also leaves out the copies merged down from the server that it does not list:
         * the backend enrolls whatever a roster lists, and such a copy is a course course
         * selection no longer lists, or never did here. A course added here by hand still goes,
         * as the backend never drops a hand-added one.
         *
         * As iOS `AppServiceBridge.uploadVisibleCourses` and `uploadedRoster` (tigerduck-app#914),
         * whose roster upload carries only the fetched courses.
         */
        fun of(courses: List<Course>, semester: String, roster: Collection<String>?, hidden: Set<String>): CourseUpload {
            if (roster == null) return CourseUpload(courses, emptyList())
            val listed = courses.mapTo(mutableSetOf()) { it.courseNo }
            if (!roster.all { it in listed || it in hidden }) return CourseUpload(courses, emptyList())
            val rosterNos = roster.toSet()
            val sent = courses.filter { !(it.isManual && !it.moodleIdNumber.isNullOrEmpty() && it.courseNo !in rosterNos) }
            return CourseUpload(sent, listOf(semester))
        }
    }
}
