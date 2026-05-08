package org.ntust.app.tigerduck.debug

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.notification.ClassPreparingNotificationScheduler
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.widget.WidgetBoundaryScheduler
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
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun bootstrap() {
        val persisted = store.load()
        if (persisted != null) {
            AppClock.setOverride(persisted)
        }
    }

    fun setOverride(override: ClockOverride?) {
        AppClock.setOverride(override)
        store.save(override)
        scope.launch {
            wearBridge.push(override)
            rescheduleAll()
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
                assignmentScheduler.scheduleAll(assignments)
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
        if (liveActivityPreferences.isEnabled) {
            liveActivityManager.refreshAndWait()
        }
    }

    fun currentOverride(): ClockOverride? = AppClock.currentOverride()
}
