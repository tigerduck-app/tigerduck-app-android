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

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.SemesterCatalog
import org.ntust.app.tigerduck.notification.SyncSource
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.SyncApiClient
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.component.ServerFailureSimulator
import org.ntust.app.tigerduck.ui.component.ServerKind
import org.ntust.app.tigerduck.ui.component.ServerStatus
import org.ntust.app.tigerduck.ui.component.ServerStatusTracker
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
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
    @param:ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val dataCache: DataCache,
    private val authService: AuthService,
    private val authTokenManager: AuthTokenManager,
    private val syncApiClient: SyncApiClient,
    private val pushApiClient: PushApiClient,
    private val courseService: CourseService,
    private val semesterCatalog: SemesterCatalog,
    private val widgetUpdater: WidgetUpdater,
    private val academicCalendar: org.ntust.app.tigerduck.academic.AcademicCalendarStore,
) {

    /**
     * Pull everything from the backend and reconcile it into [state].
     *
     * [retried] guards the 401 path against looping: a relogin that still
     * produces a 401 gives up rather than recursing.
     */
    suspend fun pull(state: HomeSyncState, retried: Boolean = false) {
        if (!prefs.cloudSyncEnabled || BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)) {
            markBackendIdle()
            return
        }
        if (!authTokenManager.isLoggedIn) {
            markBackendIdle()
            return
        }
        try {
            if (BuildConfig.DEBUG) ServerFailureSimulator.check(ServerKind.BACKEND)
            val result = syncApiClient.fetchFullSync()
            syncAssignmentOverrides(state, result)
            // Course overrides and deletion detection always run regardless
            // of whether this was a first-time migration.
            if (result.courseOverrides.isNotEmpty() && (prefs.syncCourseColors || prefs.syncCourseNames)) {
                applyCourseOverrides(state, result.courseOverrides)
            }
            applyCourseReset(result)
            // Not gated on any of the per-category sync toggles: a holiday
            // exception is a notification setting, not course or assignment
            // data, and the categories the user can turn off do not cover
            // it. Null means an older backend that does not send the
            // section, so the local set stands.
            result.holidayOverrides?.let(academicCalendar::applySyncedOverrides)
            syncCourseList(result)
            markCourseSyncAt()
            state.lastKnownRevision = result.currentRevision
            ServerStatusTracker.set(ServerStatus.OK, ServerKind.BACKEND)
            prefs.setLastSyncSource(SyncSource.BACKEND)
            widgetUpdater.requestUpdate()
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
            if (!retried && (e.message?.contains("401") == true || e.message?.contains("session_revoked") == true)) {
                // Delegates to AuthService so the relogin harvests a fresh
                // Moodle token — the backend rejects stale stored tokens with
                // 401 invalid_token.
                if (authService.attemptRelogin()) {
                    pull(state, retried = true)
                }
            }
            Log.w(TAG, "[Sync] override sync failed", e)
        }
    }

    /**
     * Report "no sync was attempted" — grey cloud, no local-only banner.
     *
     * Clearing the tracker is the load-bearing half. [ServerStatusTracker] is
     * a process-wide singleton, so an OK written by an earlier successful pull
     * stays green for the rest of the process. Turning cloud sync off (or
     * logging out) only short-circuits `pull`, so without this the indicator
     * kept claiming a healthy backend on every screen that shows it.
     */
    private fun markBackendIdle() {
        prefs.setLastSyncSource(SyncSource.NONE)
        ServerStatusTracker.set(ServerStatus.UNKNOWN, ServerKind.BACKEND)
        // Grey is the right colour for all three reasons we land here, but
        // only one of them is "switched off" — the status dot's detail list
        // needs to tell that apart from logged-out and fdroid.
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
            val outcome = AssignmentOverrideReconciler.serverWins(
                serverIgnored = result.ignoredIds,
                serverCompleted = result.completedIds,
                inFlightIgnored = state.ignoredAssignmentIds.value,
                inFlightCompleted = state.markedCompletedIds.value,
                pendingOverrides = state.pendingOverrides,
            )
            commitOverrides(state, outcome)
            if (result.courseOverrides.isNotEmpty()) {
                applyCourseOverrides(state, result.courseOverrides)
            }
        }
    }

    private suspend fun syncAssignmentOverrides(state: HomeSyncState, result: BackendSyncResult) {
        val localIgnored = dataCache.loadIgnoredAssignments()
        val localMarked = dataCache.loadMarkedCompletedAssignments()

        if (AssignmentOverrideReconciler.isFirstUpload(
                serverIgnored = result.ignoredIds,
                serverCompleted = result.completedIds,
                localIgnored = localIgnored,
                localCompleted = localMarked,
            )
        ) {
            for (id in localIgnored) {
                runCatching {
                    pushApiClient.patchAssignmentOverride(
                        id.toIntOrNull() ?: return@runCatching,
                        AssignmentOverrideReconciler.STATUS_IGNORED,
                    )
                }
            }
            for (id in localMarked) {
                runCatching {
                    pushApiClient.patchAssignmentOverride(
                        id.toIntOrNull() ?: return@runCatching,
                        AssignmentOverrideReconciler.STATUS_COMPLETED,
                    )
                }
            }
            return
        }

        val byId = state.allAssignments.value.associateBy { it.assignmentId }
        val outcome = AssignmentOverrideReconciler.reconcile(
            serverIgnored = result.ignoredIds,
            serverCompleted = result.completedIds,
            localIgnored = localIgnored,
            localCompleted = localMarked,
            inFlightIgnored = state.ignoredAssignmentIds.value,
            inFlightCompleted = state.markedCompletedIds.value,
            pendingOverrides = state.pendingOverrides,
            labelFor = { byId[it]?.title },
        )
        commitOverrides(state, outcome)
        if (outcome.conflicts.isNotEmpty()) {
            state.pendingResult = result
            state.conflicts.value = outcome.conflicts
        }
    }

    private suspend fun commitOverrides(
        state: HomeSyncState,
        outcome: AssignmentOverrideReconciler.Outcome,
    ) {
        dataCache.replaceIgnoredAssignments(outcome.ignored)
        dataCache.replaceMarkedCompletedAssignments(outcome.completed)
        state.ignoredAssignmentIds.value = outcome.ignored
        state.markedCompletedIds.value = outcome.completed
    }

    private suspend fun applyCourseOverrides(
        state: HomeSyncState,
        overrides: List<CourseOverrideResult>,
    ) {
        // The early return on an empty course list also skips the custom-name
        // merge below. That coupling predates this refactor and is preserved
        // deliberately: changing it would make names land on a device whose
        // course list has not loaded yet, which is a behaviour change and not
        // this commit's business.
        val courses = state.allCourses.value.ifEmpty { return }

        CourseSyncReconciler.mergeCustomNames(
            existing = dataCache.loadCourseCustomNames(),
            overrides = overrides,
            syncNames = prefs.syncCourseNames,
        )?.let { dataCache.saveCourseCustomNames(it) }

        CourseSyncReconciler.applyColorOverrides(
            courses = courses,
            overrides = overrides,
            syncColors = prefs.syncCourseColors,
        )?.let { updated ->
            Log.i(TAG, "[sync-color] applying ${updated.count { c -> c.customColorHex != null }} custom colors")
            state.allCourses.value = updated
            TigerDuckTheme.buildCourseColorMap(updated)
            dataCache.saveCourses(updated)
            widgetUpdater.requestUpdate()
        }
    }

    /**
     * A backend-side course reset wipes this device's manually-added courses.
     *
     * Gated on `lastCourseSyncAt > 0` so a fresh install never reads its own
     * absence of history as a reset and deletes courses the user just added.
     */
    private suspend fun applyCourseReset(result: BackendSyncResult) {
        val lastCourseSyncAt = syncPrefs().getLong(KEY_LAST_COURSE_SYNC_AT, 0L)
        val resetAt = result.coursesResetAt?.let { parseIsoTimestamp(it) } ?: 0L
        if (resetAt > lastCourseSyncAt && lastCourseSyncAt > 0L) {
            val allCourses = dataCache.loadCourses().toMutableList()
            allCourses.removeAll { it.isManual }
            dataCache.saveCourses(allCourses)
            dataCache.saveDeletedCourseNos(emptySet())
            Log.i(TAG, "[sync] courses reset detected, wiped manual courses")
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
     */
    private suspend fun syncCourseList(result: BackendSyncResult) {
        if (!prefs.syncCourses) return

        val semesters = CourseSyncReconciler.semestersToReconcile(
            serverCourses = result.serverCourses,
            tombstones = result.tombstones,
            catalogue = semesterCatalog.availableSemesters(),
        )
        val rowsBySemester = result.serverCourses.groupBy { it.semester }
        var tombstones = migratedTombstones(semesters)
        val tombstonesBefore = tombstones
        val currentSemester = courseService.currentSemesterCode()
        var touchedCurrent = false

        for (semester in semesters) {
            val localCourses = dataCache.loadCourses(semester)
            val outcome = CourseSyncReconciler.reconcileSemester(
                semester = semester,
                localCourses = localCourses,
                serverRows = rowsBySemester[semester].orEmpty(),
                tombstoneNos = CourseSyncReconciler.tombstoneNosFor(semester, result.tombstones),
                tombstones = tombstones,
            )
            tombstones = outcome.tombstones

            if (outcome.uploadLocal) {
                runCatching { pushApiClient.uploadCourses(localCourses, semester) }
                    .onFailure { e -> Log.w(TAG, "[Sync] $semester: auto-upload failed", e) }
                Log.i(TAG, "[Sync] $semester: server empty, uploaded ${localCourses.size} local courses")
                continue
            }
            if (outcome.merged.isNotEmpty()) {
                Log.i(TAG, "[Sync] $semester: merged from server ${outcome.merged.map { it.courseNo }}")
                dataCache.saveCourses(localCourses + outcome.merged, semester)
                if (semester == currentSemester) touchedCurrent = true
            }
        }

        if (tombstones != tombstonesBefore) {
            dataCache.saveDeletedCourseNos(tombstones)
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
        val migrated = CourseTombstoneKeys.migrateLegacyEntries(stored, rosters)
        if (migrated != stored) {
            dataCache.saveDeletedCourseNos(migrated)
            Log.i(TAG, "[Sync] pinned ${stored.size - migrated.count { ':' !in it }} legacy tombstones to their terms")
        }
        return migrated
    }

    private fun markCourseSyncAt() {
        syncPrefs().edit()
            .putLong(KEY_LAST_COURSE_SYNC_AT, System.currentTimeMillis())
            .apply()
    }

    private fun syncPrefs() = context.getSharedPreferences(SYNC_PREFS, 0)

    private companion object {
        const val TAG = "HomeViewModel"
        const val SYNC_PREFS = "tigerduck_sync"
        const val KEY_LAST_COURSE_SYNC_AT = "last_course_sync_at"
    }
}

/**
 * Epoch millis from the backend's ISO-8601 course-reset timestamp, which is
 * UTC and may carry a fractional part the 19-character prefix drops.
 *
 * Returns 0 for anything unparseable. [HomeBackendSync.applyCourseReset]
 * compares the result against a stored watermark where 0 reads as "no
 * information" and skips the reset, so a malformed timestamp can never wipe
 * local courses -- which is why the catch is deliberately total.
 *
 * Lived in HomeAssignmentFilters until it was noticed that nothing about it
 * is an assignment or a filter. Top-level rather than a private member so it
 * stays beside its only caller without becoming untestable. `LocalDateTime`
 * rather than the previous `SimpleDateFormat`: same accepted input, but
 * immutable and shared instead of a fresh formatter per call.
 */
internal fun parseIsoTimestamp(s: String): Long {
    if (s.isBlank()) return 0L
    return try {
        LocalDateTime.parse(s.take(19)).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (_: Exception) {
        0L
    }
}
