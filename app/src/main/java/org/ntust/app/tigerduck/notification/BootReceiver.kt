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
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
import org.ntust.app.tigerduck.widget.WidgetBoundaryScheduler
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: AssignmentNotificationScheduler
    @Inject lateinit var classPreparingScheduler: ClassPreparingNotificationScheduler
    @Inject lateinit var liveActivityPreferences: LiveActivityPreferences
    @Inject lateinit var dataCache: DataCache
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var widgetBoundaryScheduler: WidgetBoundaryScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                withTimeout(10_000) {
                    if (appPreferences.notifyAssignments) {
                        val assignments = dataCache.loadAssignments()
                        if (assignments.isNotEmpty()) {
                            scheduler.scheduleAll(assignments)
                        }
                    }
                    val courses = dataCache.loadCourses()
                    if (liveActivityPreferences.isEnabled && liveActivityPreferences.showClassPreparing &&
                        courses.isNotEmpty()) {
                        val skipped = dataCache.loadSkippedDates()
                        classPreparingScheduler.scheduleAll(
                            courses = courses,
                            skippedDates = skipped,
                            leadTimeSec = liveActivityPreferences.classPreparingLeadTimeSec,
                        )
                    }
                    // AlarmManager alarms don't survive reboot; without this
                    // the widget's class-boundary refresh stays dead until the
                    // user opens the app or BackgroundSyncWorker fires.
                    widgetBoundaryScheduler.scheduleForToday(courses)
                }
            } catch (e: Exception) {
                android.util.Log.w("BootReceiver", "Boot rescheduling failed", e)
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }
}
