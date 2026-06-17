package org.ntust.app.tigerduck.debug

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.notification.ClassPreparingNotificationScheduler
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.widget.WidgetBoundaryScheduler
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.widget.WidgetUpdater
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebugClockController @Inject constructor(
    private val store: DebugClockPrefsStore,
    private val wearBridge: WearDebugClockBridge,
    private val dataCache: DataCache,
    private val appPreferences: AppPreferences,
    private val liveActivityPreferences: LiveActivityPreferences,
    private val liveActivityManager: LiveActivityManager,
    private val assignmentScheduler: AssignmentNotificationScheduler,
    private val classPreparingScheduler: ClassPreparingNotificationScheduler,
    private val widgetBoundaryScheduler: WidgetBoundaryScheduler,
    private val widgetUpdater: WidgetUpdater,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    fun bootstrap() {
        // Guard so a stale override persisted by a prior debug install can't
        // bleed into a release build — the user has no UI to clear it there.
        if (!BuildConfig.DEBUG) return
        val persisted = store.load()
        if (persisted != null) {
            AppClock.setOverride(persisted)
        }
    }

    fun setOverride(override: ClockOverride?) {
        store.save(override)
        scope.launch {
            withContext(Dispatchers.IO) {
                wearBridge.push(override)
                AppClock.setOverride(override)
                rescheduleAll()
            }
        }
    }

    /**
     * Mirror of [BootReceiver.onReceive]'s reschedule sequence — same data
     * fetches, same scheduler calls. Re-registers all AlarmManager-backed
     * services with the new clock so their RTC_WAKEUP triggers (which were
     * computed against the previous clock) get replaced.
     */
    private suspend fun rescheduleAll() {
        if (appPreferences.notifyAssignments) {
            val assignments = dataCache.loadAssignments()
            if (assignments.isNotEmpty()) {
                val ignored = dataCache.loadIgnoredAssignments()
                val marked = dataCache.loadMarkedCompletedAssignments()
                assignmentScheduler.scheduleAll(
                    assignments,
                    ignored + marked,
                    appPreferences.notifyAssignmentOffsets,
                )
            }
        }
        val courses = dataCache.loadCourses()
        if (liveActivityPreferences.isEnabled && liveActivityPreferences.showClassPreparing &&
            courses.isNotEmpty()
        ) {
            val skipped = dataCache.loadSkippedDates()
            classPreparingScheduler.scheduleAll(
                courses = courses,
                skippedDates = skipped,
                leadTimeSec = liveActivityPreferences.classPreparingLeadTimeSec,
            )
        }
        widgetBoundaryScheduler.scheduleForToday(courses)
        // The boundary scheduler arms the *next* alarm, but the user has
        // already moved the clock past the previous boundary — so the
        // widgets must repaint right now or they'd still show the time
        // before the override until the next alarm fires.
        widgetUpdater.updateAll()
        if (liveActivityPreferences.isEnabled) {
            liveActivityManager.refreshAndWait()
        }
    }

    fun currentOverride(): ClockOverride? = AppClock.currentOverride()
}
