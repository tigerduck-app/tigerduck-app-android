package org.ntust.app.tigerduck.liveactivity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
) {
    private val resolver = LiveActivityResolver()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var boundaryJob: Job? = null
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
        refreshJob?.cancel()
        refreshJob = scope.launch {
            if (!preferences.isEnabled || !authService.isNtustAuthenticated) {
                notifier.cancel()
                classPreparingScheduler.cancelAllTracked()
                boundaryJob?.cancel()
                return@launch
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
    }

    fun stop() {
        prefsCollectorJob?.cancel()
        prefsCollectorJob = null
        boundaryJob?.cancel()
        refreshJob?.cancel()
        notifier.cancel()
        classPreparingScheduler.cancelAllTracked()
    }

    private fun scheduleBoundaryRefresh(
        snapshot: LiveActivitySnapshot?,
        courses: List<org.ntust.app.tigerduck.data.model.Course>,
        assignments: List<org.ntust.app.tigerduck.data.model.Assignment>,
        now: Date,
    ) {
        boundaryJob?.cancel()
        val candidates = mutableListOf<Long>()
        snapshot?.countdownTarget?.time?.let { candidates += it }

        val classPrep = preferences.classPreparingLeadTimeSec * 1000
        val assignmentLead = preferences.assignmentLeadTimeSec * 1000

        assignments.asSequence()
            .filter { !it.isCompleted && it.dueDate.after(now) }
            .minByOrNull { it.dueDate }?.let { a ->
                candidates += a.dueDate.time - assignmentLead
                candidates += a.dueDate.time
            }

        // Class-related boundaries are approximated: trigger 1 min after "now"
        // so we catch period transitions without needing to duplicate the full
        // timeline here. The resolver is cheap enough to re-run.
        val nowMs = now.time
        val futureCandidates = candidates.filter { it > nowMs }
        val nextBoundary = futureCandidates.minOrNull() ?: (nowMs + 60_000L)
        val delayMs = (nextBoundary - nowMs).coerceAtLeast(30_000L)

        boundaryJob = scope.launch {
            delay(delayMs)
            refresh()
        }
    }
}
