package org.ntust.app.tigerduck.data

import org.ntust.app.tigerduck.data.model.Assignment

/**
 * The assignments that still ask something of the user, and so the only ones
 * a reminder, the Live Update or a "has assignments" badge may bring up.
 *
 * Ignoring an assignment, or marking it done, is the user saying they do not
 * want to hear about it again; Moodle's own submission flag says the same.
 * Matches the Apple app's `isOutstanding`.
 */
object OutstandingAssignments {

    fun isOutstanding(
        assignment: Assignment,
        ignoredIds: Set<String>,
        markedCompletedIds: Set<String>,
    ): Boolean =
        !assignment.isCompleted &&
            assignment.assignmentId !in ignoredIds &&
            assignment.assignmentId !in markedCompletedIds

    /** [all] less everything [isOutstanding] rules out, in the same order. */
    fun of(
        all: List<Assignment>,
        ignoredIds: Set<String>,
        markedCompletedIds: Set<String>,
    ): List<Assignment> = all.filter { isOutstanding(it, ignoredIds, markedCompletedIds) }

    /** The courses with anything outstanding: the ones a "has assignments" badge marks. */
    fun courseNos(
        all: List<Assignment>,
        ignoredIds: Set<String>,
        markedCompletedIds: Set<String>,
    ): Set<String> = all.asSequence()
        .filter { isOutstanding(it, ignoredIds, markedCompletedIds) }
        .mapTo(mutableSetOf()) { it.courseNo }
}
