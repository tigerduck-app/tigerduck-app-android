package org.ntust.app.tigerduck.sync.reconcile

import org.ntust.app.tigerduck.data.UnsentMarks
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.SyncCategory
import org.ntust.app.tigerduck.sync.SyncPass
import org.ntust.app.tigerduck.ui.screen.home.AssignmentMarkStep
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncState
import javax.inject.Inject

/**
 * The assignment marks: Home's mark step ([AssignmentMarkStep]), with the Assignments switch as the
 * pass read it, and the conflict dialog's "keep the server's" answer.
 */
class MarksReconciler @Inject constructor(
    private val prefs: AppPreferences,
    dataCache: DataCache,
    unsentMarks: UnsentMarks,
    pushApiClient: PushApiClient,
) : Reconciler {

    private val markStep = AssignmentMarkStep.over(
        prefs, dataCache, unsentMarks, pushApiClient::patchAssignmentOverride,
    )

    override suspend fun reconcile(pass: SyncPass): Boolean {
        val signIn = pass.signIn
        // Recorded at once, for this sign-in only: a later step that fails must
        // not leave the next sync reading an empty server as this phone's first.
        val marksTaken = markStep.pull(pass.home, pass.result, SyncCategory.ASSIGNMENTS in pass.policy.switchedOn, signIn)
        if (marksTaken) prefs.school.assignmentMarksSyncedOnce.set(true, signIn)
        return marksTaken
    }

    /** Takes [result]'s marks, the user having picked the server's in the conflict dialog. */
    suspend fun keepServer(state: HomeSyncState, result: BackendSyncResult, signIn: SignIn) =
        markStep.keepServer(state, result, signIn)
}
