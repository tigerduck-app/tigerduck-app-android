// Which assignments Home shows, and in what order — extracted from
// HomeViewModel so the rules can be tested without a ViewModel.
//
// The ordering is not obvious and is easy to regress: the 全部 tab pins
// not-yet-due work at the top (soonest first) and puts everything already
// past its due date below it in reverse order, so "what just went overdue"
// sits at the boundary rather than at the bottom of a long history.

package org.ntust.app.tigerduck.ui.screen.home

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
    ): List<Assignment> = all.filter {
        it.courseNo == courseNo &&
            !it.isCompleted &&
            it.assignmentId !in ignoredIds &&
            it.assignmentId !in markedCompletedIds
    }

    /**
     * Epoch millis from the backend's ISO-8601 timestamps, which are UTC and
     * may carry a fractional part this drops. Returns 0 for anything
     * unparseable — callers compare it against a stored watermark, where 0
     * reads as "no information" and skips the reset rather than triggering one.
     */
    fun parseIsoTimestamp(s: String): Long {
        if (s.isBlank()) return 0L
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(s.take(19))?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
