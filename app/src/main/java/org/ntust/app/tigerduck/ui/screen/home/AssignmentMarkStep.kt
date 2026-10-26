package org.ntust.app.tigerduck.ui.screen.home

import android.util.Log
import org.ntust.app.tigerduck.data.UnsentMarks
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.BackendSyncResult

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
    private val send: suspend (assignmentId: Int, status: String, signIn: Int) -> Unit,
    /**
     * Marks tapped here whose PATCH has not landed (`UnsentMarks`): kept at
     * this phone's value like a PATCH still in flight, since the server has
     * not heard of them.
     */
    private val unsent: suspend () -> Set<String>,
    /** `UnsentMarks.keep`: a first-sync mark that did not land waits there. */
    private val keepUnsent: suspend (assignmentId: String, status: String, generation: Int) -> Unit,
) {
    /** This phone's marks on disk. */
    interface MarkStore {
        suspend fun ignored(): Set<String>
        suspend fun completed(): Set<String>
        suspend fun replace(ignored: Set<String>, completed: Set<String>)
    }

    /**
     * Syncs the marks with [result]. Returns whether this account's marks
     * have now been through a sync here — taken from the server, or every
     * one sent up as the first sync — which the caller records in
     * `AppPreferences.assignmentMarksSyncedOnce`. [syncsMarks] is the switch
     * as the sync read it with the other categories; [signIn] the sign-in it
     * began under (`AuthTokenManager.signInGeneration`), which the first-sync
     * upload is pinned to; [generation] the data generation it began under,
     * which a first-sync mark that did not land is kept for.
     */
    suspend fun pull(
        state: HomeSyncState,
        result: BackendSyncResult,
        syncsMarks: Boolean,
        signIn: Int,
        generation: Int,
    ): Boolean {
        if (!syncsMarks) {
            Log.i(TAG, "[Sync] leaving assignment marks alone: Assignments sync is off")
            return false
        }
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
                        keepUnsent(id, status, generation)
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
        commit(state, outcome)
        if (outcome.conflicts.isNotEmpty()) {
            state.pendingResult = result
            state.conflicts.value = outcome.conflicts
        }
        return true
    }

    /** Takes the server's marks of [result], the user having picked them in the conflict dialog. */
    suspend fun keepServer(state: HomeSyncState, result: BackendSyncResult) {
        // Switched off since the dialog opened, the marks are this phone's own.
        if (!switchOn()) return
        val outcome = AssignmentOverrideReconciler.serverWins(
            serverIgnored = result.ignoredIds,
            serverCompleted = result.completedIds,
            inFlightIgnored = state.ignoredAssignmentIds.value,
            inFlightCompleted = state.markedCompletedIds.value,
            pendingOverrides = state.pendingOverrides + unsent(),
        )
        commit(state, outcome)
    }

    // The mark Home shows for [id] now; taps change these at once.
    private fun statusOn(state: HomeSyncState, id: String): String = when (id) {
        in state.ignoredAssignmentIds.value -> AssignmentOverrideReconciler.STATUS_IGNORED
        in state.markedCompletedIds.value -> AssignmentOverrideReconciler.STATUS_COMPLETED
        else -> AssignmentOverrideReconciler.STATUS_NONE
    }

    private suspend fun commit(state: HomeSyncState, outcome: AssignmentOverrideReconciler.Outcome) {
        store.replace(outcome.ignored, outcome.completed)
        state.ignoredAssignmentIds.value = outcome.ignored
        state.markedCompletedIds.value = outcome.completed
    }

    companion object {
        private const val TAG = "HomeViewModel"

        /** The step over this phone's preferences and files, as Home's sync runs it. */
        fun over(
            prefs: AppPreferences,
            dataCache: DataCache,
            unsentMarks: UnsentMarks,
            send: suspend (assignmentId: Int, status: String, signIn: Int) -> Unit,
        ) = AssignmentMarkStep(
            switchOn = { prefs.syncAssignments },
            pendingConflicts = { prefs.pendingConflictCategories },
            syncedBefore = { prefs.assignmentMarksSyncedOnce },
            store = object : MarkStore {
                override suspend fun ignored() = dataCache.loadIgnoredAssignments()
                override suspend fun completed() = dataCache.loadMarkedCompletedAssignments()
                override suspend fun replace(ignored: Set<String>, completed: Set<String>) {
                    dataCache.replaceIgnoredAssignments(ignored)
                    dataCache.replaceMarkedCompletedAssignments(completed)
                }
            },
            send = send,
            unsent = unsentMarks::ids,
            keepUnsent = unsentMarks::keep,
        )
    }
}
