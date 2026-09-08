package org.ntust.app.tigerduck.liveactivity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.notification.ClassPreparingNotificationScheduler
import org.ntust.app.tigerduck.shared.clock.AppClock
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
    private val academicCalendar: org.ntust.app.tigerduck.academic.AcademicCalendarStore,
    private val boundaryScheduler: LiveActivityBoundaryScheduler,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {
    private val resolver = LiveActivityResolver()
    private val managerJob = SupervisorJob(appScope.coroutineContext[Job])
    private val scope = appScope + managerJob
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
        if (!managerJob.isActive) return
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
        managerJob.cancel()
    }

    private suspend fun refreshInternal() {
        if (!preferences.isEnabled || !authService.isNtustAuthenticated) {
            notifier.cancel()
            classPreparingScheduler.cancelAllTracked()
            boundaryScheduler.cancel()
            return
        }
        val now = Date(AppClock.nowMillis())
        // Classes do not meet on a school holiday, so neither the chip nor
        // the class-preparing alarm should surface one. Read once and used
        // for both so a holiday starting between the two calls cannot leave
        // them disagreeing.
        val calendar = academicCalendar.current()
        val optedIn = academicCalendar.optedInHolidayIds
        val onHoliday = calendar.suppressesClasses(
            AppClock.localDateTime().toLocalDate(), optedIn
        )
        val courses = if (onHoliday) emptyList() else dataCache.loadCourses()
        val assignments = dataCache.loadAssignments()
        // 翹課 parked — see DataCache's skipped-dates section. Not read, so
        // pre-v2.0.0 marks can't suppress a class the user can no longer unskip.
        val skipped = emptyMap<String, List<String>>()
        // val skipped = dataCache.loadSkippedDates()

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
            // Passes the full course list, not the holiday-emptied one: the
            // scheduler reaches ten days ahead and does its own per-day
            // check, so handing it today's emptiness would cancel next
            // week's reminders too.
            classPreparingScheduler.scheduleAll(
                courses = dataCache.loadCourses(),
                skippedDates = skipped,
                leadTimeSec = preferences.classPreparingLeadTimeSec,
                calendar = calendar,
                optedInHolidayIds = optedIn,
            )
        } else {
            classPreparingScheduler.cancelAllTracked()
        }

        scheduleBoundaryRefresh(snapshot, courses, assignments, skipped, now)
    }

    private fun scheduleBoundaryRefresh(
        snapshot: LiveActivitySnapshot?,
        courses: List<org.ntust.app.tigerduck.shared.Course>,
        assignments: List<org.ntust.app.tigerduck.data.model.Assignment>,
        skippedDates: Map<String, List<String>>,
        now: Date,
    ) {
        val candidates = mutableListOf<Long>()
        snapshot?.countdownTarget?.time?.let { candidates += it }

        // A progress bar is not self-animating — it holds whatever fraction
        // the notifier last posted — so while one is on screen this alarm
        // doubles as its tick. Only a snapshot that actually has progress
        // asks for it, and the min() below still collapses to the real
        // boundary once the class has less than a tick left to run.
        if (snapshot?.progress != null) candidates += now.time + PROGRESS_TICK_MS

        val classPrepLead = preferences.classPreparingLeadTimeSec * 1000
        val assignmentLead = preferences.assignmentLeadTimeSec * 1000

        // Cover the CLASS_PREPARING → IN_CLASS → (idle) progression for the
        // upcoming class even if it isn't currently the snapshot scenario,
        // so the boundary fires once a class enters the lead-time window.
        nextClassBoundaries(courses, skippedDates, now, classPrepLead).forEach { candidates += it }

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
        courses: List<org.ntust.app.tigerduck.shared.Course>,
        skippedDates: Map<String, List<String>>,
        now: Date,
        classPrepLeadMs: Long,
    ): List<Long> {
        val slots = resolver.todaySlotsAfter(courses, now, skippedDates)
        val first = slots.firstOrNull() ?: return emptyList()
        return listOfNotNull(
            (first.start.time - classPrepLeadMs).takeIf { it > now.time },
            first.start.time.takeIf { it > now.time },
            first.end.time.takeIf { it > now.time },
        )
    }

    private companion object {
        /**
         * How often to redraw a live progress bar. Two minutes puts a 50-minute
         * period in 4% steps, which reads as movement without spending an exact
         * alarm a minute on a bar nobody is watching that closely — the exact
         * remaining time is already on screen as a free system-drawn
         * chronometer. Doze may stretch this; the bar simply lags a little.
         */
        const val PROGRESS_TICK_MS = 2 * 60_000L
    }
}
