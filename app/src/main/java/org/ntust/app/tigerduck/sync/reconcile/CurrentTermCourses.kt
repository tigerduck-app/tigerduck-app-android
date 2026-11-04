package org.ntust.app.tigerduck.sync.reconcile

import org.ntust.app.tigerduck.data.PendingCourseAdds
import org.ntust.app.tigerduck.data.PendingCourseDeletes
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.ui.screen.home.CourseMembership
import org.ntust.app.tigerduck.ui.screen.home.CourseSyncReconciler
import org.ntust.app.tigerduck.ui.screen.home.commitReconciledTerm

/**
 * The background worker's course list: the current term's slice of Home's per-term reconcile
 * ([RosterReconciler]), through the same [CourseMembership] so the rules cannot drift.
 *
 * Deliberately narrower than Home's (C2): only the current term, no merge of rows this device
 * lacks (that is left to Home's sync), no upload, and no pinning of legacy bare tombstones to
 * terms (`pinnedLegacyHides`), so the cache prune ignores bare entries rather than delete a
 * retaken course on the strength of one.
 */
internal class CurrentTermCourses(
    private val dataCache: DataCache,
    private val courseService: CourseService,
    private val pushApiClient: PushApiClient,
    private val pendingCourseAdds: PendingCourseAdds,
    private val pendingCourseDeletes: PendingCourseDeletes,
    private val pendingCourseOverrides: PendingCourseOverrides,
) {
    /**
     * The current term. [holds] is the pull's (LWW D12), whose course list, never synced here
     * before ([syncedBefore] false), is Home's first sync's (LWW D13, C2). A course this phone holds
     * (its add or delete waiting, or acknowledged since the snapshot began) stays as it has it.
     */
    suspend fun reconcile(result: BackendSyncResult, courseSync: DataCache.CourseSync, signIn: SignIn, holds: PullHolds, syncedBefore: Boolean) {
        val semester = courseService.currentSemesterCode()
        // Mid-reset, or a snapshot fetched before this device reset the
        // term — see CourseSyncReconciler.termsResetAfter.
        val resetAt = dataCache.loadSemesterResetAt()
        dataCache.clearSemesterResetAt(CourseSyncReconciler.resetStampsOutlived(result.fetchedAtMs, resetAt), signIn)
        val stale = CourseSyncReconciler.termsResetAfter(
            result.fetchedAtMs, resetAt, dataCache.resettingSemesters(),
        )
        if (semester in stale || !syncedBefore) return
        val localCourses = dataCache.loadCourses(semester)
        val stored = dataCache.loadDeletedCourseNos()
        val waiting = pendingCourseAdds.waitingIn(semester) + pendingCourseDeletes.waitingIn(semester)
        val rows = result.serverCourses.filter { it.semester == semester }
        val membership = CourseMembership.reconcileTerm(
            semester = semester,
            localCourses = localCourses,
            serverRows = rows,
            tombstones = result.tombstones,
            hides = stored,
            keeps = { it in waiting || holds.keeps(SyncField.membership(SyncField.courseKey(semester, it))) },
            selectionDroppedNos = dataCache.loadSelectionDroppedNos()[semester].orEmpty().toSet(),
            answer = dataCache.selectionAnswerIn(semester),
            fetchedAtMs = result.fetchedAtMs,
        )
        val outcome = membership.outcome
        dataCache.recordServerDrops(semester, outcome.serverDrops, signIn)
        pushApiClient.serverDropped(semester, outcome.serverDrops.keys)
        val pruned = CourseSyncReconciler.pruneHidden(semester, localCourses, outcome.tombstones, legacyBare = false)
        commitReconciledTerm(
            dataCache, courseSync, semester, stored, outcome.tombstones, CourseSyncReconciler.tombstoneNosFor(semester, result.tombstones),
            localCourses, pruned, outcome.left, outcome.droppedOnServer, emptyList(), outcome.handAddedNos, pendingCourseOverrides, signIn,
            membership.gone,
        )
        // The copies merged down follow their rows, for the term's uploads' adds; the pull covers this term alone.
        // One a write told since the sync began keeps that (compare-and-set).
        dataCache.updateCoursesIfChanged(semester, signIn) { courses ->
            CourseMembership.followingRows(courses, semester, rows, CourseMembership.heldIn(result, semester)) { courseSync.retoldSince(semester, it) }
        }
    }
}
