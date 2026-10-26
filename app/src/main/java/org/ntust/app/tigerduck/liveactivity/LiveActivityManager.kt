package org.ntust.app.tigerduck.liveactivity

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.notification.ClassPreparingNotificationScheduler
import org.ntust.app.tigerduck.notification.ClassReminderAlarms
import org.ntust.app.tigerduck.notification.ClassReminderSettings
import org.ntust.app.tigerduck.notification.ClassReminders
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
class LiveActivityManager internal constructor(
    private val preferences: LiveActivityPreferences,
    private val notifier: LiveActivityNotifier,
    private val dataCache: DataCache,
    private val isSignedIn: () -> Boolean,
    private val appPrefs: AppPreferences,
    private val classPreparingScheduler: ClassReminderAlarms,
    private val academicCalendar: org.ntust.app.tigerduck.academic.AcademicCalendarStore,
    private val boundaryScheduler: LiveActivityBoundaryScheduler,
    private val appScope: CoroutineScope,
) {
    // The real entry point, which Hilt calls. The primary constructor takes
    // the sign-in state and the class reminder alarms as plain types, so
    // LiveActivityManagerTest and LiveActivityManagerRestartTest can build the
    // real manager around a fake scheduler without AuthService's dependency
    // graph. Same split as LiveActivityPreferences and NotificationSettingsSync.
    @Inject constructor(
        preferences: LiveActivityPreferences,
        notifier: LiveActivityNotifier,
        dataCache: DataCache,
        authService: AuthService,
        appPrefs: AppPreferences,
        classPreparingScheduler: ClassPreparingNotificationScheduler,
        academicCalendar: org.ntust.app.tigerduck.academic.AcademicCalendarStore,
        boundaryScheduler: LiveActivityBoundaryScheduler,
        @ApplicationScope appScope: CoroutineScope,
    ) : this(
        preferences = preferences,
        notifier = notifier,
        dataCache = dataCache,
        // authState, not a session-liveness check; see refreshLocked.
        isSignedIn = { authService.authState.value },
        appPrefs = appPrefs,
        classPreparingScheduler = classPreparingScheduler,
        academicCalendar = academicCalendar,
        boundaryScheduler = boundaryScheduler,
        appScope = appScope,
    )

    private val resolver = LiveActivityResolver()
    private val scope = appScope + SupervisorJob(appScope.coroutineContext[Job])
    private var refreshJob: Job? = null

    // One refresh at a time. The two boundary alarms can land together — a
    // minute change held while the phone slept fires as it wakes for the
    // other — and two posts racing would both take the scenario for new and
    // chime twice.
    private val refreshLock = Mutex()

    init {
        // Kept for the life of the process, signed in or not: a refresh while
        // nobody is signed in only takes everything down.
        scope.launch {
            // And on every write of the ignored, marked-done and ignored-course
            // sets: the resolver only sees what is still outstanding. And on a
            // change to the class reminder's settings, which this arms.
            merge(
                preferences.changeEvent,
                dataCache.assignmentMarksVersion.drop(1),
                appPrefs.classReminderSettingsChanged,
            ).collect { refresh() }
        }
    }

    /**
     * Recompute the scenario and push the result to the notifier.
     *
     * [quiet] is for a redraw nobody should hear, such as a change of
     * language; see [LiveActivityNotifier.apply].
     */
    fun refresh(quiet: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            refreshInternal(quiet)
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

    /**
     * Takes the Live Update, the class reminders and their alarms down at
     * sign-out. The manager itself stays up: it used to stop for the rest of
     * the process, so after signing in again only a restart brought the Live
     * Update back. The refresh queued here waits for any still running, finds
     * nobody signed in, and takes down whatever that one put up.
     */
    fun stop() {
        boundaryScheduler.cancel()
        notifier.cancel()
        classPreparingScheduler.cancelAllTracked()
        refresh()
    }

    private suspend fun refreshInternal(quiet: Boolean = false) =
        refreshLock.withLock { refreshLocked(quiet) }

    private suspend fun refreshLocked(quiet: Boolean) {
        // authState, not a session-liveness check: everything below reads local
        // JSON and the academic calendar, so what matters is whether a user
        // is signed in at all — not whether an SSO cookie happens to be warm.
        // The cookie jar is in-memory, so gating on it stopped the Live Update
        // after every reboot or background kill until the user signed in again.
        val signedIn = isSignedIn()
        // Classes do not meet on a school holiday, nor before 開學 — 選課 fills
        // the timetable weeks ahead of the term, so having courses is not
        // evidence that classes have started. Neither the chip nor the
        // class-preparing alarm should surface one on such a day. Read once
        // and used for both so a flip between the two calls cannot leave them
        // disagreeing.
        val calendar = academicCalendar.current()
        val optedIn = academicCalendar.optedInHolidayIds
        // 翹課 parked — see DataCache's skipped-dates section. Not read, so
        // pre-v2.0.1 marks can't suppress a class the user can no longer unskip.
        val skipped = emptyMap<String, List<String>>()
        // val skipped = dataCache.loadSkippedDates()

        // Before the Live Update's own gate: class reminders have a switch of
        // their own and no longer stop with the Live Update.
        val remindersWake = armClassReminders(signedIn, skipped, calendar, optedIn)
        // And whatever the Live Update shows: with a due-soon assignment
        // shown first, the switch to "in class" that cleared these may never
        // come. The boundary alarm at class start brings a refresh here,
        // with the Live Update off too (see below).
        notifier.clearStartedClassBanners(AppClock.nowMillis())

        if (!preferences.isEnabled || !signedIn) {
            // The other silent way to get no Live Update. Signing out stops it
            // just as completely as the feature toggle, and neither says so
            // anywhere.
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "no live update: enabled=${preferences.isEnabled} signedIn=$signedIn",
                )
            }
            notifier.cancel()
            // With no Live Update to follow, the boundary alarm is kept only
            // for the class reminders: to clear a reminder's banner when its
            // class begins, as the banner's own timeout is a hint that can
            // come late, and to arm them again when a term begins or ends.
            boundaryScheduler.cancel()
            reminderWakeAt(AppClock.nowMillis(), remindersWake)?.let(boundaryScheduler::scheduleAt)
            return
        }
        val now = Date(AppClock.nowMillis())
        val today = AppClock.localDateTime().toLocalDate()
        val quietToday = calendar.isQuietDay(today, optedIn)
        // Emptying the list is what gates the resolver, which is pure and
        // takes no calendar of its own — and scheduleBoundaryRefresh reads the
        // same list, so no boundary alarm is armed for a quiet day either.
        val courses = if (quietToday) emptyList() else dataCache.loadCourses()
        val assignments = dataCache.loadOutstandingAssignments()

        val snapshot = resolver.resolve(
            courses = courses,
            assignments = assignments,
            skippedDates = skipped,
            preferences = preferences,
            accentHex = appPrefs.accentColorHex,
            now = now,
        )
        // Distinguishes "the resolver looked and found nothing" from the
        // bail-outs above, which is the difference between a wrong timetable
        // and a wrong clock when a simulated class fails to show.
        if (BuildConfig.DEBUG && snapshot == null) {
            Log.d(
                TAG,
                "no live update at $now: courses=${courses.size} " +
                    "assignments=${assignments.size} quietToday=$quietToday",
            )
        }
        val spelledOut = notifier.apply(snapshot, quiet)

        scheduleBoundaryRefresh(snapshot, courses, assignments, skipped, now, spelledOut, calendar.termFlipMillis())
    }

    /**
     * Keeps the class reminder alarms in step with the course list and the
     * class reminder's own switch and lead time ([ClassReminders]), so a
     * reminder fires even while the app is closed. Signing out cancels them.
     *
     * Returns when they next need a refresh, or null when none are armed:
     * the start of the next class they cover (see
     * [ClassReminderAlarms.scheduleAll]), or the next 開學 or 結業 if that
     * comes first.
     */
    private suspend fun armClassReminders(
        signedIn: Boolean,
        skipped: Map<String, List<String>>,
        calendar: org.ntust.app.tigerduck.academic.AcademicCalendar,
        optedIn: Set<Int>,
    ): Long? {
        val settings = ClassReminderSettings(appPrefs.classReminderEnabled, appPrefs.classReminderLeadMinutes)
        val leadSec = ClassReminders.leadSecondsToArm(settings, signedIn)
        if (leadSec == null) {
            classPreparingScheduler.cancelAllTracked()
            return null
        }
        // Every term the days ahead fall in, not just the one loadCourses()
        // shows: in the last days of a break that is still the old term, and
        // its timetable is not the new one's. Full course lists, never ones
        // emptied for a quiet day: the scheduler reaches ten days ahead and
        // does its own per-day check, so handing it today's emptiness would
        // cancel next week's reminders too.
        val today = AppClock.localDateTime().toLocalDate()
        val nextClassStart = classPreparingScheduler.scheduleAll(
            coursesByTerm = ClassPreparingNotificationScheduler.termsAhead(calendar, today)
                .associateWith { dataCache.loadCourses(it) },
            skippedDates = skipped,
            leadTimeSec = leadSec,
            calendar = calendar,
            optedInHolidayIds = optedIn,
        )
        // Nothing else announces a term flip, and the days past it are armed
        // from whatever that term had cached by now; see
        // AcademicCalendar.termFlipMillis.
        val nextTermFlip = calendar.termFlipMillis().filter { it > AppClock.nowMillis() }.minOrNull()
        return listOfNotNull(nextClassStart, nextTermFlip).minOrNull()
    }

    /**
     * [spelledOut] is [LiveActivityNotifier.apply]'s answer: a countdown is
     * showing as text, which nothing redraws until it is posted again.
     */
    private fun scheduleBoundaryRefresh(
        snapshot: LiveActivitySnapshot?,
        courses: List<org.ntust.app.tigerduck.shared.Course>,
        assignments: List<org.ntust.app.tigerduck.data.model.Assignment>,
        skippedDates: Map<String, List<String>>,
        now: Date,
        spelledOut: Boolean,
        termFlips: List<Long>,
    ) {
        val candidates = mutableListOf<Long>()
        snapshot?.countdownTarget?.time?.let { candidates += it }

        // A progress bar is not self-animating — it holds whatever fraction
        // the notifier last posted — so while one is on screen this alarm
        // doubles as its tick. Only a snapshot that actually has progress
        // asks for it, and the min() below still collapses to the real
        // boundary once the class has less than a tick left to run.
        if (snapshot?.progress != null) candidates += now.time + PROGRESS_TICK_MS

        // Some islands show the countdown as text they never redraw — see
        // LiveActivityNotifier.showsStaticCountdown — so there each change of
        // the displayed minute needs a post of its own. Other chips tick alone.
        // A frozen debug clock never reaches the next minute: every post
        // would arm the same few seconds ahead again, in a loop.
        val target = snapshot?.countdownTarget?.time
        val clockRuns = AppClock.currentOverride()?.frozen != true
        boundaryScheduler.scheduleMinuteChangeAt(
            target?.takeIf { spelledOut && clockRuns && it > now.time }
                ?.let { minuteChangeTriggerAt(it, now.time) }
        )

        val classPrepLead = preferences.classPreparingLeadTimeSec * 1000
        val assignmentLead = preferences.assignmentLeadTimeSec * 1000

        // Cover the CLASS_PREPARING → IN_CLASS → (idle) progression for the
        // upcoming class even if it isn't currently the snapshot scenario,
        // so the boundary fires once a class enters the lead-time window.
        nextClassBoundaries(courses, skippedDates, now, classPrepLead).forEach { candidates += it }

        assignments.asSequence()
            .filter { !it.isCompleted && it.studentDueDate.after(now) }
            .minByOrNull { it.studentDueDate }?.let { a ->
                candidates += a.studentDueDate.time - assignmentLead
                candidates += a.studentDueDate.time
            }

        // And 開學 and 結業, which change what today shows and which term the
        // class reminders are armed from, with nothing to announce them.
        boundaryScheduler.scheduleAt(boundaryTriggerAt(now.time, candidates, termFlips))
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
        private const val TAG = "LiveActivity"

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

/**
 * When the boundary alarm fires with no Live Update to follow: just after the
 * class reminders next need a refresh ([remindersWakeMs], see
 * armClassReminders), padded and floored as [boundaryTriggerAt] does, or null
 * for none.
 */
internal fun reminderWakeAt(nowMs: Long, remindersWakeMs: Long?): Long? =
    remindersWakeMs?.takeIf { it > nowMs }?.let { (it + 1_000L).coerceAtLeast(nowMs + 30_000L) }

/**
 * When the boundary alarm fires: the earliest of [candidates] still ahead of
 * [nowMs], or the next of [termFlips] if that comes first.
 */
internal fun boundaryTriggerAt(nowMs: Long, candidates: List<Long>, termFlips: List<Long> = emptyList()): Long {
    // Pad by 1s to make sure we land *after* the boundary tick, not on it,
    // so the resolver sees the new state instead of the prior one.
    val futureCandidates = candidates.filter { it > nowMs }.map { it + 1_000L }
    // Floor at 30s so a near-instant boundary doesn't burn battery, and
    // ceil at 30 min so a long-idle stretch still gets a watchdog refresh.
    val nextBoundary = futureCandidates.minOrNull() ?: (nowMs + 30 * 60_000L)
    // A term flip only ever brings the alarm forward, as it does the
    // widgets' (WidgetBoundaryScheduler.chooseTriggerMillis): taken as a
    // candidate, one months away would stand in for the watchdog.
    val nextFlip = termFlips.filter { it > nowMs }.minOrNull()?.plus(1_000L) ?: nextBoundary
    return minOf(nextBoundary, nextFlip).coerceAtLeast(nowMs + 30_000L)
}

/**
 * When the minute alarm fires for a countdown to [targetMs]: just after its
 * minute next changes, padded like the boundaries above. No floor: a post
 * landing just before the change would otherwise leave the island a minute
 * behind for up to half a minute, and a running clock brings the change
 * round only once a minute.
 */
internal fun minuteChangeTriggerAt(targetMs: Long, nowMs: Long): Long =
    StaticCountdown.nextChangeAt(targetMs, nowMs) + 1_000L
