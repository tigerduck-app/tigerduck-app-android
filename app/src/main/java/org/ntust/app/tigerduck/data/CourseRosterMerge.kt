// Rules for folding a freshly fetched roster onto what is already known.
//
// Home and the class table both pull the same two enrolment sources — the
// NTUST 選課 list and the Moodle enrolment list — and both have to answer the
// same questions about the result. Shared rather than duplicated, and placed
// under `data` so neither screen owns it: a class-table file importing
// something called `Home...` is the kind of dependency that gets copied
// rather than reused.
//
// The two copies had already drifted. The class table's assignment merge
// re-marked *every* previously-completed id; Home only rescued ids the remote
// had not already confirmed. Same result either way, but only because
// `copy(isCompleted = true)` on an already-true value is a no-op.

package org.ntust.app.tigerduck.data

import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse

object CourseRosterMerge {

    /**
     * The course numbers a term renders, in source priority order, deduped.
     *
     * **A non-empty 選課 answer owns its term outright.** Moodle keeps an
     * enrolment after the student drops the class and gains one only once
     * the teacher opens the course, so during 加退選 the two disagree in both
     * directions: a dropped course lingers there for days, and a just-added
     * one is missing. Letting Moodle top up the roster put the dropped course
     * back on the timetable — which is why it no longer does. A course 選課
     * lists but Moodle has not caught up with still renders; it simply has no
     * Moodle link until the binding resolves.
     *
     * [selectionCourseNos] is null when the scrape failed and empty when 選課
     * serves another term or the page yielded nothing. Both mean the same
     * thing here — **Moodle is the source** — because the scrape is a regex
     * over HTML, and a layout change returns zero matches rather than an
     * error. "Everything was dropped" and "the parser broke" are
     * indistinguishable, and only one of them may blank a timetable.
     *
     * Matches iOS `AppServiceBridge.enrolledCourseNos`, minus its transcript
     * source, which Android has no equivalent of.
     */
    fun rosterOrder(
        selectionCourseNos: List<String>?,
        moodleForSemester: List<MoodleEnrolledCourse>,
    ): List<String> {
        val candidates = if (!selectionCourseNos.isNullOrEmpty()) {
            selectionCourseNos
        } else {
            moodleForSemester.map { it.courseNo }
        }
        return LinkedHashSet<String>().apply {
            candidates.forEach { if (it.isNotEmpty()) add(it) }
        }.toList()
    }

    /**
     * The courses 選課 has stopped listing for one term, updated from one
     * successful, non-empty answer.
     *
     * Only a drop this device actually witnessed goes in: [localPortalNos]
     * are the portal courses it is holding right now, so the difference
     * against [roster] is exactly what 加退選 just removed. That temporal
     * check is the whole point — a snapshot cannot tell a dropped course
     * from a course another device added by hand, since neither is in 選課
     * and the uploaded rows look identical. A manual course from elsewhere
     * was never in this device's roster, so it is never in the difference.
     *
     * Anything 選課 lists again is cleared, so re-adding a course in 加退選
     * brings it straight back.
     *
     * Call only with a non-empty [roster]: an empty answer means the scrape
     * was not consulted or the page drifted, and would read as "everything
     * was dropped". See [rosterOrder].
     */
    fun selectionDrops(
        previous: Set<String>,
        localPortalNos: List<String>,
        roster: List<String>,
    ): Set<String> {
        if (roster.isEmpty()) return previous
        val enrolled = roster.toSet()
        return (previous + localPortalNos).filterNot { it in enrolled }.toSet()
    }

    /**
     * Moodle enrolments that belong to [semester] and carry a usable course
     * number. Moodle returns every course the account can see, including past
     * terms and admin shells with no NTUST course number at all.
     */
    fun moodleCoursesFor(
        semester: String,
        enrolled: List<MoodleEnrolledCourse>?,
    ): List<MoodleEnrolledCourse> = enrolled
        .orEmpty()
        .filter { it.semesterCode == semester && it.courseNo.isNotEmpty() }

    /**
     * Don't let a fetch walk back a submission we already confirmed.
     *
     * Moodle reports submission status through a separate call per course; if
     * one fails, the assignment comes back `isCompleted = false` rather than
     * unknown. Treating that as truth flips a submitted item back to
     * outstanding and re-arms its notification. Remote still wins when it
     * says `true`, so a genuine un-submit is picked up.
     */
    fun preserveConfirmedSubmissions(
        remote: List<Assignment>,
        previouslyCompleted: Set<String>,
    ): List<Assignment> = remote.map { assignment ->
        if (!assignment.isCompleted && assignment.assignmentId in previouslyCompleted) {
            assignment.copy(isCompleted = true)
        } else {
            assignment
        }
    }

    /** Ids of everything already recorded as submitted, for the call above. */
    fun completedIds(assignments: List<Assignment>): Set<String> =
        assignments.filter { it.isCompleted }.mapTo(mutableSetOf()) { it.assignmentId }

}
