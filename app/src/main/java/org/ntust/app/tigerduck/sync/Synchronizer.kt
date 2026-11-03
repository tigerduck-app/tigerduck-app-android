package org.ntust.app.tigerduck.sync

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.PendingCourseAdds
import org.ntust.app.tigerduck.data.PendingCourseDeletes
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.NotificationSettingsSync
import org.ntust.app.tigerduck.push.SyncApiClient
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.reconcile.CourseResetReconciler
import org.ntust.app.tigerduck.sync.reconcile.FirstSyncReconciler
import org.ntust.app.tigerduck.sync.reconcile.HolidaysReconciler
import org.ntust.app.tigerduck.sync.reconcile.IgnoresReconciler
import org.ntust.app.tigerduck.sync.reconcile.MarksReconciler
import org.ntust.app.tigerduck.sync.reconcile.OverridesReconciler
import org.ntust.app.tigerduck.sync.reconcile.PullHolds
import org.ntust.app.tigerduck.sync.reconcile.Reconciler
import org.ntust.app.tigerduck.sync.reconcile.Reconcilers
import org.ntust.app.tigerduck.sync.outbox.Outbox
import org.ntust.app.tigerduck.sync.reconcile.RosterReconciler
import org.ntust.app.tigerduck.ui.component.ServerFailureSimulator
import org.ntust.app.tigerduck.ui.component.ServerKind
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncState
import org.ntust.app.tigerduck.ui.screen.settings.ReenableCheck
import org.ntust.app.tigerduck.ui.screen.settings.categoriesTaken
import org.ntust.app.tigerduck.util.logSummary
import org.ntust.app.tigerduck.widget.WidgetUpdater
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Who runs a full sync: Home, on screen, or the background worker (C2). */
enum class SyncMode { FOREGROUND, BACKGROUND }

/** How a full sync ended: it ran, it did not run (switched off, no session, or past its sign-in), or it failed. */
enum class SyncOutcome { SYNCED, SKIPPED, FAILED }

/**
 * One full sync after its fetch, as each [Reconciler] reads it: the snapshot ([result]), the
 * sign-in it was fetched as, and one reading of the switches and the hold ([policy]), taken once
 * the snapshot is in, so every step of Home's decides from the same one. The background's colors
 * and names and its course list read theirs at their own step, as the worker always did. [courseSync] is the window the
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
    /** The categories this account had synced on this phone before the pass (LWW D13), read with [policy]. */
    val syncedBefore: Set<SyncCategory> = emptySet(),
    private val heldFields: suspend () -> Set<String> = { emptySet() },
    private val ack: AckClock = AckClock(),
) {
    /** Home's own state, which a pass in the foreground updates as it goes. */
    val home: HomeSyncState get() = checkNotNull(state) { "a foreground pass runs over Home's state" }

    private var holds: PullHolds? = null

    /**
     * What this phone keeps over the snapshot, field by field (LWW D12), or null for a snapshot
     * without a `server_hlc`, as `/v3`'s, whose own rules apply. The outbox's held fields are read
     * once, at the first step that asks, after the first sync's queued values (FirstSyncReconciler).
     */
    suspend fun holds(): PullHolds? {
        val serverHlc = result.serverHlc ?: return null
        return holds ?: PullHolds(serverHlc, heldFields(), ack).also { holds = it }
    }

    /**
     * Whether the marks step changed this phone's marks, set as it runs: the background worker
     * arms the assignment reminders again then, as homework ignored or marked done on another
     * device takes its reminders with it.
     */
    var marksChanged: Boolean = false
        internal set
}

/**
 * Every full sync: the outbox's drain, the fetch, then each category's [Reconciler] in the order
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
    /** Sends what waits to go up under a sign-in: the outbox's one drain ([Outbox.drain]). */
    private val drain: suspend (SignIn) -> Unit,
    private val foreground: PassSteps,
    private val background: PassSteps,
    private val marks: Reconciler,
    private val ignores: Reconciler,
    private val overrides: Reconciler,
    private val reset: Reconciler,
    private val holidays: Reconciler,
    private val roster: Reconciler,
    /** A category's first sync on `/v4` (LWW D13); none takes nothing. */
    private val firstSync: Reconciler = NO_FIRST_SYNC,
    /** Every field the outbox holds an edit of ([Outbox.heldFields]), read once per pull. */
    private val heldFields: suspend () -> Set<String> = { emptySet() },
    private val ack: AckClock = AckClock(),
) {
    @Inject constructor(
        prefs: AppPreferences,
        syncPolicies: SyncPolicies,
        dataCache: DataCache,
        authTokenManager: AuthTokenManager,
        authService: AuthService,
        syncApiClient: SyncApiClient,
        outbox: Outbox,
        pendingCourseAdds: PendingCourseAdds,
        pendingCourseDeletes: PendingCourseDeletes,
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
        ack: AckClock = AckClock(),
        firstSync: FirstSyncReconciler? = null,
    ) : this(
        drain = outbox::drain,
        ForegroundSteps(
            prefs, syncPolicies, dataCache, authTokenManager, syncApiClient::fetchFullSync, pendingCourseAdds, pendingCourseDeletes,
            firstPullGate, widgetUpdater, reenableCheck, notificationSettingsSync, appScope,
        ),
        BackgroundSteps(
            prefs, syncPolicies, dataCache, authTokenManager, syncApiClient::fetchFullSync, authService::attemptRelogin,
            pendingCourseAdds, firstPullGate,
        ),
        marks, ignores, overrides, reset, holidays, roster, firstSync ?: NO_FIRST_SYNC, outbox::heldFields, ack,
    )

    private val inOrder = Reconcilers.inOrder(firstSync, marks, ignores, overrides, reset, holidays, roster)

    /**
     * Sends what waits to go up: the outbox's one drain, every channel in its pinned order,
     * the order the queues were always sent in. Every full sync starts with this, in either mode,
     * and Home's revision poll sends it without one.
     */
    suspend fun retryUnsent(signIn: SignIn) = drain(signIn)

    // Under [lock]: the pass under way, and the passes queued after it, in order, none started yet.
    private val lock = Any()
    private var running: Running? = null
    private val queued = ArrayList<Running>()

    /**
     * A pass in [mode] under [signIn], and what it came to once that is known: null when it was cut
     * short. One queued [after] another starts once that one is done. Under [lock], but [landed].
     */
    private class Running(val signIn: SignIn, val mode: SyncMode, var after: Running? = null) {
        val landed = CompletableDeferred<Landed?>()
        var started = after == null
    }

    /** What a pass came to, and the pass itself once it synced. */
    private class Landed(val outcome: SyncOutcome, val pass: SyncPass?)

    private val waitingCallers = AtomicInteger()

    /** How many callers wait on a pass they did not start, or for the one under way to be done, for a test. */
    internal val waiting: Int get() = waitingCallers.get()

    /**
     * Runs one full sync under [signIn], the sign-in its caller read once where it started, over
     * Home's [state] in the foreground. [afterSync] runs once a pass has landed, before its course
     * window closes and as part of the pass: the worker's settings pull and reminders, which it
     * starts on its session. [afterFailure] runs once a pass that failed has said so, before its
     * course window closes: Home's relogin, which runs the sync again.
     *
     * One at a time (`tigersync-refresh-order`, "One refresh at a time"), as iOS's `FullSyncGate`
     * with a follow-up: asked for while a pass runs under the same sign-in, the sync waits for it,
     * then one more pass runs, whose snapshot is fetched after it asked, for every sync of its mode
     * asked for meanwhile. A sync of the other mode queues its own pass after those (iOS has one
     * mode; Home's steps and the worker's differ, and neither stands for the other's). So passes
     * never overlap, however many ask, and each reads what the outbox holds once. The pass's
     * steps, its [state] and its [afterFailure] are its first caller's; another caller gets its
     * outcome, and once it synced runs its own [afterSync] over it. A pass under another sign-in
     * is never waited for, and one cut short by its caller's cancellation is run again by a caller
     * that waited for it.
     */
    suspend fun sync(
        mode: SyncMode,
        signIn: SignIn,
        state: HomeSyncState? = null,
        afterSync: suspend (SyncPass) -> Unit = {},
        afterFailure: suspend (Exception) -> Unit = {},
    ): SyncOutcome {
        while (true) {
            val (shared, starts) = synchronized(lock) {
                queued.firstOrNull { it.signIn === signIn && it.mode == mode }?.let { return@synchronized it to false }
                val last = queued.lastOrNull { it.signIn === signIn } ?: running?.takeIf { it.signIn === signIn }
                Running(signIn, mode, after = last).also { if (last == null) running = it else queued += it } to true
            }
            if (starts) {
                if (!shared.started) awaitDone(shared)
                return run(shared, mode, signIn, state, afterSync, afterFailure)
            }
            val landed = waitingFor { shared.landed.await() }
            // Cut short by its caller's cancellation: this caller runs it again.
            if (landed == null) continue
            val pass = landed.pass ?: return landed.outcome
            return try {
                afterSync(pass)
                SyncOutcome.SYNCED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "[Sync] the steps after a shared sync failed: ${e.logSummary()}")
                SyncOutcome.FAILED
            }
        }
    }

    /**
     * [shared], queued, starts once the pass ahead of it is done: one let go before it started
     * never ran, so then it waits for what that one waited for. Cancelled meanwhile, it is cut
     * short for who waits on it.
     */
    private suspend fun awaitDone(shared: Running) {
        try {
            while (true) {
                val ahead = synchronized(lock) { shared.after } ?: break
                waitingFor { ahead.landed.await() }
                synchronized(lock) { if (shared.after === ahead) shared.after = if (ahead.started) null else ahead.after }
            }
        } catch (e: CancellationException) {
            land(shared, null)
            throw e
        }
        synchronized(lock) {
            queued.remove(shared)
            shared.started = true
            // A pass of the next sign-in under way keeps its place: the departing one's is not waited for.
            if (running?.signIn?.let { it === shared.signIn } != false) running = shared
        }
    }

    private suspend fun <T> waitingFor(wait: suspend () -> T): T {
        waitingCallers.incrementAndGet()
        try {
            return wait()
        } finally {
            waitingCallers.decrementAndGet()
        }
    }

    /** What [shared] came to, told to whoever waits on it; nobody waits on it from here. */
    private fun land(shared: Running, outcome: SyncOutcome?, pass: SyncPass? = null) {
        synchronized(lock) {
            if (running === shared) running = null
            queued.remove(shared)
        }
        shared.landed.complete(outcome?.let { Landed(it, pass) })
    }

    private suspend fun run(
        shared: Running,
        mode: SyncMode,
        signIn: SignIn,
        state: HomeSyncState?,
        afterSync: suspend (SyncPass) -> Unit,
        afterFailure: suspend (Exception) -> Unit,
    ): SyncOutcome = try {
        pass(shared, mode, signIn, state, afterSync, afterFailure)
    } finally {
        // Cut short, by a cancellation: who waited runs a pass of its own.
        land(shared, null)
    }

    private suspend fun pass(
        shared: Running,
        mode: SyncMode,
        signIn: SignIn,
        state: HomeSyncState?,
        afterSync: suspend (SyncPass) -> Unit,
        afterFailure: suspend (Exception) -> Unit,
    ): SyncOutcome {
        val steps = when (mode) {
            SyncMode.FOREGROUND -> foreground
            SyncMode.BACKGROUND -> background
        }
        if (!steps.mayRun()) return SyncOutcome.SKIPPED.also { land(shared, it) }
        // Before anything it sends or fetches, until it is done: see DataCache.beginCourseSync.
        val courseSync = steps.beginCourseSync()
        try {
            if (mode == SyncMode.FOREGROUND && BuildConfig.DEBUG) ServerFailureSimulator.check(ServerKind.BACKEND)
            // What waits to go up goes first, so the snapshot has it.
            drain(signIn)
            // Every class-table delete asked before the fetch ranks below it, every later one
            // above: a course reset the snapshot reads lets go of the first only; see CourseResetReconciler.
            val beforeFetch = steps.stampDeletes()
            // Marks the course window's fetch, each attempt's: see PassSteps.fetch.
            val result = steps.fetch(signIn, courseSync)
            steps.listed(result, signIn)
            val pass = SyncPass(result, signIn, mode, steps.policy(), state, courseSync, beforeFetch, steps.syncedBefore(), heldFields, ack)
            val took = inOrder.associateWith { it.reconcile(pass) }
            steps.recordTaken(pass, taken(pass, took))
            steps.noteRevision(pass)
            steps.notePull(pass)
            steps.synced()
            // Landed: who waited on it goes on to its own steps after it, and a sync asked for from here is a pass of its own.
            land(shared, SyncOutcome.SYNCED, pass)
            steps.afterSync(pass)
            afterSync(pass)
            return SyncOutcome.SYNCED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A run past its sign-out says nothing of the backend: the status is the next account's.
            if (!signIn.isCurrent) return SyncOutcome.SKIPPED.also { land(shared, it) }
            steps.failed()
            // Before the failure step, whose relogin runs the sync again as a pass of its own.
            land(shared, SyncOutcome.FAILED)
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

        // No first sync: a pass that queues nothing.
        val NO_FIRST_SYNC = object : Reconciler {
            override suspend fun reconcile(pass: SyncPass): Boolean = false
        }
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

    /** The categories the account had synced here before this pass, as `syncedCategories` records them (LWW D13). */
    fun syncedBefore(): Set<SyncCategory> = emptySet()
    fun recordTaken(pass: SyncPass, took: Set<String>)
    fun noteRevision(pass: SyncPass)
    fun notePull(pass: SyncPass)
    fun synced()
    fun afterSync(pass: SyncPass)
    fun failed()
}
