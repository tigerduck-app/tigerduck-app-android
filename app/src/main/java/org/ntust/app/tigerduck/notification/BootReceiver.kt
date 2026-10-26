package org.ntust.app.tigerduck.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.util.logSummary
import org.ntust.app.tigerduck.widget.WidgetBoundaryScheduler
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var scheduler: AssignmentNotificationScheduler

    @Inject
    lateinit var dataCache: DataCache

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var widgetBoundaryScheduler: WidgetBoundaryScheduler

    @Inject
    lateinit var liveActivityManager: LiveActivityManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                withTimeout(10_000) {
                    if (appPreferences.notifyAssignments) {
                        val assignments = dataCache.loadOutstandingAssignments()
                        if (assignments.isNotEmpty()) {
                            scheduler.scheduleAll(
                                assignments,
                                appPreferences.notifyAssignmentOffsets,
                            )
                        }
                    }
                    val courses = dataCache.loadCourses()
                    // AlarmManager alarms don't survive reboot; without this
                    // the widget's class-boundary refresh stays dead until the
                    // user opens the app or BackgroundSyncWorker fires.
                    widgetBoundaryScheduler.scheduleForToday(courses)

                    // Same story for the class reminders and the Live
                    // Activity ongoing notification: the refresh re-arms the
                    // reminders from their own settings, and the Live
                    // Update's boundary alarm so the CLASS_PREPARING →
                    // IN_CLASS transition still fires when the device boots
                    // before the user reopens the app. Unconditional, as the
                    // reminders no longer depend on the Live Update.
                    liveActivityManager.refreshAndWait()
                }
            } catch (e: Exception) {
                android.util.Log.w("BootReceiver", "Boot rescheduling failed: ${e.logSummary()}")
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }
}
