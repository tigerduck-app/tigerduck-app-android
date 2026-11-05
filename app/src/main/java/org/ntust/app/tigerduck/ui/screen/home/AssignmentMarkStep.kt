package org.ntust.app.tigerduck.ui.screen.home

import android.util.Log
import kotlinx.coroutines.flow.update
import org.ntust.app.tigerduck.data.cache.AssignmentMarks
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.UnsentMark
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.SyncCategory
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.SyncPolicies
import org.ntust.app.tigerduck.sync.reconcile.PullHolds

/**
 * The assignment-mark step of Home's full sync ([HomeBackendSync.pull]): I/O around the rules in
 * [AssignmentOverrideReconciler], over small seams so the sequencing is tested too.
 *
 * [switchOn] is the Assignments sync switch, read again at each point where
 * it decides something: with it off, the done and ignored marks are this
 * phone's own, as on iOS (tigerduck-app-android#188, #209), and one turned
 * off while a sync ran stops that sync from writing them.
 */
internal class AssignmentMarkStep(
    private val switchOn: () -> Boolean,
    private val pendingConflicts: () -> Set<String>,
    private val store: MarkStore,
) {
    /** This phone's marks on disk. */
    fun interface MarkStore {
        /**
         * Writes the marks as [change] makes them of the marks as stored and of those waiting to go
         * up, all read in one step with every other write of them ([DataCache.updateAssignmentMarks]).
         * The marks as they stand after, or null once [signIn] has ended.
         */
        suspend fun update(signIn: SignIn, change: (AssignmentMarks, List<UnsentMark>) -> AssignmentMarks): AssignmentMarks?
    }

    /**
     * A pull's marks (LWW D12): every mark the snapshot's, one it lacks or holds as `none` cleared,
     * but each this phone keeps, decided in the write's own step: tapped, waiting to go up, or
     * acknowledged since the snapshot began ([holds]). No conflict is offered and nothing goes up
     * as a first sync: a category's first sync queues this phone's marks as the oldest (LWW D13).
     * Returns whether the marks were taken, which the caller records in
     * `AppPreferences.assignmentMarksSyncedOnce`. [syncsMarks] is the switch as the sync read it
     * with the other categories; [signIn] the sign-in it began under.
     */
    suspend fun pull(
        state: HomeSyncState,
        result: BackendSyncResult,
        syncsMarks: Boolean,
        signIn: SignIn,
        holds: PullHolds,
    ): Boolean {
        if (!syncsMarks) {
            Log.i(TAG, "[Sync] leaving assignment marks alone: Assignments sync is off")
            return false
        }
        AssignmentOverrideReconciler.reasonToLeaveMarks(true, pendingConflicts())?.let { reason ->
            Log.i(TAG, "[Sync] leaving assignment marks alone: $reason")
            return false
        }
        val server = AssignmentMarks(result.ignoredIds, result.completedIds)
        // Read again: switched off since, the marks are this phone's own.
        if (!switchOn()) return false
        commitChanges(state, signIn, kept = { holds.keeps(SyncField.mark(it)) }) { stored -> AssignmentOverrideReconciler.changes(stored, server) }
        return true
    }

    // The changes [decide] makes of the marks as they stand, one assignment at a time, but over a
    // mark this phone holds as the write runs: one tapped, its PATCH under way
    // ([HomeSyncState.pendingOverrides]) or waiting, or [kept]. Home then shows the marks as written,
    // but each it holds as it shows it. What Home shows is the session's on screen: a run past its
    // sign-in changes none of it.
    private suspend fun commitChanges(
        state: HomeSyncState,
        signIn: SignIn,
        kept: (String) -> Boolean,
        decide: (stored: AssignmentMarks) -> Map<String, String>,
    ) {
        var held = emptySet<String>()
        val written = store.update(signIn) { stored, waiting ->
            val changes = decide(stored)
            held = changes.keys.filterTo(HashSet()) { id ->
                id in state.pendingOverrides || waiting.any { it.assignmentId == id } || kept(id)
            }
            AssignmentOverrideReconciler.applied(stored, changes, held)
        } ?: return
        signIn.ifCurrent {
            val skip = held + state.pendingOverrides
            state.ignoredAssignmentIds.update { shown -> written.ignored - skip + (shown intersect skip) }
            state.markedCompletedIds.update { shown -> written.completed - skip + (shown intersect skip) }
        }
    }

    companion object {
        private const val TAG = "HomeViewModel"

        /** The step over this phone's preferences and files, as Home's sync runs it. */
        fun over(prefs: AppPreferences, dataCache: DataCache) = SyncPolicies(prefs).let { policies ->
            AssignmentMarkStep(
                switchOn = { SyncCategory.ASSIGNMENTS in policies.current.switchedOn },
                pendingConflicts = { policies.current.held.mapTo(HashSet()) { it.stored } },
                store = MarkStore { signIn, change -> dataCache.updateAssignmentMarks(signIn, change) },
            )
        }
    }
}
