package org.ntust.app.tigerduck.ui.screen.home

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
 * The background worker's assignment-mark step: it takes the server's marks
 * over this phone's ([AssignmentOverrideReconciler.backgroundTakesMarks]),
 * with each mark still waiting to go up at the status its tap set
 * ([AssignmentOverrideReconciler.backgroundMarks]). It never sends marks up;
 * Home's sync does.
 *
 * Read again right before the write: the Assignments switch ([switchOn]),
 * since one turned off while the waiting marks were read makes the marks this
 * phone's own, and [marksVersion], since a tap made after this phone's marks
 * were read would be written over; the next sync merges it instead.
 */
internal class BackgroundMarkStep(
    private val switchOn: () -> Boolean,
    private val pendingConflicts: () -> Set<String>,
    private val syncedBefore: () -> Boolean,
    private val marksVersion: () -> Int,
    private val store: AssignmentMarkStep.MarkStore,
    private val unsent: suspend () -> Map<String, String>,
) {
    /** Whether the server's marks were [taken], and whether that [changed] this phone's. */
    data class Outcome(val taken: Boolean, val changed: Boolean)

    /**
     * [holds] is a `/v4` pull's (LWW D12), null for `/v3`'s rules; [syncedBefore] whether the
     * account's marks have synced here before, without which a `/v4` pull leaves them to Home's
     * first sync, which queues them as the oldest first (LWW D13, C2).
     */
    suspend fun pull(result: BackendSyncResult, signIn: SignIn, holds: PullHolds? = null, syncedBefore: Boolean = true): Outcome {
        if (holds != null) return pullStamped(result, signIn, holds, syncedBefore)
        val readAt = marksVersion()
        val localIgnored = store.ignored()
        val localCompleted = store.completed()
        val takes = AssignmentOverrideReconciler.backgroundTakesMarks(
            syncsMarks = switchOn(),
            pendingConflictCategories = pendingConflicts(),
            syncedBefore = syncedBefore(),
            serverIgnored = result.ignoredIds,
            serverCompleted = result.completedIds,
            localIgnored = localIgnored,
            localCompleted = localCompleted,
        )
        if (!takes) return NOT_TAKEN
        val marks = AssignmentOverrideReconciler.backgroundMarks(result.ignoredIds, result.completedIds, unsent())
        if (!switchOn() || marksVersion() != readAt) return NOT_TAKEN
        // In one step with every other write of the marks: a tap saved since the read, which the
        // version above may not have seen yet, leaves them to the next sync as well.
        val local = AssignmentMarks(localIgnored, localCompleted)
        var taken = false
        store.update(signIn) { stored, _ ->
            if (stored != local) return@update stored
            taken = true
            AssignmentMarks(marks.ignored, marks.completed)
        } ?: return NOT_TAKEN
        if (!taken) return NOT_TAKEN
        return Outcome(taken = true, changed = marks.ignored != localIgnored || marks.completed != localCompleted)
    }

    // Every mark the snapshot's, a missing one cleared, but each this phone keeps: waiting to go up,
    // or acknowledged since the snapshot began. Decided in the write's own step, with the switch
    // read again there: one turned off makes the marks this phone's own.
    private suspend fun pullStamped(result: BackendSyncResult, signIn: SignIn, holds: PullHolds, syncedBefore: Boolean): Outcome {
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
        fun over(prefs: AppPreferences, dataCache: DataCache, unsentMarks: UnsentMarks) = BackgroundMarkStep(
            switchOn = { prefs.syncAssignments },
            pendingConflicts = { prefs.school.pendingConflictCategories.get() },
            syncedBefore = { prefs.school.assignmentMarksSyncedOnce.get() },
            marksVersion = { dataCache.assignmentMarksVersion.value },
            store = object : AssignmentMarkStep.MarkStore {
                override suspend fun ignored() = dataCache.loadIgnoredAssignments()
                override suspend fun completed() = dataCache.loadMarkedCompletedAssignments()
                override suspend fun replace(ignored: Set<String>, completed: Set<String>, signIn: SignIn) {
                    dataCache.replaceIgnoredAssignments(ignored, signIn)
                    dataCache.replaceMarkedCompletedAssignments(completed, signIn)
                }
                override suspend fun update(signIn: SignIn, change: (AssignmentMarks, List<UnsentMark>) -> AssignmentMarks) =
                    dataCache.updateAssignmentMarks(signIn, change)
            },
            unsent = unsentMarks::statuses,
        )
    }
}
