package org.ntust.app.tigerduck.sync.reconcile

import org.ntust.app.tigerduck.data.CourseIgnoreSync
import org.ntust.app.tigerduck.sync.SyncCategory
import org.ntust.app.tigerduck.sync.SyncMode
import org.ntust.app.tigerduck.sync.SyncPass
import javax.inject.Inject

/**
 * The courses whose assignments are ignored: [CourseIgnoreSync.applySnapshot], then Home shows
 * them, on `/v4` course by course but those this phone keeps ([SyncPass.holds], LWW D12). The
 * background worker has no screen to show them on.
 */
class IgnoresReconciler @Inject constructor(private val courseIgnoreSync: CourseIgnoreSync) : Reconciler {

    override suspend fun reconcile(pass: SyncPass): Boolean {
        val holds = pass.holds()
        // On /v4 a never-synced category's first sync is Home's, which queues this phone's values first (LWW D13, C2).
        if (holds != null && pass.mode == SyncMode.BACKGROUND && SyncCategory.ASSIGNMENTS !in pass.syncedBefore) return false
        val keys = courseIgnoreSync.applySnapshot(pass.result, pass.signIn, holds?.let { it::keeps }) ?: return false
        // What Home shows is the session's on screen: a run past its sign-in changes none of it.
        if (pass.mode == SyncMode.FOREGROUND) pass.signIn.ifCurrent { pass.home.ignoredCourseKeys.value = keys }
        return true
    }
}
