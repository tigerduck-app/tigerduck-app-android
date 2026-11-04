// Decides what this device's assignment overrides should be after a full
// sync, given what the server has and what this device has on disk.
//
// An "override" is the user saying something about an assignment that Moodle
// does not know: 已忽略 (ignored) or 標示為完成 (locally_completed). Both are
// per-device gestures that sync across a user's devices through the backend,
// which settles each by its stamp (LWW D12).
//
// Extracted from HomeViewModel.syncOverridesFromBackend so the decisions are
// reachable without a network, a database, or a ViewModel. The rules here are
// the ones that decide whether a user's tap survives a sync, so they should
// stay pure: no I/O, no clock, no Android types.

package org.ntust.app.tigerduck.ui.screen.home

import org.ntust.app.tigerduck.data.cache.AssignmentMarks

/**
 * One assignment whose local override disagrees with the server's.
 *
 * Surfaced to the user as a "keep mine / keep theirs" dialog, which is why
 * this carries display text ([kind], [label]) alongside the two statuses —
 * by the time the dialog renders, the assignment list it came from may have
 * been replaced. No pull offers one since every mark is settled by its stamp
 * (S4); the type and its dialog stay until the hold goes (Q).
 */
data class AssignmentSyncConflict(
    val id: String,
    val kind: String,
    val label: String,
    val localStatus: String,
    val serverStatus: String,
)

object AssignmentOverrideReconciler {

    /** No override recorded. Also what the server reports for an unknown id. */
    const val STATUS_NONE = "none"

    /** Ignored: hidden from the main list, and no longer reminded. */
    const val STATUS_IGNORED = "ignored"

    /** 標示為完成 — user says done, distinct from Moodle's own isCompleted. */
    const val STATUS_COMPLETED = "locally_completed"

    /** The re-enable conflict category these overrides belong to. */
    private const val REENABLE_CATEGORY = "assignments"

    /**
     * The status to send for each id when, with sync back on, the user keeps
     * this device's overrides: this device's own, then [STATUS_NONE] for an
     * id only the server marks, which the next full sync would otherwise take
     * as another device's mark and bring back. With empty server sets only
     * this device's overrides go, so Use Local falls back to the re-enable
     * check's snapshot when it cannot read the server again (see
     * `serverForUseLocal`).
     */
    fun keepLocal(
        localIgnored: Set<String>,
        localCompleted: Set<String>,
        serverIgnored: Set<String>,
        serverCompleted: Set<String>,
    ): Map<String, String> =
        localIgnored.associateWith { STATUS_IGNORED } +
            localCompleted.associateWith { STATUS_COMPLETED } +
            (serverIgnored + serverCompleted - localIgnored - localCompleted).associateWith { STATUS_NONE }

    /**
     * Whether a full sync leaves this device's overrides as they are: from
     * turning the Assignments sync back on until the re-enable check finds
     * no difference or the user's answer to it has been applied. Taking the
     * server's set in that window would answer for the user.
     */
    fun isHeldForReenable(pendingConflictCategories: Set<String>): Boolean =
        REENABLE_CATEGORY in pendingConflictCategories

    /**
     * Why a full sync leaves this device's marks as they are, or null to sync
     * them. [syncsMarks] is the Assignments sync switch: with it off, the
     * done and ignored marks are this device's own, as on iOS. The re-enable
     * check holds them too; see [isHeldForReenable].
     */
    fun reasonToLeaveMarks(syncsMarks: Boolean, pendingConflictCategories: Set<String>): String? = when {
        !syncsMarks -> "Assignments sync is off"
        isHeldForReenable(pendingConflictCategories) -> "re-enable check pending"
        else -> null
    }

    /** [id]'s mark in [marks]. */
    fun statusIn(marks: AssignmentMarks, id: String): String = statusOf(id, marks.ignored, marks.completed)

    /** [marks] with [id] at [status]: in the set it names and out of the other, or out of both for [STATUS_NONE]. */
    fun withStatus(marks: AssignmentMarks, id: String, status: String): AssignmentMarks = AssignmentMarks(
        ignored = if (status == STATUS_IGNORED) marks.ignored + id else marks.ignored - id,
        completed = if (status == STATUS_COMPLETED) marks.completed + id else marks.completed - id,
    )

    /**
     * Each id whose mark [to] gives otherwise than [from], at [to]'s: what a sync's result changes
     * of the marks it read, written one assignment at a time onto the marks as they stand then.
     */
    fun changes(from: AssignmentMarks, to: AssignmentMarks): Map<String, String> =
        (from.ignored + from.completed + to.ignored + to.completed)
            .filter { statusIn(from, it) != statusIn(to, it) }
            .associateWith { statusIn(to, it) }

    /** [marks] with each of [changes] made, but those of [held], whose mark this phone keeps as it stands. */
    fun applied(marks: AssignmentMarks, changes: Map<String, String>, held: Set<String>): AssignmentMarks =
        changes.entries.fold(marks) { m, (id, status) -> if (id in held) m else withStatus(m, id, status) }

    private fun statusOf(id: String, ignored: Set<String>, completed: Set<String>): String = when (id) {
        in ignored -> STATUS_IGNORED
        in completed -> STATUS_COMPLETED
        else -> STATUS_NONE
    }
}
