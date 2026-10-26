package org.ntust.app.tigerduck.data.model

import org.ntust.app.tigerduck.shared.clock.AppClock
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

/** Judged against the student's own dates, an extension included: [Assignment.studentDueDate]. */
fun Assignment.status(now: Date = Date(AppClock.nowMillis())): AssignmentStatus {
    val due = studentDueDate
    if (isCompleted) {
        return if (submittedAt != null && submittedAt.after(due)) {
            AssignmentStatus.SUBMITTED_LATE
        } else {
            AssignmentStatus.SUBMITTED
        }
    }
    if (!due.before(now)) return AssignmentStatus.PENDING
    val cutoff = studentCutoffDate
    if (cutoff != null && now.after(cutoff)) return AssignmentStatus.OVERDUE_REJECTED
    return AssignmentStatus.OVERDUE_ACCEPTABLE
}
