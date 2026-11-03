package org.ntust.app.tigerduck.sync.reconcile

import android.util.Log
import org.ntust.app.tigerduck.data.PendingCourseAdds
import org.ntust.app.tigerduck.data.PendingCourseDeletes
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.SyncMode
import org.ntust.app.tigerduck.sync.SyncPass
import org.ntust.app.tigerduck.ui.screen.home.CourseSyncReconciler
import javax.inject.Inject

/**
 * A course reset made on another device wipes this device's manually-added courses, once: the
 * server's `courses_reset_at` is compared with the one the last full sync read, never with this
 * phone's clock — see [CourseSyncReconciler.coursesReset]. The account's first sync here only
 * remembers it, so a fresh install never reads its own absence of history as a reset and deletes
 * courses the user just added. Remembered for the pass's sign-in only: after a sign-out it would
 * be the next session's first sync that skipped a reset. Returns whether a reset was applied.
 *
 * The reset brings back the courses this phone hid, so the deletes still waiting to go up from
 * before the fetch ([SyncPass.beforeFetch]) go too: sent on a later retry, one would take a
 * restored course off the server again. An add back under way goes with the waiting adds, for
 * the pass's course window as well; see DataCache.CourseSync.supersedeAddsBack.
 *
 * Home's alone: the background worker leaves a reset to Home's sync (C2), neither applying nor
 * remembering it, so Home's next sync still applies it. The worker's course list keeps the
 * hand-added courses from the waiting adds the reset would take; see [RosterReconciler].
 */
class CourseResetReconciler @Inject constructor(
    private val prefs: AppPreferences,
    private val dataCache: DataCache,
    private val pendingCourseAdds: PendingCourseAdds,
    private val pendingCourseDeletes: PendingCourseDeletes,
    private val pendingCourseOverrides: PendingCourseOverrides,
) : Reconciler {

    override suspend fun reconcile(pass: SyncPass): Boolean = when (pass.mode) {
        SyncMode.FOREGROUND -> applyCourseReset(pass.result, pass.beforeFetch, pass.courseSync, pass.signIn)
        SyncMode.BACKGROUND -> false
    }

    private suspend fun applyCourseReset(
        result: BackendSyncResult,
        beforeFetch: Long,
        courseSync: DataCache.CourseSync,
        signIn: SignIn,
    ): Boolean {
        val reset = CourseSyncReconciler.coursesReset(
            result.coursesResetAt, prefs.school.lastSeenCoursesResetAt.get(), prefs.legacyLastCourseSyncAtMs,
        )
        if (reset.apply) {
            val allCourses = dataCache.loadCourses().toMutableList()
            // Their waiting colours and names go with them; see PendingCourseOverrides.forget.
            pendingCourseOverrides.forget(allCourses.filter { it.isManual }.mapNotNull { it.moodleIdNumber }, signIn = signIn)
            allCourses.removeAll { it.isManual }
            dataCache.saveCourses(allCourses, signIn)
            dataCache.saveDeletedCourseNos(emptySet(), signIn)
            // In every term: the reconcile would keep a course whose add waits.
            pendingCourseAdds.forgetAll(signIn)
            courseSync.supersedeAddsBack()
            // Under the adds lock, as a term reset's forgetTerm: a retry waiting behind it then
            // finds them gone. One asked since the fetch is newer than the reset, and stays.
            pendingCourseAdds.removing {
                pendingCourseDeletes.forgetAskedBefore(beforeFetch, signIn)
            }
            Log.i(TAG, "[sync] courses reset detected, wiped manual courses")
        }
        if (prefs.school.lastSeenCoursesResetAt.set(reset.remember, signIn)) prefs.forgetLegacyLastCourseSyncAt()
        return reset.apply
    }

    private companion object {
        const val TAG = "HomeViewModel"
    }
}
