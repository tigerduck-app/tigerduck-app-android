// Which assignments Home shows, and in what order — extracted from
// HomeViewModel so the rules can be tested without a ViewModel.
//
// The ordering is not obvious and is easy to regress: the 全部 tab pins
// not-yet-due work at the top (soonest first) and puts everything already
// past its due date below it in reverse order, so "what just went overdue"
// sits at the boundary rather than at the bottom of a long history.

package org.ntust.app.tigerduck.ui.screen.home

import java.util.Date
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.AssignmentFilter

object HomeAssignmentFilters {

    /**
     * "Effectively done" is either side saying so: Moodle reports a
     * submission, or the user swiped it done locally. Both are treated the
     * same for filtering and sorting — a locally-marked item must not
     * reappear in 未完成 just because Moodle has not caught up.
     */
    fun isDone(assignment: Assignment, markedCompletedIds: Set<String>): Boolean =
        assignment.isCompleted || assignment.assignmentId in markedCompletedIds

    /** The list for [filter], ordered as the tab expects. */
    fun visible(
        all: List<Assignment>,
        ignoredIds: Set<String>,
        markedCompletedIds: Set<String>,
        filter: AssignmentFilter,
        now: Date,
    ): List<Assignment> = when (filter) {
        AssignmentFilter.INCOMPLETE ->
            all.filter { !isDone(it, markedCompletedIds) && it.assignmentId !in ignoredIds }
                .sortedBy { it.dueDate }

        AssignmentFilter.ALL -> {
            // 全部 deliberately includes ignored and done items, matching iOS
            // allCandidates(). Future first (soonest on top), then past (most
            // recently due first).
            val (future, past) = all.partition { !it.dueDate.before(now) }
            future.sortedBy { it.dueDate } + past.sortedByDescending { it.dueDate }
        }

        AssignmentFilter.IGNORED ->
            all.filter { it.assignmentId in ignoredIds }.sortedBy { it.dueDate }
    }

    /**
     * Assignments for one course that still need doing. Drives the course
     * tile's "has work outstanding" dot, so ignored items must not count —
     * ignoring is the user saying they do not want to be reminded.
     */
    fun unfinishedFor(
        all: List<Assignment>,
        courseNo: String,
        ignoredIds: Set<String>,
        markedCompletedIds: Set<String>,
    ): List<Assignment> = all.filter { it.isUnfinishedFor(courseNo, ignoredIds, markedCompletedIds) }

    /**
     * Whether [unfinishedFor] would return anything, without building the list.
     *
     * The course tile only needs the dot, and Home asks once per tile on every
     * recomposition of the today-courses carousel — so the list version scans
     * the whole assignment set and allocates, per tile, to answer a yes/no.
     * Shares [isUnfinishedFor] with [unfinishedFor] so the two cannot drift.
     */
    fun anyUnfinishedFor(
        all: List<Assignment>,
        courseNo: String,
        ignoredIds: Set<String>,
        markedCompletedIds: Set<String>,
    ): Boolean = all.any { it.isUnfinishedFor(courseNo, ignoredIds, markedCompletedIds) }

    private fun Assignment.isUnfinishedFor(
        courseNo: String,
        ignoredIds: Set<String>,
        markedCompletedIds: Set<String>,
    ): Boolean =
        this.courseNo == courseNo &&
            !isCompleted &&
            assignmentId !in ignoredIds &&
            assignmentId !in markedCompletedIds

}
