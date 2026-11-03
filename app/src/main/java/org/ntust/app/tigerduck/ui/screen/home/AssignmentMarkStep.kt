package org.ntust.app.tigerduck.ui.screen.home

import android.util.Log
import kotlinx.coroutines.flow.update
import org.ntust.app.tigerduck.data.UnsentMarks
import org.ntust.app.tigerduck.data.cache.AssignmentMarks
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.UnsentMark
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.reconcile.PullHolds

/**
 * The assignment-mark step of Home's full sync ([HomeBackendSync.pull]), and
 * the "keep the server's version" answer to its conflict dialog: I/O around
 * the rules in [AssignmentOverrideReconciler], over small seams so the
 * sequencing is tested too.
 *
 * [switchOn] is the Assignments sync switch, read again at each point where
 * it decides something: with it off, the done and ignored marks are this
 * phone's own, as on iOS (tigerduck-app-android#188, #209), and one turned
 * off while a sync ran stops that sync from writing them.
 */
internal class AssignmentMarkStep(
    private val switchOn: () -> Boolean,
    private val pendingConflicts: () -> Set<String>,
    private val syncedBefore: () -> Boolean,
    private val store: MarkStore,
    private val send: suspend (assignmentId: Int, status: String, signIn: SignIn) -> Unit,
    /**
     * Marks tapped here whose PATCH has not landed (`UnsentMarks`): kept at
     * this phone's value like a PATCH still in flight, since the server has
     * not heard of them.
     */
    private val unsent: suspend () -> Set<String>,
    /** `UnsentMarks.keep`: a first-sync mark that did not land waits there. */
    private val keepUnsent: suspend (assignmentId: String, status: String, signIn: SignIn) -> Unit,
) {
    /** This phone's marks on disk. */
    interface MarkStore {
        suspend fun ignored(): Set<String>
        suspend fun completed(): Set<String>
        suspend fun replace(ignored: Set<String>, completed: Set<String>, signIn: SignIn)

        /**
         * Writes the marks as [change] makes them of the marks as stored and of those waiting to go
         * up, all read in one step with every other write of them ([DataCache.updateAssignmentMarks]).
         * The marks as they stand after, or null once [signIn] has ended. A store with no such step
         * reads, changes and [replace]s, waiting marks unknown.
         */
        suspend fun update(signIn: SignIn, change: (AssignmentMarks, List<UnsentMark>) -> AssignmentMarks): AssignmentMarks? =
            change(AssignmentMarks(ignored(), completed()), emptyList()).also { replace(it.ignored, it.completed, signIn) }
    }

    /**
     * Syncs the marks with [result]. Returns whether this account's marks
     * have now been through a sync here — taken from the server, or every
     * one sent up as the first sync — which the caller records in
     * `AppPreferences.assignmentMarksSyncedOnce`. [syncsMarks] is the switch
     * as the sync read it with the other categories; [signIn] the sign-in it
     * began under, which the first-sync upload is pinned to and a first-sync
     * mark that did not land is kept for.
     */
    suspend fun pull(
        state: HomeSyncState,
        result: BackendSyncResult,
        syncsMarks: Boolean,
        signIn: SignIn,
        holds: PullHolds? = null,
    ): Boolean {
        if (!syncsMarks) {
            Log.i(TAG, "[Sync] leaving assignment marks alone: Assignments sync is off")
            return false
        }
        if (holds != null) return pullStamped(state, result, holds, signIn)
        val localIgnored = store.ignored()
        val localCompleted = store.completed()

        if (AssignmentOverrideReconciler.isFirstUpload(
                syncedBefore = syncedBefore(),
                serverIgnored = result.ignoredIds,
                serverCompleted = result.completedIds,
                localIgnored = localIgnored,
                localCompleted = localCompleted,
            )
        ) {
            // Pinned to [signIn]: after a sign-out the rest go without a
            // token, not as the next account's marks.
            val allSent = sendFirstSyncMarks(
                localIgnored,
                localCompleted,
                keep = { id, status ->
                    // Only while it is still this phone's mark: a tap made
                    // while the PATCH was out is newer, and goes up itself.
                    if (id !in state.pendingOverrides && statusOn(state, id) == status) {
                        keepUnsent(id, status, signIn)
                    }
                },
            ) { id, status -> send(id, status, signIn) }
            // Switched off meanwhile, the rest were never sent.
            return allSent && switchOn()
        }

        // After the first-sync upload, which does not wait for the re-enable
        // check (decided for both apps, as on iOS): only taking the server's
        // marks over this phone's waits for the user's answer.
        AssignmentOverrideReconciler.reasonToLeaveMarks(syncsMarks, pendingConflicts())?.let { reason ->
            Log.i(TAG, "[Sync] leaving assignment marks alone: $reason")
            return false
        }

        val byId = state.allAssignments.value.associateBy { it.assignmentId }
        val outcome = AssignmentOverrideReconciler.reconcile(
            serverIgnored = result.ignoredIds,
            serverCompleted = result.completedIds,
            localIgnored = localIgnored,
            localCompleted = localCompleted,
            inFlightIgnored = state.ignoredAssignmentIds.value,
            inFlightCompleted = state.markedCompletedIds.value,
            pendingOverrides = state.pendingOverrides + unsent(),
            labelFor = { byId[it]?.title },
        )
        // Read again: switched off since, the marks are this phone's own.
        if (!switchOn()) return false
        commit(state, AssignmentMarks(localIgnored, localCompleted), outcome, signIn)
        if (outcome.conflicts.isNotEmpty()) {
            signIn.ifCurrent {
                state.pendingResult = result
                state.conflicts.value = outcome.conflicts
            }
        }
        return true
    }

    /**
     * A `/v4` pull's marks (LWW D12): every mark the snapshot's, one it lacks or holds as `none`
     * cleared, but each this phone keeps, decided in the write's own step: tapped, waiting to go up,
     * or acknowledged since the snapshot began ([holds]). No conflict is offered and nothing goes up
     * as a first sync: a category's first sync queues this phone's marks as the oldest (LWW D13).
     */
    private suspend fun pullStamped(state: HomeSyncState, result: BackendSyncResult, holds: PullHolds, signIn: SignIn): Boolean {
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

    /** Takes the server's marks of [result], the user having picked them in the conflict dialog. */
    suspend fun keepServer(state: HomeSyncState, result: BackendSyncResult, signIn: SignIn) {
        // Switched off since the dialog opened, the marks are this phone's own.
        if (!switchOn()) return
        val local = AssignmentMarks(store.ignored(), store.completed())
        val outcome = AssignmentOverrideReconciler.serverWins(
            serverIgnored = result.ignoredIds,
            serverCompleted = result.completedIds,
            inFlightIgnored = state.ignoredAssignmentIds.value,
            inFlightCompleted = state.markedCompletedIds.value,
            pendingOverrides = state.pendingOverrides + unsent(),
        )
        commit(state, local, outcome, signIn)
    }

    // The mark Home shows for [id] now; taps change these at once.
    private fun statusOn(state: HomeSyncState, id: String): String = when (id) {
        in state.ignoredAssignmentIds.value -> AssignmentOverrideReconciler.STATUS_IGNORED
        in state.markedCompletedIds.value -> AssignmentOverrideReconciler.STATUS_COMPLETED
        else -> AssignmentOverrideReconciler.STATUS_NONE
    }

    // The marks [outcome] changes of [local], the ones the step read, each written onto the marks
    // as they stand, as Home's taps are, never as a whole set: a tap during the pull stays, and so
    // does the pull's mark of another assignment. Not over a mark tapped since the read, whose PATCH
    // is under way or waits, which the step's read may predate: decided in the write's own step.
    private suspend fun commit(
        state: HomeSyncState,
        local: AssignmentMarks,
        outcome: AssignmentOverrideReconciler.Outcome,
        signIn: SignIn,
    ) = AssignmentOverrideReconciler.changes(local, AssignmentMarks(outcome.ignored, outcome.completed)).let { changes ->
        commitChanges(state, signIn) { changes }
    }

    // The changes [decide] makes of the marks as they stand, one assignment at a time, but over a
    // mark this phone holds as the write runs: one tapped, its PATCH under way
    // ([HomeSyncState.pendingOverrides]) or waiting, or [kept]. Home then shows the marks as written,
    // but each it holds as it shows it. What Home shows is the session's on screen: a run past its
    // sign-in changes none of it.
    private suspend fun commitChanges(
        state: HomeSyncState,
        signIn: SignIn,
        kept: (String) -> Boolean = { false },
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
        fun over(
            prefs: AppPreferences,
            dataCache: DataCache,
            unsentMarks: UnsentMarks,
            send: suspend (assignmentId: Int, status: String, signIn: SignIn) -> Unit,
        ) = AssignmentMarkStep(
            switchOn = { prefs.syncAssignments },
            pendingConflicts = { prefs.school.pendingConflictCategories.get() },
            syncedBefore = { prefs.school.assignmentMarksSyncedOnce.get() },
            store = object : MarkStore {
                override suspend fun ignored() = dataCache.loadIgnoredAssignments()
                override suspend fun completed() = dataCache.loadMarkedCompletedAssignments()
                override suspend fun replace(ignored: Set<String>, completed: Set<String>, signIn: SignIn) {
                    dataCache.replaceIgnoredAssignments(ignored, signIn)
                    dataCache.replaceMarkedCompletedAssignments(completed, signIn)
                }
                override suspend fun update(signIn: SignIn, change: (AssignmentMarks, List<UnsentMark>) -> AssignmentMarks) =
                    dataCache.updateAssignmentMarks(signIn, change)
            },
            send = send,
            unsent = unsentMarks::ids,
            keepUnsent = unsentMarks::keep,
        )
    }
}
