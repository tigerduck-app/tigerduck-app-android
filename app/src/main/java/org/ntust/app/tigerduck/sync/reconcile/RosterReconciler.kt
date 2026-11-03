package org.ntust.app.tigerduck.sync.reconcile

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.PendingCourseAdds
import org.ntust.app.tigerduck.data.PendingCourseDeletes
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.SemesterCatalog
import org.ntust.app.tigerduck.notification.TermCourseSync
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.ServerCourse
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.sync.SyncCategory
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.SyncMode
import org.ntust.app.tigerduck.sync.SyncPass
import org.ntust.app.tigerduck.sync.SyncPolicies
import org.ntust.app.tigerduck.ui.screen.home.CourseMembership
import org.ntust.app.tigerduck.ui.screen.home.CourseSyncReconciler
import org.ntust.app.tigerduck.ui.screen.home.commitReconciledTerm
import org.ntust.app.tigerduck.util.logSummary
import org.ntust.app.tigerduck.widget.WidgetUpdater
import javax.inject.Inject

/**
 * The course list, every term this phone knows of, and the roster requests the snapshot makes,
 * while course sync is on and no re-enable check holds the list. Returns whether it reconciled
 * the list. In the background, the current term's alone ([CurrentTermCourses]), and the roster
 * requests answered before the run ends, the switch and the hold read at the step.
 */
class RosterReconciler @Inject constructor(
    private val dataCache: DataCache,
    private val authService: AuthService,
    private val pushApiClient: PushApiClient,
    private val courseService: CourseService,
    private val semesterCatalog: SemesterCatalog,
    private val widgetUpdater: WidgetUpdater,
    private val pendingCourseAdds: PendingCourseAdds,
    private val pendingCourseDeletes: PendingCourseDeletes,
    private val pendingCourseOverrides: PendingCourseOverrides,
    private val termCourseSync: TermCourseSync,
    @param:ApplicationScope private val appScope: CoroutineScope,
    prefs: AppPreferences,
    private val syncPolicies: SyncPolicies,
) : Reconciler {

    private val currentTerm = CurrentTermCourses(
        prefs, dataCache, courseService, pushApiClient, pendingCourseAdds, pendingCourseDeletes, pendingCourseOverrides,
    )

    override suspend fun reconcile(pass: SyncPass): Boolean {
        val result = pass.result
        val signIn = pass.signIn
        return when (pass.mode) {
            SyncMode.FOREGROUND -> pass.policy.applies(SyncCategory.COURSES).also { takesCourseList ->
                syncCourseList(result, takesCourseList, pass.courseSync, signIn, pass.holds())
                if (takesCourseList) answerRosterRequests(result.rosterRequests, signIn)
            }
            // Read here, once the steps before have run, as the worker always read it: a course
            // switch turned off while they ran leaves the list and its roster requests.
            SyncMode.BACKGROUND -> syncPolicies.current.applies(SyncCategory.COURSES).also { takesCourseList ->
                if (takesCourseList) {
                    currentTerm.reconcile(result, pass.courseSync, signIn, pass.holds(), SyncCategory.COURSES in pass.syncedBefore)
                    answerRosterRequestsNow(result.rosterRequests, signIn)
                }
            }
        }
    }

    /**
     * The background worker's [answerRosterRequests]: answered before the run ends, as the worker
     * has no screen to come back to. A failure there is no failed sync: the backend asks again
     * until a roster arrives.
     */
    private suspend fun answerRosterRequestsNow(requests: List<String>, signIn: SignIn) {
        if (requests.isEmpty()) return
        val studentId = authService.storedStudentId ?: return
        val password = authService.storedPassword ?: return
        try {
            termCourseSync.answerRosterRequests(requests, studentId, password, signIn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "roster request failed: ${e.logSummary()}")
        }
    }

    /**
     * The terms the snapshot asks this account's roster of (`roster_requests`), answered from
     * course selection; see [TermCourseSync.answerRosterRequests]. Launched, never awaited: the
     * sync this refresh shows is done, and the school takes seconds. On the application scope,
     * pinned to [signIn], the sign-in the snapshot was fetched as, so a sign-out meanwhile drops
     * what it writes, and its token alone is what the roster goes with.
     */
    private suspend fun answerRosterRequests(requests: List<String>, signIn: SignIn) {
        if (requests.isEmpty()) return
        val studentId = authService.storedStudentId ?: return
        val password = authService.storedPassword ?: return
        appScope.launch {
            runCatching { termCourseSync.answerRosterRequests(requests, studentId, password, signIn) }
                .onSuccess { asked ->
                    // The open screens and the widget read the term again, as after the worker's sync.
                    if (asked) {
                        widgetUpdater.requestUpdate()
                        dataCache.notifyBackgroundSyncComplete()
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "[Sync] roster request failed: ${e.logSummary()}")
                }
        }
    }

    /** One pass over the course list: what every term's reconcile reads, and the server's numbers it learns. */
    private class CourseListRun(
        val result: BackendSyncResult,
        val courseSync: DataCache.CourseSync,
        val signIn: SignIn,
        val rowsBySemester: Map<String, List<ServerCourse>>,
        val selectionDropped: Map<String, List<String>>,
        val serverKnown: Map<String, List<String>>,
        var tombstones: Set<String>,
        /** A `/v4` pull's holds (LWW D12): membership by its tombstones and resets ([CourseMembership]). */
        val holds: PullHolds?,
    ) {
        val updatedKnown = serverKnown.toMutableMap()
    }

    /**
     * Reconcile every semester the app knows about, one term at a time.
     *
     * This used to reconcile a single "current" term: it compared the current
     * semester's cache against `serverCourseNos` flattened across all terms.
     * Once the clients began uploading every semester (iOS `5403ff3`), that
     * flattening became actively wrong — a course hidden in 114-2 was
     * un-hidden because the student was taking it again in 115-1, since the
     * un-delete rule only asked "is this number anywhere on the server".
     *
     * Per term, in whichever direction has data: when the backend knows
     * courses it is authoritative for deletions and we merge down; when it
     * knows none for that term but this device does, we upload. Reading an
     * empty term as "the server says you have no courses" would delete them.
     * Each term's commit leaves be what was put back in it since [courseSync]
     * began; see [commitReconciledTerm].
     */
    private suspend fun syncCourseList(
        result: BackendSyncResult,
        takesCourseList: Boolean,
        courseSync: DataCache.CourseSync,
        signIn: SignIn,
        holds: PullHolds?,
    ) {
        if (!takesCourseList) return

        val resetAt = dataCache.loadSemesterResetAt()
        val semesters = CourseSyncReconciler.semestersToReconcile(
            serverCourses = result.serverCourses,
            tombstones = result.tombstones,
            catalogue = semesterCatalog.availableSemesters(),
            excluding = CourseSyncReconciler.termsResetAfter(
                result.fetchedAtMs, resetAt, dataCache.resettingSemesters(),
            ),
        )
        dataCache.clearSemesterResetAt(CourseSyncReconciler.resetStampsOutlived(result.fetchedAtMs, resetAt), signIn)
        val rowsBySemester = result.serverCourses.groupBy { it.semester }
        val selectionDropped = dataCache.loadSelectionDroppedNos()
        val serverKnown = dataCache.loadServerKnownNos()
        val run = CourseListRun(
            result, courseSync, signIn, rowsBySemester, selectionDropped, serverKnown, migratedTombstones(semesters, signIn), holds,
        )
        val currentSemester = courseService.currentSemesterCode()
        var touchedCurrent = false

        for (semester in semesters) {
            if (reconcileTerm(run, semester) && semester == currentSemester) touchedCurrent = true
        }

        val updatedKnown = run.updatedKnown
        if (updatedKnown != serverKnown) {
            // Added to what is stored now, the numbers only growing: an add answered meanwhile,
            // or the background run, may have added to it too.
            dataCache.updateServerKnownNos(signIn) { current ->
                current + updatedKnown.mapValues { (semester, nos) -> (current[semester].orEmpty() + nos).distinct().sorted() }
            }
        }
        if (touchedCurrent) widgetUpdater.requestUpdate()
    }

    /** One term of [run]: its reconcile, committed, then its upload when the server holds none. Returns whether it wrote the courses. */
    private suspend fun reconcileTerm(run: CourseListRun, semester: String): Boolean {
        val result = run.result
        val signIn = run.signIn
        val rowsBySemester = run.rowsBySemester
        val localCourses = dataCache.loadCourses(semester)
        // After the courses: see CourseSyncReconciler.withoutRestored. On /v4 a hide is the snapshot's to lift.
        val restored = if (run.holds != null) emptySet() else dataCache.restoredCoursesIn(semester, result.fetchedAtMs)
        run.tombstones = CourseSyncReconciler.withoutRestored(semester, run.tombstones, restored)
        val read = run.tombstones
        val tombstoneNos = CourseSyncReconciler.tombstoneNosFor(semester, result.tombstones)
        val membership = run.holds?.let { holds -> stampedTerm(run, semester, localCourses, holds) }
        val outcome = membership?.outcome ?: CourseSyncReconciler.reconcileSemester(
            semester = semester,
            localCourses = localCourses,
            serverRows = rowsBySemester[semester].orEmpty(),
            tombstoneNos = tombstoneNos,
            tombstones = run.tombstones,
            serverKnownNos = run.serverKnown[semester].orEmpty().toSet(),
            // Deletes the retry above could not land: the snapshot still lists them.
            graceCourseNos = pendingCourseDeletes.waitingIn(semester),
            // Adds the retry above could not land: the snapshot does not list them yet.
            pendingAddNos = pendingCourseAdds.waitingIn(semester),
            selectionDroppedNos = run.selectionDropped[semester].orEmpty().toSet(),
            restoredNos = restored,
            // A course it listed since a roster dropped it elsewhere stays: see SelectionAnswer.listsSince.
            answer = dataCache.selectionAnswerIn(semester),
            fetchedAtMs = result.fetchedAtMs,
        )
        // For the fetches that only gain, which leave them out; see DataCache.fetchDropsIn.
        dataCache.recordServerDrops(semester, outcome.serverDrops, signIn)
        // A roster from here that listed one goes again, unchanged or not.
        pushApiClient.serverDropped(semester, outcome.serverDrops.keys)
        dataCache.restoredCoursesListed(semester, rowsBySemester[semester].orEmpty().map { it.courseNo }, result.fetchedAtMs, signIn)
        run.tombstones = outcome.tombstones
        if (outcome.serverKnownNos.isNotEmpty()) {
            run.updatedKnown[semester] = outcome.serverKnownNos.sorted()
        }

        // Before the upload branch: an empty term can still have hidden
        // courses to take out of the cache (a reset elsewhere, with one
        // hand-typed survivor left to upload).
        val pruned = CourseSyncReconciler.pruneHidden(semester, localCourses, run.tombstones)
        if (outcome.merged.isNotEmpty()) {
            Log.i(TAG, "[Sync] $semester: merged from server ${outcome.merged.map { it.courseNo }}")
        }
        if (outcome.left.isNotEmpty()) {
            Log.i(TAG, "[Sync] $semester: dropped merged copies left ${outcome.left.sorted()}")
        }
        val wrote = commitReconciledTerm(
            dataCache, run.courseSync, semester, read, run.tombstones, tombstoneNos, localCourses, pruned, outcome.left,
            outcome.droppedOnServer, outcome.merged, outcome.handAddedNos, pendingCourseOverrides, signIn, membership?.gone.orEmpty(),
        )
        if (outcome.uploadLocal) uploadTerm(run, semester, outcome)
        return wrote
    }

    /**
     * [semester]'s membership on a `/v4` pull ([CourseMembership]): what this phone holds is a
     * course whose add or delete waits, read here at the term's step as `/v3` reads them, or whose
     * membership was acknowledged since the snapshot began ([holds]).
     */
    private suspend fun stampedTerm(run: CourseListRun, semester: String, localCourses: List<Course>, holds: PullHolds): CourseMembership.TermOutcome {
        val waiting = pendingCourseAdds.waitingIn(semester) + pendingCourseDeletes.waitingIn(semester)
        return CourseMembership.reconcileTerm(
            semester = semester,
            localCourses = localCourses,
            serverRows = run.rowsBySemester[semester].orEmpty(),
            tombstones = run.result.tombstones,
            hides = run.tombstones,
            keeps = { it in waiting || holds.keeps(SyncField.membership(SyncField.courseKey(semester, it))) },
            serverKnownNos = run.serverKnown[semester].orEmpty().toSet(),
            selectionDroppedNos = run.selectionDropped[semester].orEmpty().toSet(),
            answer = dataCache.selectionAnswerIn(semester),
            fetchedAtMs = run.result.fetchedAtMs,
        )
    }

    /**
     * Sent whatever the upload ledger remembers (resend): the server has nothing for the term, so
     * a reset elsewhere took the list it last accepted, and that same list has to go again.
     */
    private suspend fun uploadTerm(run: CourseListRun, semester: String, outcome: CourseSyncReconciler.SemesterOutcome) {
        runCatching { pushApiClient.uploadCourses(outcome.toUpload, semester, run.signIn, resend = true) }
            .onSuccess { accepted ->
                // The server has now seen these numbers: from here
                // on their absence is a deletion, not a pending
                // upload — see reconcileSemester. Only what it
                // accepted: a tombstoned course it skipped is still
                // unknown to it.
                if (!accepted.isNullOrEmpty()) {
                    run.updatedKnown[semester] = (outcome.serverKnownNos + accepted).sorted()
                }
            }
            .onFailure { e -> Log.w(TAG, "[Sync] $semester: auto-upload failed: ${e.logSummary()}") }
        Log.i(TAG, "[Sync] $semester: server empty, uploaded ${outcome.toUpload.size} local courses")
    }

    /**
     * The tombstone store with legacy bare entries pinned to the terms whose
     * cached roster carries them.
     *
     * Bare entries pre-date semester scoping and hide a course number in
     * every term, so the per-term reconcile would keep flip-flopping them:
     * one term un-hides on the server's say-so and clears the bare entry for
     * all the others. Pinning them once, here, is the upgrade path — see
     * [CourseTombstoneKeys.migrateLegacyEntries], which leaves anything no
     * roster knows bare rather than guessing.
     */
    private suspend fun migratedTombstones(semesters: List<String>, signIn: SignIn): Set<String> {
        val stored = dataCache.loadDeletedCourseNos()
        if (stored.none { ':' !in it }) return stored
        val rosters = semesters.associateWith { semester ->
            dataCache.loadCourses(semester).map { it.courseNo }.toSet()
        }
        // Under the hides lock: a bare one lifted while the rosters were read stays lifted.
        val migrated = dataCache.pinLegacyCourseHides(stored, rosters, signIn)
        val bare = migrated.count { ':' !in it }
        if (bare < stored.count { ':' !in it }) {
            Log.i(TAG, "[Sync] pinned ${stored.count { ':' !in it } - bare} legacy tombstones to their terms")
        }
        return migrated
    }

    private companion object {
        const val TAG = "HomeViewModel"
    }
}
