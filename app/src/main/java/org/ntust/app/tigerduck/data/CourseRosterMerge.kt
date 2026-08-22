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
import java.util.Calendar

object CourseRosterMerge {

    /**
     * Merge the two enrolment sources into one ordered, de-duplicated roster.
     *
     * NTUST's 選課 list comes first because it is the authoritative
     * enrolment record and its order is the one the user recognises; Moodle
     * contributes anything 選課 missed (cross-listed courses, late adds).
     * Insertion-ordered and de-duplicated, so a course in both appears once,
     * in its 選課 position.
     *
     * [selectionCourseNos] is null when the 選課 scrape failed — distinct
     * from empty, which means "asked, enrolled in nothing".
     */
    fun rosterOrder(
        selectionCourseNos: List<String>?,
        moodleForSemester: List<MoodleEnrolledCourse>,
    ): List<String> = LinkedHashSet<String>().apply {
        selectionCourseNos?.forEach { add(it) }
        moodleForSemester.forEach { add(it.courseNo) }
    }.toList()

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

    /**
     * `java.util.Calendar`'s Sunday-first weekday to the Monday-first index
     * course schedules are keyed by (Mon=1 … Sun=7).
     */
    fun weekdayIndex(calendarDayOfWeek: Int): Int = when (calendarDayOfWeek) {
        Calendar.MONDAY -> 1
        Calendar.TUESDAY -> 2
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4
        Calendar.FRIDAY -> 5
        Calendar.SATURDAY -> 6
        Calendar.SUNDAY -> 7
        else -> 1
    }
}
