package org.ntust.app.tigerduck.data.model

import java.util.Date

/**
 * Presentation state for an assignment. Ported from iOS
 * (`Models/Domain/AssignmentStatus.swift`) so home, live updates and tests
 * all resolve status through the same rule instead of re-deriving from
 * raw dates.
 */
enum class AssignmentStatus {
    /** Not submitted, before the due date. */
    PENDING,

    /** Submitted on or before the due date. */
    SUBMITTED,

    /** Submitted after the due date (Moodle still recorded it). */
    SUBMITTED_LATE,

    /** Past due, still accepting late submissions. */
    OVERDUE_ACCEPTABLE,

    /** Past the hard cutoff — Moodle rejects further submissions. */
    OVERDUE_REJECTED,
}

fun Assignment.status(now: Date = Date()): AssignmentStatus {
    if (isCompleted) {
        return if (submittedAt != null && submittedAt.after(dueDate)) {
            AssignmentStatus.SUBMITTED_LATE
        } else {
            AssignmentStatus.SUBMITTED
        }
    }
    if (!dueDate.before(now)) return AssignmentStatus.PENDING
    if (cutoffDate != null && now.after(cutoffDate)) return AssignmentStatus.OVERDUE_REJECTED
    return AssignmentStatus.OVERDUE_ACCEPTABLE
}
