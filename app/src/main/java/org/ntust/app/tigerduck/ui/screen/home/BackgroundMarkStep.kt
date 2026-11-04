package org.ntust.app.tigerduck.ui.screen.home

import org.ntust.app.tigerduck.data.cache.AssignmentMarks
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.reconcile.PullHolds

/**
 * The background worker's assignment-mark step: every mark the snapshot's, a missing one cleared,
 * but each this phone keeps ([pull]). It never sends marks up; Home's sync does.
 *
 * The Assignments switch ([switchOn]) is read again in the write's own step: one turned off while
 * the step ran makes the marks this phone's own.
 */
internal class BackgroundMarkStep(
    private val switchOn: () -> Boolean,
    private val pendingConflicts: () -> Set<String>,
    private val store: AssignmentMarkStep.MarkStore,
) {
    /** Whether the server's marks were [taken], and whether that [changed] this phone's. */
    data class Outcome(val taken: Boolean, val changed: Boolean)

    /**
     * [holds] is the pull's (LWW D12); [syncedBefore] whether the account's marks have synced here
     * before, without which the pull leaves them to Home's first sync, which queues them as the
     * oldest first (LWW D13, C2). Every mark the snapshot's, a missing one cleared, but each this
     * phone keeps: waiting to go up, or acknowledged since the snapshot began. Decided in the
     * write's own step, with the switch read again there.
     */
    suspend fun pull(result: BackendSyncResult, signIn: SignIn, holds: PullHolds, syncedBefore: Boolean): Outcome {
        if (!syncedBefore || AssignmentOverrideReconciler.reasonToLeaveMarks(switchOn(), pendingConflicts()) != null) return NOT_TAKEN
        val server = AssignmentMarks(result.ignoredIds, result.completedIds)
        var taken: Outcome = NOT_TAKEN
        store.update(signIn) { stored, waiting ->
            if (!switchOn()) return@update stored
            val changes = AssignmentOverrideReconciler.changes(stored, server)
            val held = waiting.mapNotNullTo(HashSet()) { it.assignmentId } + changes.keys.filter { holds.keeps(SyncField.mark(it)) }
            AssignmentOverrideReconciler.applied(stored, changes, held).also {
                taken = Outcome(taken = true, changed = it != stored)
            }
        } ?: return NOT_TAKEN
        return taken
    }

    companion object {
        private val NOT_TAKEN = Outcome(taken = false, changed = false)

        /** The step over this phone's preferences and files, as the background worker runs it. */
        fun over(prefs: AppPreferences, dataCache: DataCache) = BackgroundMarkStep(
            switchOn = { prefs.syncAssignments },
            pendingConflicts = { prefs.school.pendingConflictCategories.get() },
            store = AssignmentMarkStep.MarkStore { signIn, change -> dataCache.updateAssignmentMarks(signIn, change) },
        )
    }
}
