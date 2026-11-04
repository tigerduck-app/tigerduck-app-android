package org.ntust.app.tigerduck.ui.screen.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.push.NotificationSettingsSync
import org.ntust.app.tigerduck.ui.AppState
import javax.inject.Inject

/**
 * Assignment reminders turned on or off: the one write their settings page and Home's ⋮ both make,
 * so the two always read the same. It stores the switch, queues it for the user's other devices,
 * and arms or cancels the reminders at once.
 */
class AssignmentReminderSwitch @Inject constructor(
    private val appState: AppState,
    private val scheduler: AssignmentNotificationScheduler,
    private val dataCache: DataCache,
    private val notificationSettingsSync: NotificationSettingsSync,
) {
    val isOn: Boolean get() = appState.notifyAssignments

    /** [scope] is the screen's, which arms the reminders. */
    fun setEnabled(value: Boolean, scope: CoroutineScope) {
        appState.notifyAssignments = value
        notificationSettingsSync.markAssignmentUnconfirmedAndPush()
        if (value) reschedule(scope) else scheduler.cancelAllTracked()
    }

    /**
     * Rebuilds every pending reminder against the on-disk assignment cache, whenever the switch or
     * an offset changes, so the change takes effect at once instead of at the next sync. Reads the
     * stored offsets, not AppState's copy of them, for the reason AssignmentReminderSettingsState
     * gives.
     */
    fun reschedule(scope: CoroutineScope) {
        scope.launch {
            scheduler.scheduleAll(dataCache.loadOutstandingAssignments(), appState.prefs.notifyAssignmentOffsets)
        }
    }
}

/**
 * Live Updates turned on or off: the one write their settings page and Home's ⋮ both make. The
 * switch is this device's own, outside the synced settings, and the live notification follows it
 * at once.
 */
class LiveUpdatesSwitch @Inject constructor(
    private val prefs: LiveActivityPreferences,
    private val manager: LiveActivityManager,
) {
    val isOn: Boolean get() = prefs.isEnabled

    fun setEnabled(value: Boolean) {
        prefs.isEnabled = value
        manager.refresh()
    }
}
