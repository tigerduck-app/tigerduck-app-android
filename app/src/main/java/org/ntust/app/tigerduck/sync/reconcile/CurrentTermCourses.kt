package org.ntust.app.tigerduck.sync.reconcile

import android.util.Log
import org.ntust.app.tigerduck.data.PendingCourseAdds
import org.ntust.app.tigerduck.data.PendingCourseDeletes
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.ui.screen.home.CourseMembership
import org.ntust.app.tigerduck.ui.screen.home.CourseSyncReconciler
import org.ntust.app.tigerduck.ui.screen.home.commitReconciledTerm
import org.ntust.app.tigerduck.util.logSummary

/**
 * The background worker's course list: the current term's slice of Home's per-term reconcile
 * ([RosterReconciler]), through the same [CourseSyncReconciler] so the rules cannot drift.
 *
 * The worker used to carry its own copy of the rules, and it had drifted: it never read
 * `course_tombstones` at all, and when the server had nothing for this term it fell back to the
 * roster flattened across every term, so a semester another device had just reset looked
 * populated from here, and nothing was hidden.
 *
 * Deliberately narrower than Home's (C2): only the current term, no merge of rows this device
 * lacks (that is left to Home's sync), and no pinning of legacy bare tombstones to terms
 * (`RosterReconciler.migratedTombstones`), so an un-hide here can lift a bare entry for every
 * term at once, as the code it replaced also did, and the cache prune ignores bare entries rather
 * than delete a retaken course on the strength of one.
 */
internal class CurrentTermCourses(
    private val prefs: AppPreferences,
    private val dataCache: DataCache,
    private val courseService: CourseService,
    private val pushApiClient: PushApiClient,
    private val pendingCourseAdds: PendingCourseAdds,
    private val pendingCourseDeletes: PendingCourseDeletes,
    private val pendingCourseOverrides: PendingCourseOverrides,
    /** A fetch's courses whose upload waits for the first pull: `SchoolUploads.waitingIn`. */
    private val uploadsWaitingIn: (String) -> Set<String> = { emptySet() },
) {
    /**
     * The current term. [holds] is a `/v4` pull's (LWW D12), whose course list, never synced here
     * before ([syncedBefore] false), is Home's first sync's (LWW D13, C2).
     */
    suspend fun reconcile(result: BackendSyncResult, courseSync: DataCache.CourseSync, signIn: SignIn, holds: PullHolds? = null, syncedBefore: Boolean = true) {
        val semester = courseService.currentSemesterCode()
        // Mid-reset, or a snapshot fetched before this device reset the
        // term — see CourseSyncReconciler.termsResetAfter.
        val resetAt = dataCache.loadSemesterResetAt()
        dataCache.clearSemesterResetAt(CourseSyncReconciler.resetStampsOutlived(result.fetchedAtMs, resetAt), signIn)
        val stale = CourseSyncReconciler.termsResetAfter(
            result.fetchedAtMs, resetAt, dataCache.resettingSemesters(),
        )
        if (semester in stale) return
        if (holds != null) return if (syncedBefore) reconcileStamped(result, courseSync, signIn, holds, semester) else Unit
        // As Home's course list reads it: under the adds' send lock, with what waits to go up (RosterReconciler.reconcileTerm).
        val (localCourses, uploadingNos) = pendingCourseAdds.uploading { dataCache.loadCourses(semester) to uploadsWaitingIn(semester) }
        // After the courses: see CourseSyncReconciler.withoutRestored.
        val restored = dataCache.restoredCoursesIn(semester, result.fetchedAtMs)
        val stored = CourseSyncReconciler.withoutRestored(semester, dataCache.loadDeletedCourseNos(), restored)
        val storedKnown = dataCache.loadServerKnownNos()
        // A course reset made on another device, which the background leaves to
        // Home's sync (CourseResetReconciler): it wipes the hand-added courses
        // and their waiting adds, so none is kept here.
        val resetElsewhere = CourseSyncReconciler.coursesReset(
            result.coursesResetAt, prefs.school.lastSeenCoursesResetAt.get(), prefs.legacyLastCourseSyncAtMs,
        ).apply
        // Nor one whose add back is under way; see DataCache.CourseSync.supersedeAddsBack.
        if (resetElsewhere) courseSync.supersedeAddsBack()
        val knownHere = storedKnown[semester].orEmpty().toSet()
        val tombstoneNos = CourseSyncReconciler.tombstoneNosFor(semester, result.tombstones)
        val outcome = CourseSyncReconciler.reconcileSemester(
            semester = semester,
            localCourses = localCourses,
            serverRows = result.serverCourses.filter { it.semester == semester },
            tombstoneNos = tombstoneNos,
            tombstones = stored,
            serverKnownNos = knownHere,
            // Deletes the retry above could not land: the snapshot still lists them.
            graceCourseNos = pendingCourseDeletes.waitingIn(semester),
            // Adds the retry above could not land: the snapshot does not list them yet.
            pendingAddNos = if (resetElsewhere) emptySet() else pendingCourseAdds.waitingIn(semester),
            selectionDroppedNos = dataCache.loadSelectionDroppedNos()[semester].orEmpty().toSet(),
            restoredNos = restored,
            // A course it listed since a roster dropped it elsewhere stays: see SelectionAnswer.listsSince.
            answer = dataCache.selectionAnswerIn(semester),
            fetchedAtMs = result.fetchedAtMs,
            // A fetch's courses whose upload waits for this pull: not on the server yet either.
            uploadingNos = uploadingNos,
        )
        // For the fetches that only gain, which leave them out; see DataCache.fetchDropsIn.
        dataCache.recordServerDrops(semester, outcome.serverDrops, signIn)
        // A roster from here that listed one goes again, unchanged or not.
        pushApiClient.serverDropped(semester, outcome.serverDrops.keys)
        dataCache.restoredCoursesListed(
            semester, result.serverCourses.filter { it.semester == semester }.map { it.courseNo }, result.fetchedAtMs, signIn,
        )
        // Unconditional: a course hidden by an earlier build and left in
        // the cache is pruned on the first run, not the next change.
        val pruned = CourseSyncReconciler.pruneHidden(semester, localCourses, outcome.tombstones, legacyBare = false)
        // Merging down is Home's: nothing merged here.
        // A course it takes out goes with its waiting colour and name.
        commitReconciledTerm(
            dataCache, courseSync, semester, stored, outcome.tombstones, tombstoneNos, localCourses, pruned, outcome.left,
            outcome.droppedOnServer, emptyList(), outcome.handAddedNos, pendingCourseOverrides, signIn,
        )
        var known = outcome.serverKnownNos
        if (outcome.uploadLocal) {
            // resend: the server has nothing for the term, whatever list the
            // upload ledger remembers it accepting; see RosterReconciler.uploadTerm.
            runCatching { pushApiClient.uploadCourses(outcome.toUpload, semester, signIn, resend = true) }
                .onSuccess { accepted -> if (!accepted.isNullOrEmpty()) known = known + accepted }
                .onFailure { e -> Log.w(TAG, "[Sync] auto-upload failed: ${e.logSummary()}") }
            Log.i(TAG, "[Sync] backend empty, auto-uploaded ${outcome.toUpload.size} courses")
        }
        if (known != knownHere) {
            // Added to what is stored now: an add answered meanwhile may have added to it too.
            dataCache.updateServerKnownNos(signIn) { current ->
                current + (semester to (current[semester].orEmpty() + known).distinct().sorted())
            }
        }
    }

    /**
     * The current term on a `/v4` pull ([CourseMembership]), as Home's but for what the background
     * leaves to Home: no merge of rows this phone lacks, and no upload. A course this phone holds
     * (its add or delete waiting, or acknowledged since the snapshot began) stays as it has it.
     */
    private suspend fun reconcileStamped(result: BackendSyncResult, courseSync: DataCache.CourseSync, signIn: SignIn, holds: PullHolds, semester: String) {
        val localCourses = dataCache.loadCourses(semester)
        val stored = dataCache.loadDeletedCourseNos()
        val waiting = pendingCourseAdds.waitingIn(semester) + pendingCourseDeletes.waitingIn(semester)
        val membership = CourseMembership.reconcileTerm(
            semester = semester,
            localCourses = localCourses,
            serverRows = result.serverCourses.filter { it.semester == semester },
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
    }

    private companion object {
        const val TAG = "BackgroundSyncWorker"
    }
}
