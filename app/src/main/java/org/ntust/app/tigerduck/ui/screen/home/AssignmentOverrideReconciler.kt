// Decides what this device's assignment overrides should be after a full
// sync, given what the server has, what this device has on disk, and what
// this device has in flight.
//
// An "override" is the user saying something about an assignment that Moodle
// does not know: 已忽略 (ignored) or 標示為完成 (locally_completed). Both are
// per-device gestures that sync across a user's devices through the backend,
// which makes them the one place where two devices can disagree about the
// same id — hence the conflict list this produces.
//
// Extracted from HomeViewModel.syncOverridesFromBackend so the decisions are
// reachable without a network, a database, or a ViewModel. The rules here are
// the ones that decide whether a user's tap survives a sync, so they should
// stay pure: no I/O, no clock, no Android types.

package org.ntust.app.tigerduck.ui.screen.home

/**
 * One assignment whose local override disagrees with the server's.
 *
 * Surfaced to the user as a "keep mine / keep theirs" dialog, which is why
 * this carries display text ([kind], [label]) alongside the two statuses —
 * by the time the dialog renders, the assignment list it came from may have
 * been replaced.
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

    /** 已忽略 — hidden from the main list, notifications become safety nets. */
    const val STATUS_IGNORED = "ignored"

    /** 標示為完成 — user says done, distinct from Moodle's own isCompleted. */
    const val STATUS_COMPLETED = "locally_completed"

    // Shown in the conflict dialog as the category of the conflicting item.
    // Hardcoded rather than a string resource, preserved as-is from the
    // original: the dialog interpolates it into a localized sentence, so a
    // non-Chinese user currently gets one Chinese word in an otherwise
    // translated line. Fixing that means a new string key, which is a
    // localization change and not this refactor's business.
    private const val KIND_ASSIGNMENT = "作業"

    /**
     * What this device should end up with, plus anything the user has to
     * decide. [ignored] and [completed] are safe to write immediately; the
     * conflicting ids are included in them at their *local* value, so a
     * pending decision never makes an override vanish from the UI while the
     * dialog is open.
     */
    data class Outcome(
        val ignored: Set<String>,
        val completed: Set<String>,
        val conflicts: List<AssignmentSyncConflict>,
    )

    /**
     * True when this device has overrides and the server has none at all.
     *
     * That combination means the account predates override sync (or the
     * backend was reset), so the correct move is to push local state up
     * rather than reconcile against an empty server — reconciling would read
     * every local override as "server says none" and delete it.
     */
    fun isFirstUpload(
        serverIgnored: Set<String>,
        serverCompleted: Set<String>,
        localIgnored: Set<String>,
        localCompleted: Set<String>,
    ): Boolean = serverIgnored.isEmpty() && serverCompleted.isEmpty() &&
        (localIgnored.isNotEmpty() || localCompleted.isNotEmpty())

    /**
     * Merge server and local overrides.
     *
     * Three rules, in order of how much trouble they save:
     *
     * 1. **In-flight ids are untouchable.** [pendingOverrides] holds ids whose
     *    PATCH to the backend has not come back yet. The server's answer for
     *    those is known-stale — it predates the tap — so they are skipped for
     *    conflict detection and their current in-memory value
     *    ([inFlightIgnored] / [inFlightCompleted]) is carried through
     *    verbatim, and the server's value for those ids is discarded rather
     *    than merged. Without this, tapping 已忽略 while a sync is in flight
     *    reverts under the user's finger — and, in the other direction,
     *    *un*-ignoring during a sync silently comes back.
     *
     * 2. **Only a real disagreement is a conflict.** Both sides must name a
     *    status, and they must differ. If one side says [STATUS_NONE] the
     *    other side simply wins — that is a new override propagating, not a
     *    contested one, and prompting for it would make routine sync feel
     *    broken.
     *
     * 3. **Local wins until the user says otherwise.** Conflicting ids are
     *    written into the returned sets at their local value. The user's own
     *    device keeps showing what they last chose; the server's version is
     *    only applied if they pick it in the dialog.
     *
     * [labelFor] resolves an assignment title for display and may return null
     * for an id this device has never seen — that happens when the conflict
     * comes from a course this device is not enrolled in.
     */
    fun reconcile(
        serverIgnored: Set<String>,
        serverCompleted: Set<String>,
        localIgnored: Set<String>,
        localCompleted: Set<String>,
        inFlightIgnored: Set<String>,
        inFlightCompleted: Set<String>,
        pendingOverrides: Set<String>,
        labelFor: (String) -> String?,
    ): Outcome {
        val conflicts = mutableListOf<AssignmentSyncConflict>()
        val allIds = serverIgnored + serverCompleted + localIgnored + localCompleted

        for (id in allIds) {
            if (id in pendingOverrides) continue
            val serverStatus = statusOf(id, serverIgnored, serverCompleted)
            val localStatus = statusOf(id, localIgnored, localCompleted)
            val contested = serverStatus != localStatus &&
                localStatus != STATUS_NONE && serverStatus != STATUS_NONE
            if (contested) {
                conflicts += AssignmentSyncConflict(
                    id = id,
                    kind = KIND_ASSIGNMENT,
                    label = labelFor(id) ?: "ID $id",
                    localStatus = localStatus,
                    serverStatus = serverStatus,
                )
            }
        }

        val contestedIds = conflicts.mapTo(mutableSetOf()) { it.id }
        // Drop the in-flight ids from the server's answer *before* merging,
        // then put this device's values back. Subtracting is what makes the
        // carve-out work in both directions: adding alone would re-apply an
        // override the user just cleared, because it would still be sitting
        // in the server's set.
        val ignored = (serverIgnored - contestedIds - pendingOverrides).toMutableSet()
        val completed = (serverCompleted - contestedIds - pendingOverrides).toMutableSet()
        ignored += inFlightIgnored.filter { it in pendingOverrides }
        completed += inFlightCompleted.filter { it in pendingOverrides }
        for (c in conflicts) {
            when (c.localStatus) {
                STATUS_IGNORED -> ignored += c.id
                STATUS_COMPLETED -> completed += c.id
            }
        }
        return Outcome(ignored = ignored, completed = completed, conflicts = conflicts)
    }

    /**
     * Non-conflicting merge, used when the user has already answered the
     * dialog with "keep the server's version".
     *
     * Same in-flight carve-out as [reconcile], and for the same reason: a tap
     * made while the dialog was open happened *after* the choice and is the
     * more recent statement of intent, so it survives "keep the server's
     * version" in both directions.
     */
    fun serverWins(
        serverIgnored: Set<String>,
        serverCompleted: Set<String>,
        inFlightIgnored: Set<String>,
        inFlightCompleted: Set<String>,
        pendingOverrides: Set<String>,
    ): Outcome = Outcome(
        ignored = (serverIgnored - pendingOverrides) +
            inFlightIgnored.filter { it in pendingOverrides },
        completed = (serverCompleted - pendingOverrides) +
            inFlightCompleted.filter { it in pendingOverrides },
        conflicts = emptyList(),
    )

    private fun statusOf(id: String, ignored: Set<String>, completed: Set<String>): String = when (id) {
        in ignored -> STATUS_IGNORED
        in completed -> STATUS_COMPLETED
        else -> STATUS_NONE
    }
}
