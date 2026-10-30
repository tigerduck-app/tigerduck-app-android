// Cross-device sync for everything Home owns: assignment overrides, course
// colours and custom names, and the course list itself.
//
// This used to be one 150-line method on HomeViewModel. It is here instead
// because it is a different job from "hold the Home screen's state" — it
// talks to the backend, arbitrates between two devices, and writes the
// cache. HomeViewModel keeps the flows; this drives them.
//
// The decisions are deliberately not in this file. Which overrides survive a
// sync lives in AssignmentOverrideReconciler, and which courses are deleted
// or merged lives in CourseSyncReconciler — both pure and both tested. What
// is left here is sequencing and I/O, which is what makes it hard to test and
// exactly why it should not also contain the rules.
//
// Failure policy is "degrade to local, never lose a tap": a throw sets the
// sync source to LOCAL and leaves the cache alone, and a 401 gets one relogin
// retry. A sync that fails must never look like a sync that found nothing.
//
// Cancellation is the one exception and is rethrown untouched. Leaving Home
// mid-sync is not a backend failure, and reporting it as one turns on the
// "local only" banner for a sync nobody was waiting for.

package org.ntust.app.tigerduck.ui.screen.home

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.isSessionRefused
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.CourseIgnoreSync
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.PendingCourseAdds
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.UnsentMarks
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.SemesterCatalog
import org.ntust.app.tigerduck.notification.SyncSource
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.push.NotificationSettingsSync
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.SyncApiClient
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.component.ServerFailureSimulator
import org.ntust.app.tigerduck.ui.component.ServerKind
import org.ntust.app.tigerduck.ui.component.ServerStatus
import org.ntust.app.tigerduck.ui.component.ServerStatusTracker
import org.ntust.app.tigerduck.ui.screen.settings.ReenableCheck
import org.ntust.app.tigerduck.ui.screen.settings.categoriesSwitchedOn
import org.ntust.app.tigerduck.ui.screen.settings.categoriesSynced
import org.ntust.app.tigerduck.ui.screen.settings.categoriesTaken
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.util.logSummary
import org.ntust.app.tigerduck.widget.WidgetUpdater
import javax.inject.Inject

/**
 * The slice of HomeViewModel that a sync reads and writes.
 *
 * Passed in rather than injected because these are the ViewModel's own
 * flows — the sync updates the live UI state directly, the same way the
 * inline version did. Holding them here instead of copying values in and out
 * keeps the "user taps mid-sync" behaviour identical: the reconciler reads
 * [pendingOverrides] at the moment it runs, not a snapshot from before the
 * network call.
 */
class HomeSyncState(
    val ignoredAssignmentIds: MutableStateFlow<Set<String>>,
    val markedCompletedIds: MutableStateFlow<Set<String>>,
    /** The ignored courses; see [org.ntust.app.tigerduck.data.CourseIgnores]. */
    val ignoredCourseKeys: MutableStateFlow<Set<String>>,
    val allAssignments: MutableStateFlow<List<Assignment>>,
    val allCourses: MutableStateFlow<List<Course>>,
    val conflicts: MutableStateFlow<List<AssignmentSyncConflict>>,
    /**
     * Ids with an override PATCH in flight. Mutated by HomeViewModel's toggle
     * handlers on the main thread and read here; see the reconciler for why
     * these are exempt from server state.
     */
    val pendingOverrides: MutableSet<String>,
) {
    /**
     * Held from the sync that produced a conflict until the user answers the
     * dialog, because "keep the server's version" has to apply the result
     * that was contested, not whatever the server says by the time they tap.
     */
    var pendingResult: BackendSyncResult? = null

    /** Last revision the backend reported; the poller compares against it. */
    var lastKnownRevision: Long = 0
}

class HomeBackendSync @Inject constructor(
    private val prefs: AppPreferences,
    private val dataCache: DataCache,
    private val authService: AuthService,
    private val authTokenManager: AuthTokenManager,
    private val syncApiClient: SyncApiClient,
    private val pushApiClient: PushApiClient,
    private val notificationSettingsSync: NotificationSettingsSync,
    private val courseService: CourseService,
    private val semesterCatalog: SemesterCatalog,
    private val widgetUpdater: WidgetUpdater,
    private val academicCalendar: org.ntust.app.tigerduck.academic.AcademicCalendarStore,
    private val courseIgnoreSync: CourseIgnoreSync,
    private val unsentMarks: UnsentMarks,
    private val pendingCourseAdds: PendingCourseAdds,
    private val pendingCourseDeletes: org.ntust.app.tigerduck.data.PendingCourseDeletes,
    private val pendingCourseOverrides: PendingCourseOverrides,
    private val pendingHolidayOverrides: org.ntust.app.tigerduck.data.PendingHolidayOverrides,
    private val reenableCheck: ReenableCheck,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {

    private val markStep = AssignmentMarkStep.over(
        prefs, dataCache, unsentMarks, pushApiClient::patchAssignmentOverride,
    )

    /**
     * Sends what waits to go up: course-ignore changes, marks, course adds and
     * deletes, course colours and names, and holiday choices. A full sync
     * starts with this.
     */
    suspend fun retryUnsent() {
        courseIgnoreSync.retryPending()
        unsentMarks.retry()
        pendingCourseAdds.retry()
        pendingCourseDeletes.retry()
        pendingCourseOverrides.retry()
        pendingHolidayOverrides.retry()
    }

    /**
     * What a full sync sends besides taking its snapshot, for a revision poll
     * that moves the watermark without one (`pollRevisionOnce`): what waits
     * ([retryUnsent]), and a notification-settings edit whose push never
     * landed, which [pull] re-sends once it lands. The feed read before this
     * proves a session and a network as a full sync does. As the iPhone's
     * `retryUnsentEdits`.
     */
    suspend fun sendWhatWaits() {
        retryUnsent()
        notificationSettingsSync.pushIfUnconfirmed()
    }

    /**
     * Pull everything from the backend and reconcile it into [state].
     *
     * [retried] guards the 401 path against looping: a relogin that still
     * produces a 401 gives up rather than recursing.
     */
    suspend fun pull(state: HomeSyncState, retried: Boolean = false) {
        // Pinned to the data this sync began with: once a sign-out wipes it, every cache write
        // below drops, so a sync that outlives one cannot write the departing account's courses,
        // deletions and marks into the files the next account starts from.
        if (currentCoroutineContext()[DataCache.Generation] == null) {
            return withContext(DataCache.Generation(dataCache.userDataGeneration)) { pull(state, retried) }
        }
        // cloudSyncEnabled already reads false on fdroid at its source
        // (AppPreferences.cloudSyncEnabled), so no separate flavor check
        // is needed here.
        if (!prefs.cloudSyncEnabled) {
            markBackendIdle()
            return
        }
        if (!authTokenManager.signInIfSignedOut()) {
            markBackendIdle()
            return
        }
        // Before anything it sends or fetches, until it is done: see DataCache.beginCourseSync.
        val courseSync = dataCache.beginCourseSync()
        try {
            if (BuildConfig.DEBUG) ServerFailureSimulator.check(ServerKind.BACKEND)
            val account = authService.storedStudentId
            val generation = courseIgnoreSync.currentGeneration()
            // What waits to go up goes first, so the snapshot has it.
            retryUnsent()
            // Every class-table delete asked before the fetch ranks below it, every later one
            // above: a course reset the snapshot reads lets go of the first only; see applyCourseReset.
            val beforeFetch = pendingCourseDeletes.stamp()
            // An add back made from here on is newer than a course reset the snapshot reads.
            courseSync.fetching()
            // The first-sync mark upload is pinned to the sign-in read here:
            // a sign-out and another sign-in meanwhile must not send these
            // marks as theirs.
            val (result, signIn) = fetchPinned({ authTokenManager.signInGeneration }) {
                syncApiClient.fetchFullSync()
            }
            pendingCourseAdds.listed(result.serverCourses)
            // One reading of the switches, for the steps below and for what
            // this sync records as synced: one flipped meanwhile counts from the next.
            val switchedOn = categoriesSwitchedOn(prefs)
            // Recorded at once, for this account only: a later step that
            // fails must not leave the next sync reading an empty server as
            // this phone's first.
            val marksTaken = markStep.pull(state, result, "assignments" in switchedOn, signIn, generation)
            if (marksTaken && authService.isStillSignedInAs(account)) {
                prefs.assignmentMarksSyncedOnce = true
            }
            courseIgnoreSync.applySnapshot(result, generation)?.let { state.ignoredCourseKeys.value = it }
            // Course overrides and deletion detection always run regardless
            // of whether this was a first-time migration, but not over a
            // re-enable check still waiting for the user's answer.
            val pending = prefs.pendingConflictCategories
            val syncColors = CourseSyncReconciler.syncsCourseColors("course_colors" in switchedOn, pending)
            val syncNames = CourseSyncReconciler.syncsCourseNames("course_names" in switchedOn, pending)
            val coursesLoaded = if (result.courseOverrides.isNotEmpty() && (syncColors || syncNames)) {
                // Not over a colour or name this phone has waiting to go up,
                // or sent since the fetch: the snapshot's is the older.
                val held = pendingCourseOverrides.held(result.fetchedAtMs)
                applyCourseOverrides(state, result.courseOverrides, held, syncColors, syncNames)
            } else {
                state.allCourses.value.isNotEmpty()
            }
            applyCourseReset(result, account, beforeFetch, courseSync)
            // Not gated on any of the per-category sync toggles: a holiday
            // exception is a notification setting, not course or assignment
            // data, and the categories the user can turn off do not cover
            // it. Null means an older backend that does not send the
            // section, so the local set stands. A choice whose PUT has not
            // landed stays as this phone has it, and a snapshot that may
            // predate one is not applied: see PendingHolidayOverrides.merged.
            result.holidayOverrides?.let { snapshot ->
                authTokenManager.runIfSignedIn(signIn) {
                    // Under the sign-out's lock: applied after its clear, the
                    // departing account's choices would come back.
                    pendingHolidayOverrides.merged(snapshot, result.fetchedAtMs, academicCalendar.optedInHolidayIds)
                        ?.let(academicCalendar::applySyncedOverrides)
                }
            }
            val syncCourses = CourseSyncReconciler.syncsCourseList(
                switchOn = "courses" in switchedOn,
                pendingConflictCategories = prefs.pendingConflictCategories,
            )
            syncCourseList(result, syncCourses, courseSync)
            // Only what this sync took, and only for the account it was for:
            // recorded after a sign-out, it would mark the next account's
            // categories before their first sync.
            val took = categoriesTaken(
                marksTaken = marksTaken,
                courseListSynced = syncCourses,
                syncColors = syncColors,
                syncNames = syncNames,
                hasOverrides = result.courseOverrides.isNotEmpty(),
                coursesLoaded = coursesLoaded,
            )
            if (authService.isStillSignedInAs(account)) prefs.syncedCategories += categoriesSynced(switchedOn, took, prefs)
            // Only for the account this sync fetched for: set after a sign-out
            // reset it, the departing account's would stand for the next one.
            authTokenManager.runIfSignedIn(signIn) { state.lastKnownRevision = result.currentRevision }
            ServerStatusTracker.set(ServerStatus.OK, ServerKind.BACKEND)
            prefs.setLastSyncSource(SyncSource.BACKEND)
            widgetUpdater.requestUpdate()
            // A re-enable check that failed runs again here rather than at
            // the next Settings visit, so what it holds is not held for days.
            reenableCheck.runIfOwed()
            // A full sync that just succeeded proves a session and a network,
            // so this is where a notification-settings edit (Live Update or
            // assignment reminders) whose push never landed — made while sync
            // was off or the phone was offline, or one whose retries ran out —
            // gets sent again. Catch-up only: it must never mark the device
            // dirty, or every sync would push this phone's values over the
            // other devices'. iOS re-sends at every full sync too.
            notificationSettingsSync.pushIfUnconfirmed()
            // ...and where a change another device made to those settings is
            // picked up. Android fires assignment reminders itself, so without
            // this an iPhone change reached this phone's alarms only once the
            // user opened one of the two settings screens. iOS reconciles
            // after every full sync as well. pullNow() applies nothing over
            // an edit still waiting for its push, cannot interleave with that
            // push, and cancels or re-arms the reminders armed here when a
            // value changes. It already catches its own transport failures;
            // the guard is so nothing else it throws can turn this successful
            // sync into a failed one.
            //
            // Launched, never awaited: the sync this refresh is showing has
            // already succeeded by here, and pullNow() waits for the document
            // lock — which a queued push can hold for a whole read-modify-
            // write — and then reads the document. On a degrading connection
            // that is up to a minute of spinner (10 s connect / 20 s read,
            // twice) for work Home is not showing. Nothing downstream needs
            // the result: the pull re-arms the reminders itself, and
            // `scheduleAll` is synchronized and reads the preferences at arm
            // time, so whichever of it and Home's own arming runs last is
            // correct either way.
            //
            // On the application scope rather than the caller's: the caller is
            // a viewModelScope that a rotation or a swipe off Home cancels,
            // which could land in the middle of the apply. On Dispatchers.Main
            // because pullNow()'s check-and-apply requires it — the
            // application scope's own dispatcher is Default.
            appScope.launch(Dispatchers.Main) {
                runCatching { notificationSettingsSync.pullNow(knownRevision = result.currentRevision) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, "[Sync] notification settings pull failed: ${e.logSummary()}")
                    }
            }
        } catch (e: CancellationException) {
            // Leaving Home mid-sync cancels viewModelScope, which lands here.
            // The writes below are not suspending, so they would run even in a
            // cancelled scope and report a backend failure for a sync that was
            // simply abandoned — flipping on the "local only" banner and, if
            // the cancellation message happened to contain "401", kicking off
            // a relogin. Same guard BackgroundSyncWorker took in cd644d04.
            throw e
        } catch (e: Exception) {
            ServerStatusTracker.set(ServerStatus.FAILED, ServerKind.BACKEND)
            prefs.setLastSyncSource(SyncSource.LOCAL)
            if (!retried && e.isSessionRefused()) {
                // Delegates to AuthService so the relogin harvests a fresh
                // Moodle token — the backend rejects stale stored tokens with
                // 401 invalid_token.
                if (authService.attemptRelogin()) {
                    pull(state, retried = true)
                }
            }
            Log.w(TAG, "[Sync] override sync failed: ${e.logSummary()}")
        } finally {
            dataCache.endCourseSync(courseSync)
        }
    }

    /**
     * Report "no sync was attempted" — no local-only banner, and TigerSync's
     * row reading Minimal rather than OK.
     *
     * Deliberately does not clear BACKEND's status. It used to, because a
     * stale OK from an earlier successful pull would otherwise stay green for
     * the rest of the process; [ServerStatusTracker.setCloudSyncEnabled] now
     * drops it on the flip instead, which is the moment the reading actually
     * stops meaning what it meant. Clearing here as well would blank it on
     * every pull, and with sync off that is every refresh — stamping out the
     * reachability the public GETs report between one call and the next.
     */
    private fun markBackendIdle() {
        prefs.setLastSyncSource(SyncSource.NONE)
        // Three reasons land here and only one of them is "switched off"; the
        // tracker folds fdroid in itself, and logged-out hides the dot.
        ServerStatusTracker.setCloudSyncEnabled(prefs.cloudSyncEnabled)
    }

    /**
     * Answer to the conflict dialog. Keeping local re-asserts it upstream.
     *
     * [result] and [conflicts] are passed in rather than read from [state]
     * because the caller clears both synchronously to dismiss the dialog —
     * by the time this runs they are already gone from the live state.
     */
    suspend fun applyResolution(
        state: HomeSyncState,
        result: BackendSyncResult,
        conflicts: List<AssignmentSyncConflict>,
        keepLocal: Boolean,
    ) {
        if (keepLocal) {
            for (c in conflicts) {
                val mid = c.id.toIntOrNull() ?: continue
                runCatching { pushApiClient.patchAssignmentOverride(mid, c.localStatus) }
            }
        } else {
            markStep.keepServer(state, result)
            if (result.courseOverrides.isNotEmpty()) {
                applyCourseOverrides(state, result.courseOverrides, pendingCourseOverrides.held(result.fetchedAtMs))
            }
        }
    }

    /**
     * Returns false when it applied nothing, the course list not having
     * loaded. [held] is what the snapshot must not overwrite; see
     * [PendingCourseOverrides.held].
     */
    private suspend fun applyCourseOverrides(
        state: HomeSyncState,
        overrides: List<CourseOverrideResult>,
        held: Set<String>,
        syncColors: Boolean = CourseSyncReconciler.syncsCourseColors(
            prefs.syncCourseColors, prefs.pendingConflictCategories,
        ),
        syncNames: Boolean = CourseSyncReconciler.syncsCourseNames(
            prefs.syncCourseNames, prefs.pendingConflictCategories,
        ),
    ): Boolean {
        // The early return on an empty course list also skips the custom-name
        // merge below. That coupling predates this refactor and is preserved
        // deliberately: changing it would make names land on a device whose
        // course list has not loaded yet, which is a behaviour change and not
        // this commit's business.
        val courses = state.allCourses.value.ifEmpty { return false }

        CourseSyncReconciler.mergeCustomNames(
            existing = dataCache.loadCourseCustomNames(),
            overrides = overrides,
            syncNames = syncNames,
            held = held,
        )?.let { dataCache.saveCourseCustomNames(it) }

        CourseSyncReconciler.applyColorOverrides(
            courses = courses,
            overrides = overrides,
            syncColors = syncColors,
            held = held,
        )?.let { outcome ->
            val updated = outcome.courses
            Log.i(TAG, "[sync-color] applying ${updated.count { c -> c.customColorHex != null }} custom colors")
            state.allCourses.value = updated
            TigerDuckTheme.buildCourseColorMap(updated)
            dataCache.saveCourses(updated)
            widgetUpdater.requestUpdate()
            sendMovedColors(updated, outcome.moved) { moodleId, hex ->
                pendingCourseOverrides.send(moodleId, colorHex = hex)
            }
        }
        return true
    }

    /**
     * A course reset made on another device wipes this device's
     * manually-added courses, once: the server's `courses_reset_at` is
     * compared with the one the last full sync read, never with this phone's
     * clock — see [CourseSyncReconciler.coursesReset]. The account's first
     * sync here only remembers it, so a fresh install never reads its own
     * absence of history as a reset and deletes courses the user just added.
     * Remembered for [account] only: after a sign-out it would be the next
     * account's first sync that skipped a reset.
     *
     * The reset brings back the courses this phone hid, so the deletes still
     * waiting to go up from before the fetch ([beforeFetch]) go too: sent on
     * a later retry, one would take a restored course off the server again.
     * An add back under way goes with the waiting adds, for [courseSync]'s
     * commit as well; see DataCache.CourseSync.supersedeAddsBack.
     */
    private suspend fun applyCourseReset(
        result: BackendSyncResult,
        account: String?,
        beforeFetch: Long,
        courseSync: DataCache.CourseSync,
    ) {
        val reset = CourseSyncReconciler.coursesReset(
            result.coursesResetAt, prefs.lastSeenCoursesResetAt, prefs.legacyLastCourseSyncAtMs,
        )
        if (reset.apply) {
            val allCourses = dataCache.loadCourses().toMutableList()
            // Their waiting colours and names go with them; see PendingCourseOverrides.forget.
            pendingCourseOverrides.forget(allCourses.filter { it.isManual }.mapNotNull { it.moodleIdNumber })
            allCourses.removeAll { it.isManual }
            dataCache.saveCourses(allCourses)
            dataCache.saveDeletedCourseNos(emptySet())
            // In every term: the reconcile would keep a course whose add waits.
            pendingCourseAdds.forgetAll()
            courseSync.supersedeAddsBack()
            // Under the adds lock, as a term reset's forgetTerm: a retry waiting behind it then
            // finds them gone. One asked since the fetch is newer than the reset, and stays.
            pendingCourseAdds.removing {
                pendingCourseDeletes.forgetAskedBefore(beforeFetch)
            }
            Log.i(TAG, "[sync] courses reset detected, wiped manual courses")
        }
        if (authService.isStillSignedInAs(account)) {
            prefs.lastSeenCoursesResetAt = reset.remember
            prefs.forgetLegacyLastCourseSyncAt()
        }
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
    private suspend fun syncCourseList(result: BackendSyncResult, syncCourses: Boolean, courseSync: DataCache.CourseSync) {
        if (!syncCourses) return

        val resetAt = dataCache.loadSemesterResetAt()
        val semesters = CourseSyncReconciler.semestersToReconcile(
            serverCourses = result.serverCourses,
            tombstones = result.tombstones,
            catalogue = semesterCatalog.availableSemesters(),
            excluding = CourseSyncReconciler.termsResetAfter(
                result.fetchedAtMs, resetAt, dataCache.resettingSemesters(),
            ),
        )
        dataCache.clearSemesterResetAt(CourseSyncReconciler.resetStampsOutlived(result.fetchedAtMs, resetAt))
        val rowsBySemester = result.serverCourses.groupBy { it.semester }
        val selectionDropped = dataCache.loadSelectionDroppedNos()
        val serverKnown = dataCache.loadServerKnownNos()
        val updatedKnown = serverKnown.toMutableMap()
        var tombstones = migratedTombstones(semesters)
        val currentSemester = courseService.currentSemesterCode()
        var touchedCurrent = false

        for (semester in semesters) {
            val localCourses = dataCache.loadCourses(semester)
            // After the courses: see CourseSyncReconciler.withoutRestored.
            val restored = dataCache.restoredCoursesIn(semester, result.fetchedAtMs)
            tombstones = CourseSyncReconciler.withoutRestored(semester, tombstones, restored)
            val read = tombstones
            val tombstoneNos = CourseSyncReconciler.tombstoneNosFor(semester, result.tombstones)
            val outcome = CourseSyncReconciler.reconcileSemester(
                semester = semester,
                localCourses = localCourses,
                serverRows = rowsBySemester[semester].orEmpty(),
                tombstoneNos = tombstoneNos,
                tombstones = tombstones,
                serverKnownNos = serverKnown[semester].orEmpty().toSet(),
                // Deletes the retry above could not land: the snapshot still lists them.
                graceCourseNos = pendingCourseDeletes.waitingIn(semester),
                // Adds the retry above could not land: the snapshot does not list them yet.
                pendingAddNos = pendingCourseAdds.waitingIn(semester),
                selectionDroppedNos = selectionDropped[semester].orEmpty().toSet(),
                restoredNos = restored,
            )
            dataCache.restoredCoursesListed(semester, rowsBySemester[semester].orEmpty().map { it.courseNo }, result.fetchedAtMs)
            tombstones = outcome.tombstones
            if (outcome.serverKnownNos.isNotEmpty()) {
                updatedKnown[semester] = outcome.serverKnownNos.sorted()
            }

            // Before the upload branch: an empty term can still have hidden
            // courses to take out of the cache (a reset elsewhere, with one
            // hand-typed survivor left to upload).
            val pruned = CourseSyncReconciler.pruneHidden(semester, localCourses, tombstones)
            if (outcome.merged.isNotEmpty()) {
                Log.i(TAG, "[Sync] $semester: merged from server ${outcome.merged.map { it.courseNo }}")
            }
            if (commitReconciledTerm(dataCache, courseSync, semester, read, tombstones, tombstoneNos, localCourses, pruned, outcome.merged, pendingCourseOverrides) &&
                semester == currentSemester
            ) {
                touchedCurrent = true
            }
            if (outcome.uploadLocal) {
                // Sent whatever the upload ledger remembers (resend): the
                // server has nothing for the term, so a reset elsewhere took
                // the list it last accepted, and that same list has to go again.
                runCatching { pushApiClient.uploadCourses(outcome.toUpload, semester, resend = true) }
                    .onSuccess { accepted ->
                        // The server has now seen these numbers: from here
                        // on their absence is a deletion, not a pending
                        // upload — see reconcileSemester. Only what it
                        // accepted: a tombstoned course it skipped is still
                        // unknown to it.
                        if (!accepted.isNullOrEmpty()) {
                            updatedKnown[semester] = (outcome.serverKnownNos + accepted).sorted()
                        }
                    }
                    .onFailure { e -> Log.w(TAG, "[Sync] $semester: auto-upload failed: ${e.logSummary()}") }
                Log.i(TAG, "[Sync] $semester: server empty, uploaded ${outcome.toUpload.size} local courses")
            }
        }

        if (updatedKnown != serverKnown) {
            // Added to what is stored now, the numbers only growing: an add answered meanwhile,
            // or the background run, may have added to it too.
            dataCache.updateServerKnownNos { current ->
                current + updatedKnown.mapValues { (semester, nos) -> (current[semester].orEmpty() + nos).distinct().sorted() }
            }
        }
        if (touchedCurrent) widgetUpdater.requestUpdate()
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
    private suspend fun migratedTombstones(semesters: List<String>): Set<String> {
        val stored = dataCache.loadDeletedCourseNos()
        if (stored.none { ':' !in it }) return stored
        val rosters = semesters.associateWith { semester ->
            dataCache.loadCourses(semester).map { it.courseNo }.toSet()
        }
        // Under the hides lock: a bare one lifted while the rosters were read stays lifted.
        val migrated = dataCache.pinLegacyCourseHides(stored, rosters)
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

/**
 * A term's reconcile committed to [dataCache], by Home's sync and the background one: the hides
 * it changed, from [read], the hides it was handed, to [hides], the marks of those
 * [tombstoneNos], the course numbers its snapshot's tombstones name, hold let go of, and the
 * term's courses, without those it pruned from [localCourses], its own load ([kept] says which
 * stay, null when it pruned none), and with the rows it [merged] down. Each is applied to the
 * store as it stands, so a delete, a fetch or a hide lifted while the sync ran stays. Returns
 * whether it wrote the courses.
 *
 * The hides first: the courses keep out every merged row the store hides when they are written,
 * and a course another device added back has its row merged and its hide lifted by the same
 * reconcile, so written the other way round it stays missing until the next sync. A hide a
 * delete wrote since the read is no change of this reconcile's, and still keeps its row out.
 * The marks go with the hides, in one write: a delete made before it marks its hide, and one
 * made after finds the course hidden and marks nothing (`DataCache.hideDeletedCourse`). A course
 * selection answer that lifted a tombstoned hide meanwhile is undone, and every course the
 * commit hid goes from the term, wherever the term has it now.
 *
 * A course put back in the term since [courseSync], the sync's, began is neither hidden nor
 * pruned for the older read: added back by hand, or brought back where the server is silent
 * (`DataCache.commitSyncHides`). Its hide not written, its row stays, as one whose hide was lifted.
 *
 * A course the commit takes out of the term, hidden since here or on another device, takes its
 * waiting colour and name in [overrides] with it, those set before the commit wrote the term:
 * a course put back meanwhile stays, and so does what waits for it. The two go together however
 * the sync is cancelled: a course gone with its changes left waiting would have them refused at
 * every sync, and given to it again if it came back.
 */
internal suspend fun commitReconciledTerm(
    dataCache: DataCache,
    courseSync: DataCache.CourseSync,
    semester: String,
    read: Set<String>,
    hides: Set<String>,
    tombstoneNos: Set<String>,
    localCourses: List<Course>,
    kept: List<Course>?,
    merged: List<Course>,
    overrides: PendingCourseOverrides,
): Boolean {
    val newlyHidden = dataCache.commitSyncHides(semester, read, hides, tombstoneNos, courseSync)
    if (kept == null && merged.isEmpty() && newlyHidden.isEmpty()) return false
    withContext(NonCancellable) {
        val before = overrides.reserveVersion()
        var removed = emptyList<Course>()
        dataCache.updateCourses(semester) { current, hidden ->
            CourseSyncReconciler.committedCourses(current, localCourses, kept ?: localCourses, merged, hidden, newlyHidden)
                .also { committed ->
                    val stays = committed.mapTo(mutableSetOf()) { it.courseNo }
                    removed = current.filter { it.courseNo !in stays }
                }
        }
        overrides.forget(removed.mapNotNull { it.moodleIdNumber }, madeBefore = before)
    }
    return true
}

/**
 * Sends each course the colour pass moved ([moved], by course number) its new
 * colour through [patch], by the Moodle id the backend keys the course by, in
 * course-number order. A course without one is skipped, as a pick on the class
 * table skips it. Through [PendingCourseOverrides], which keeps a colour that
 * fails to land, and in place of an older colour of the course still waiting.
 */
internal suspend fun sendMovedColors(
    courses: List<Course>,
    moved: Map<String, String>,
    patch: suspend (moodleId: String, hex: String) -> Unit,
) {
    for ((courseNo, hex) in moved.toSortedMap()) {
        val moodleId = courses.find { it.courseNo == courseNo }?.moodleIdNumber ?: continue
        try {
            patch(moodleId, hex)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("HomeViewModel", "[sync-color] moved colour PATCH failed: ${e.logSummary()}")
        }
    }
}

/** A full sync's snapshot, and the sign-in its first-sync mark upload is pinned to. */
internal data class PinnedSnapshot(val result: BackendSyncResult, val signIn: Int)

/**
 * [fetch]es a full sync's snapshot, with the sign-in
 * (`AuthTokenManager.signInGeneration`) read before it: read after, a
 * sign-out and another sign-in while the fetch was out would pin this
 * account's first-sync mark upload to the next account's session.
 */
internal suspend fun fetchPinned(
    signInGeneration: () -> Int,
    fetch: suspend () -> BackendSyncResult,
): PinnedSnapshot {
    val signIn = signInGeneration()
    return PinnedSnapshot(fetch(), signIn)
}
