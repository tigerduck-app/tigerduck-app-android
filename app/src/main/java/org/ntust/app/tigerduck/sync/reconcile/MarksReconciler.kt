package org.ntust.app.tigerduck.sync.reconcile

import org.ntust.app.tigerduck.data.UnsentMarks
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.SyncCategory
import org.ntust.app.tigerduck.sync.SyncMode
import org.ntust.app.tigerduck.sync.SyncPass
import org.ntust.app.tigerduck.ui.screen.home.AssignmentMarkStep
import org.ntust.app.tigerduck.ui.screen.home.BackgroundMarkStep
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncState
import javax.inject.Inject

/**
 * The assignment marks: Home's mark step ([AssignmentMarkStep]), with the Assignments switch as the
 * pass read it, and the conflict dialog's "keep the server's" answer. In the background, the
 * worker's ([BackgroundMarkStep]), which takes the server's marks and sends none up (C2): a first
 * sync's marks are Home's to send. On `/v4` both take the snapshot's marks field by field, but
 * those this phone keeps ([SyncPass.holds], LWW D12).
 */
class MarksReconciler @Inject constructor(
    private val prefs: AppPreferences,
    dataCache: DataCache,
    unsentMarks: UnsentMarks,
    pushApiClient: PushApiClient,
) : Reconciler {

    private val markStep = AssignmentMarkStep.over(prefs, dataCache, unsentMarks) { id, status, signIn ->
        pushApiClient.patchAssignmentOverrideAnswered(id, status, signIn, madeAtMs = null)
    }

    private val backgroundStep = BackgroundMarkStep.over(prefs, dataCache, unsentMarks)

    override suspend fun reconcile(pass: SyncPass): Boolean {
        val signIn = pass.signIn
        val holds = pass.holds()
        val marksTaken = when (pass.mode) {
            SyncMode.FOREGROUND -> markStep.pull(pass.home, pass.result, SyncCategory.ASSIGNMENTS in pass.policy.switchedOn, signIn, holds)
            SyncMode.BACKGROUND -> backgroundStep.pull(pass.result, signIn, holds, SyncCategory.ASSIGNMENTS in pass.syncedBefore).let { marks ->
                pass.marksChanged = marks.changed
                marks.taken
            }
        }
        // Recorded at once, for this sign-in only: a later step that fails must
        // not leave the next sync reading an empty server as this phone's first.
        if (marksTaken) prefs.school.assignmentMarksSyncedOnce.set(true, signIn)
        return marksTaken
    }

    /** Takes [result]'s marks, the user having picked the server's in the conflict dialog. */
    suspend fun keepServer(state: HomeSyncState, result: BackendSyncResult, signIn: SignIn) =
        markStep.keepServer(state, result, signIn)
}
