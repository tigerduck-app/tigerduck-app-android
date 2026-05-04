package org.ntust.app.tigerduck.liveactivity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.notification.ClassPreparingNotificationScheduler
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the Android Live Update experience — analogous to the iOS
 * `LiveActivityCoordinator`. Owns no state itself; loads cached courses /
 * assignments on every refresh, asks the resolver what should be shown, and
 * forwards the result to the notifier.
 */
@Singleton
class LiveActivityManager @Inject constructor(
    private val preferences: LiveActivityPreferences,
    private val notifier: LiveActivityNotifier,
    private val dataCache: DataCache,
    private val authService: AuthService,
    private val appPrefs: AppPreferences,
    private val classPreparingScheduler: ClassPreparingNotificationScheduler,
    private val boundaryScheduler: LiveActivityBoundaryScheduler,
) {
    private val resolver = LiveActivityResolver()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var refreshJob: Job? = null
    // Hold a reference so `stop()` can halt preference-driven refreshes too;
    // otherwise a `preferences.changeEvent` arriving after `stop()` would
    // silently resurrect the notifier machinery.
    private var prefsCollectorJob: Job? = null

    init {
        prefsCollectorJob = scope.launch {
            preferences.changeEvent.collect { refresh() }
        }
    }

    /** Recompute the scenario and push the result to the notifier. */
    fun refresh() {
        if (!scope.isActive) return
        refreshJob?.cancel()
        refreshJob = scope.launch {
            refreshInternal()
        }
    }

    /**
     * Refresh synchronously in the caller coroutine.
     * Used by background workers so scheduling completes before doWork() returns.
     */
    suspend fun refreshAndWait() {
        refreshJob?.cancelAndJoin()
        refreshInternal()
    }

    fun stop() {
        prefsCollectorJob?.cancel()
        prefsCollectorJob = null
        boundaryScheduler.cancel()
        refreshJob?.cancel()
        notifier.cancel()
        classPreparingScheduler.cancelAllTracked()
        // Cancel the scope itself so future launch() calls are no-ops; the
        // SupervisorJob would otherwise outlive any DI/test teardown and
        // resurrect work via prefs-driven refresh.
        scope.cancel()
    }

    private suspend fun refreshInternal() {
        if (!preferences.isEnabled || !authService.isNtustAuthenticated) {
            notifier.cancel()
            classPreparingScheduler.cancelAllTracked()
            boundaryScheduler.cancel()
            return
        }
        val now = Date()
        val courses = dataCache.loadCourses()
        val assignments = dataCache.loadAssignments()
        val skipped = dataCache.loadSkippedDates()

        val snapshot = resolver.resolve(
            courses = courses,
            assignments = assignments,
            skippedDates = skipped,
            preferences = preferences,
            accentHex = appPrefs.accentColorHex,
            now = now,
        )
        notifier.apply(snapshot)

        // Keep the class-preparing alarm set in sync with the current
        // course list + lead-time preference so reminders fire even when
        // the app is fully closed.
        if (preferences.showClassPreparing) {
            classPreparingScheduler.scheduleAll(
                courses = courses,
                skippedDates = skipped,
                leadTimeSec = preferences.classPreparingLeadTimeSec,
            )
        } else {
            classPreparingScheduler.cancelAllTracked()
        }

        scheduleBoundaryRefresh(snapshot, courses, assignments, now)
    }

    private fun scheduleBoundaryRefresh(
        snapshot: LiveActivitySnapshot?,
        courses: List<org.ntust.app.tigerduck.data.model.Course>,
        assignments: List<org.ntust.app.tigerduck.data.model.Assignment>,
        now: Date,
    ) {
        val candidates = mutableListOf<Long>()
        snapshot?.countdownTarget?.time?.let { candidates += it }

        val classPrepLead = preferences.classPreparingLeadTimeSec * 1000
        val assignmentLead = preferences.assignmentLeadTimeSec * 1000

        // Cover the CLASS_PREPARING → IN_CLASS → (idle) progression for the
        // upcoming class even if it isn't currently the snapshot scenario,
        // so the boundary fires once a class enters the lead-time window.
        nextClassBoundaries(courses, now, classPrepLead).forEach { candidates += it }

        assignments.asSequence()
            .filter { !it.isCompleted && it.dueDate.after(now) }
            .minByOrNull { it.dueDate }?.let { a ->
                candidates += a.dueDate.time - assignmentLead
                candidates += a.dueDate.time
            }

        val nowMs = now.time
        // Pad by 1s to make sure we land *after* the boundary tick, not on it,
        // so the resolver sees the new state instead of the prior one.
        val futureCandidates = candidates.filter { it > nowMs }.map { it + 1_000L }
        // Floor at 30s so a near-instant boundary doesn't burn battery, and
        // ceil at 30 min so a long-idle stretch still gets a watchdog refresh.
        val nextBoundary = futureCandidates.minOrNull() ?: (nowMs + 30 * 60_000L)
        val triggerAt = nextBoundary.coerceAtLeast(nowMs + 30_000L)

        boundaryScheduler.scheduleAt(triggerAt)
    }

    private fun nextClassBoundaries(
        courses: List<org.ntust.app.tigerduck.data.model.Course>,
        now: Date,
        classPrepLeadMs: Long,
    ): List<Long> {
        val slots = resolver.todaySlotsAfter(courses, now)
        val first = slots.firstOrNull() ?: return emptyList()
        return listOfNotNull(
            (first.start.time - classPrepLeadMs).takeIf { it > now.time },
            first.start.time.takeIf { it > now.time },
            first.end.time.takeIf { it > now.time },
        )
    }
}
