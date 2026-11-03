package org.ntust.app.tigerduck.sync

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.CourseIgnoreSync
import org.ntust.app.tigerduck.data.PendingCourseAdds
import org.ntust.app.tigerduck.data.PendingCourseDeletes
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.PendingHolidayOverrides
import org.ntust.app.tigerduck.data.UnsentMarks
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.NotificationSettingsSync
import org.ntust.app.tigerduck.push.SyncApiClient
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.reconcile.CourseResetReconciler
import org.ntust.app.tigerduck.sync.reconcile.HolidaysReconciler
import org.ntust.app.tigerduck.sync.reconcile.IgnoresReconciler
import org.ntust.app.tigerduck.sync.reconcile.MarksReconciler
import org.ntust.app.tigerduck.sync.reconcile.OverridesReconciler
import org.ntust.app.tigerduck.sync.reconcile.Reconciler
import org.ntust.app.tigerduck.sync.reconcile.Reconcilers
import org.ntust.app.tigerduck.sync.reconcile.RosterReconciler
import org.ntust.app.tigerduck.ui.component.ServerFailureSimulator
import org.ntust.app.tigerduck.ui.component.ServerKind
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncState
import org.ntust.app.tigerduck.ui.screen.settings.ReenableCheck
import org.ntust.app.tigerduck.ui.screen.settings.categoriesTaken
import org.ntust.app.tigerduck.util.logSummary
import org.ntust.app.tigerduck.widget.WidgetUpdater
import javax.inject.Inject
import javax.inject.Singleton

/** Who runs a full sync: Home, on screen, or the background worker (C2). */
enum class SyncMode { FOREGROUND, BACKGROUND }

/** How a full sync ended: it ran, it did not run (switched off, no session, or past its sign-in), or it failed. */
enum class SyncOutcome { SYNCED, SKIPPED, FAILED }

/**
 * One full sync after its fetch, as each [Reconciler] reads it: the snapshot ([result]), the
 * sign-in it was fetched as, and one reading of the switches and the hold ([policy]), taken once
 * the snapshot is in, so every step decides from the same one. [courseSync] is the window the
 * course steps commit against (`DataCache.beginCourseSync`), and [beforeFetch] the deletes'
 * stamp taken before the fetch (`PendingCourseDeletes.stamp`).
 */
class SyncPass(
    val result: BackendSyncResult,
    val signIn: SignIn,
    val mode: SyncMode,
    val policy: SyncPolicy,
    val state: HomeSyncState?,
    val courseSync: DataCache.CourseSync,
    val beforeFetch: Long,
) {
    /** Home's own state, which a pass in the foreground updates as it goes. */
    val home: HomeSyncState get() = checkNotNull(state) { "a foreground pass runs over Home's state" }

    /**
     * Whether the marks step changed this phone's marks, set as it runs: the background worker
     * arms the assignment reminders again then, as homework ignored or marked done on another
     * device takes its reminders with it.
     */
    var marksChanged: Boolean = false
        internal set
}

/**
 * Every full sync: the retries, the fetch, then each category's [Reconciler] in the order
 * [Reconcilers.inOrder] pins, then what the pass took, its watermark, its status and the steps
 * after it. `SynchronizerOrderTest` holds the order. Home's runs in [SyncMode.FOREGROUND] over
 * [ForegroundSteps]; the background worker's in [SyncMode.BACKGROUND] over [BackgroundSteps], the
 * narrower pass it always ran (C2), each reconciler doing the worker's step where the two differed.
 *
 * Failure policy is "degrade to local, never lose a tap": a throw reports the backend as failed
 * and leaves the cache alone. A pass that fails must never look like one that found nothing.
 * Cancellation is rethrown untouched: leaving Home mid-sync is no backend failure, and reporting
 * one turns on the "local only" banner for a sync nobody was waiting for.
 */
@Singleton
class Synchronizer internal constructor(
    private val foreground: PassSteps,
    private val background: PassSteps,
    private val marks: Reconciler,
    private val ignores: Reconciler,
    private val overrides: Reconciler,
    private val reset: Reconciler,
    private val holidays: Reconciler,
    private val roster: Reconciler,
) {
    @Inject constructor(
        prefs: AppPreferences,
        syncPolicies: SyncPolicies,
        dataCache: DataCache,
        authTokenManager: AuthTokenManager,
        authService: AuthService,
        syncApiClient: SyncApiClient,
        courseIgnoreSync: CourseIgnoreSync,
        unsentMarks: UnsentMarks,
        pendingCourseAdds: PendingCourseAdds,
        pendingCourseDeletes: PendingCourseDeletes,
        pendingCourseOverrides: PendingCourseOverrides,
        pendingHolidayOverrides: PendingHolidayOverrides,
        firstPullGate: FirstPullGate,
        widgetUpdater: WidgetUpdater,
        reenableCheck: ReenableCheck,
        notificationSettingsSync: NotificationSettingsSync,
        @ApplicationScope appScope: CoroutineScope,
        marks: MarksReconciler,
        ignores: IgnoresReconciler,
        overrides: OverridesReconciler,
        reset: CourseResetReconciler,
        holidays: HolidaysReconciler,
        roster: RosterReconciler,
    ) : this(
        ForegroundSteps(
            prefs, syncPolicies, dataCache, authTokenManager, syncApiClient::fetchFullSync,
            UnsentQueues(courseIgnoreSync, unsentMarks, pendingCourseAdds, pendingCourseDeletes, pendingCourseOverrides, pendingHolidayOverrides),
            firstPullGate, widgetUpdater, reenableCheck, notificationSettingsSync, appScope,
        ),
        BackgroundSteps(
            prefs, syncPolicies, dataCache, authTokenManager, syncApiClient::fetchFullSync, authService::attemptRelogin,
            UnsentQueues(courseIgnoreSync, unsentMarks, pendingCourseAdds, pendingCourseDeletes, pendingCourseOverrides, pendingHolidayOverrides),
            firstPullGate,
        ),
        marks, ignores, overrides, reset, holidays, roster,
    )

    private val inOrder = Reconcilers.inOrder(marks, ignores, overrides, reset, holidays, roster)

    /**
     * Sends what waits to go up, every queue in the order the retries always ran. A full sync
     * starts with this, and Home's revision poll sends it without one.
     */
    suspend fun retryUnsent(signIn: SignIn) = foreground.retryUnsent(signIn)

    /**
     * Runs one full sync under [signIn], the sign-in its caller read once where it started, over
     * Home's [state] in the foreground. [afterSync] runs once a pass has landed, before its course
     * window closes and as part of the pass: the worker's settings pull and reminders, which it
     * starts on its session. [afterFailure] runs once a pass that failed has said so, before its
     * course window closes: Home's relogin, which runs the sync again.
     */
    suspend fun sync(
        mode: SyncMode,
        signIn: SignIn,
        state: HomeSyncState? = null,
        afterSync: suspend (SyncPass) -> Unit = {},
        afterFailure: suspend (Exception) -> Unit = {},
    ): SyncOutcome {
        val steps = when (mode) {
            SyncMode.FOREGROUND -> foreground
            SyncMode.BACKGROUND -> background
        }
        if (!steps.mayRun()) return SyncOutcome.SKIPPED
        // Before anything it sends or fetches, until it is done: see DataCache.beginCourseSync.
        val courseSync = steps.beginCourseSync()
        try {
            if (mode == SyncMode.FOREGROUND && BuildConfig.DEBUG) ServerFailureSimulator.check(ServerKind.BACKEND)
            // What waits to go up goes first, so the snapshot has it.
            steps.retryUnsent(signIn)
            // Every class-table delete asked before the fetch ranks below it, every later one
            // above: a course reset the snapshot reads lets go of the first only; see CourseResetReconciler.
            val beforeFetch = steps.stampDeletes()
            // Marks the course window's fetch, each attempt's: see PassSteps.fetch.
            val result = steps.fetch(signIn, courseSync)
            steps.listed(result, signIn)
            val pass = SyncPass(result, signIn, mode, steps.policy(), state, courseSync, beforeFetch)
            val took = inOrder.associateWith { it.reconcile(pass) }
            steps.recordTaken(pass, taken(pass, took))
            steps.noteRevision(pass)
            steps.notePull(pass)
            steps.synced()
            steps.afterSync(pass)
            afterSync(pass)
            return SyncOutcome.SYNCED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A run past its sign-out says nothing of the backend: the status is the next account's.
            if (!signIn.isCurrent) return SyncOutcome.SKIPPED
            steps.failed()
            afterFailure(e)
            Log.w(TAG, "[Sync] override sync failed: ${e.logSummary()}")
            return SyncOutcome.FAILED
        } finally {
            steps.endCourseSync(courseSync)
        }
    }

    /** The categories whose backend data the pass took, from what each step answered. */
    private fun taken(pass: SyncPass, took: Map<Reconciler, Boolean>): Set<String> = categoriesTaken(
        marksTaken = took.getValue(marks),
        courseListSynced = took.getValue(roster),
        syncColors = pass.policy.applies(SyncCategory.COURSE_COLORS),
        syncNames = pass.policy.applies(SyncCategory.COURSE_NAMES),
        hasOverrides = pass.result.courseOverrides.isNotEmpty(),
        coursesLoaded = took.getValue(overrides),
    )

    private companion object {
        const val TAG = "HomeViewModel"
    }
}

/**
 * What a pass does besides its reconcilers, each a call [Synchronizer.sync] makes in its order:
 * one seam, so `SynchronizerOrderTest` can record that order. [ForegroundSteps] is the real one.
 */
internal interface PassSteps {
    /** Whether a pass may run: TigerSync on, and a session with the backend. If not, says no sync was attempted. */
    suspend fun mayRun(): Boolean
    fun beginCourseSync(): DataCache.CourseSync
    fun endCourseSync(courseSync: DataCache.CourseSync)
    suspend fun retryUnsent(signIn: SignIn)
    suspend fun stampDeletes(): Long

    /**
     * The snapshot under [signIn], marking [courseSync]'s fetch ([DataCache.CourseSync.fetching])
     * for the attempt the snapshot comes from: an add back made from there on is newer than a
     * course reset it reads.
     */
    suspend fun fetch(signIn: SignIn, courseSync: DataCache.CourseSync): BackendSyncResult
    /** The adds the snapshot lists have landed. */
    suspend fun listed(result: BackendSyncResult, signIn: SignIn)
    fun policy(): SyncPolicy
    fun recordTaken(pass: SyncPass, took: Set<String>)
    fun noteRevision(pass: SyncPass)
    fun notePull(pass: SyncPass)
    fun synced()
    fun afterSync(pass: SyncPass)
    fun failed()
}
