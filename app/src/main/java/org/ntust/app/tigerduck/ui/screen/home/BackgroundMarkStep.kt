package org.ntust.app.tigerduck.ui.screen.home

import org.ntust.app.tigerduck.data.UnsentMarks
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.BackendSyncResult

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

    suspend fun pull(result: BackendSyncResult): Outcome {
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
        store.replace(marks.ignored, marks.completed)
        return Outcome(taken = true, changed = marks.ignored != localIgnored || marks.completed != localCompleted)
    }

    companion object {
        private val NOT_TAKEN = Outcome(taken = false, changed = false)

        /** The step over this phone's preferences and files, as the background worker runs it. */
        fun over(prefs: AppPreferences, dataCache: DataCache, unsentMarks: UnsentMarks) = BackgroundMarkStep(
            switchOn = { prefs.syncAssignments },
            pendingConflicts = { prefs.pendingConflictCategories },
            syncedBefore = { prefs.assignmentMarksSyncedOnce },
            marksVersion = { dataCache.assignmentMarksVersion.value },
            store = object : AssignmentMarkStep.MarkStore {
                override suspend fun ignored() = dataCache.loadIgnoredAssignments()
                override suspend fun completed() = dataCache.loadMarkedCompletedAssignments()
                override suspend fun replace(ignored: Set<String>, completed: Set<String>) {
                    dataCache.replaceIgnoredAssignments(ignored)
                    dataCache.replaceMarkedCompletedAssignments(completed)
                }
            },
            unsent = unsentMarks::statuses,
        )
    }
}
