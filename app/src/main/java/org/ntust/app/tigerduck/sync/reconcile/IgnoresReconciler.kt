package org.ntust.app.tigerduck.sync.reconcile

import org.ntust.app.tigerduck.data.CourseIgnoreSync
import org.ntust.app.tigerduck.sync.SyncPass
import javax.inject.Inject

/** The courses whose assignments are ignored: [CourseIgnoreSync.applySnapshot], then Home shows them. */
class IgnoresReconciler @Inject constructor(private val courseIgnoreSync: CourseIgnoreSync) : Reconciler {

    override suspend fun reconcile(pass: SyncPass): Boolean {
        val keys = courseIgnoreSync.applySnapshot(pass.result, pass.signIn) ?: return false
        // What Home shows is the session's on screen: a run past its sign-in changes none of it.
        pass.signIn.ifCurrent { pass.home.ignoredCourseKeys.value = keys }
        return true
    }
}
