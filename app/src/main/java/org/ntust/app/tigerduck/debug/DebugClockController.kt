package org.ntust.app.tigerduck.debug

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.widget.WidgetBoundaryScheduler
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.wear.WearScheduleBridge
import org.ntust.app.tigerduck.widget.WidgetUpdater
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebugClockController @Inject constructor(
    private val store: DebugClockPrefsStore,
    private val wearBridge: WearDebugClockBridge,
    private val dataCache: DataCache,
    private val appPreferences: AppPreferences,
    private val liveActivityManager: LiveActivityManager,
    private val assignmentScheduler: AssignmentNotificationScheduler,
    private val widgetBoundaryScheduler: WidgetBoundaryScheduler,
    private val widgetUpdater: WidgetUpdater,
    private val scheduleBridge: WearScheduleBridge,
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
                // Set the override immediately before rescheduleAll() so every
                // scheduler reads the updated clock when computing realTimeFor()
                // trigger values — eliminating the window where AlarmManager
                // entries computed against the old override are still live.
                AppClock.setOverride(override)
                rescheduleAll()
                // The watch's quiet-day list starts at the phone's date when it
                // was sent, so a clock moved to another date needs a new one.
                scheduleBridge.publish()
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
            val assignments = dataCache.loadOutstandingAssignments()
            if (assignments.isNotEmpty()) {
                assignmentScheduler.scheduleAll(
                    assignments,
                    appPreferences.notifyAssignmentOffsets,
                )
            }
        }
        val courses = dataCache.loadCourses()
        widgetBoundaryScheduler.scheduleForToday(courses)
        // The boundary scheduler arms the *next* alarm, but the user has
        // already moved the clock past the previous boundary — so the
        // widgets must repaint right now or they'd still show the time
        // before the override until the next alarm fires.
        widgetUpdater.updateAll()
        // Re-arms the class reminders from their own settings, and the Live
        // Update; see BootReceiver.
        liveActivityManager.refreshAndWait()
    }

    fun currentOverride(): ClockOverride? = AppClock.currentOverride()
}
