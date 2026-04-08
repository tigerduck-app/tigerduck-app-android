package org.ntust.app.tigerduck.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.ntust.app.tigerduck.data.model.Assignment
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AssignmentNotificationScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val trackerPrefs = context.getSharedPreferences("notification_tracker", Context.MODE_PRIVATE)

    fun scheduleAll(assignments: List<Assignment>) {
        cancelAllTracked()

        val now = System.currentTimeMillis()
        val leadTimeMs = 60 * 60 * 1000L
        val scheduledIds = mutableSetOf<String>()

        for (assignment in assignments) {
            if (assignment.isCompleted) continue
            val triggerTime = assignment.dueDate.time - leadTimeMs
            if (triggerTime <= now) continue

            val intent = Intent(context, AssignmentNotificationReceiver::class.java).apply {
                putExtra(AssignmentNotificationReceiver.EXTRA_TITLE, assignment.title)
                putExtra(AssignmentNotificationReceiver.EXTRA_COURSE_NAME, assignment.courseName)
                putExtra(AssignmentNotificationReceiver.EXTRA_ASSIGNMENT_ID, assignment.assignmentId)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                assignment.assignmentId.hashCode() and 0x7FFFFFFF,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            } catch (_: SecurityException) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
            scheduledIds.add(assignment.assignmentId)
        }

        trackerPrefs.edit().putStringSet("scheduled_ids", scheduledIds).apply()
    }

    fun cancelAllTracked() {
        val ids = trackerPrefs.getStringSet("scheduled_ids", emptySet()) ?: emptySet()
        for (id in ids) {
            val intent = Intent(context, AssignmentNotificationReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                id.hashCode() and 0x7FFFFFFF,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let { alarmManager.cancel(it) }
        }
        trackerPrefs.edit().remove("scheduled_ids").apply()
    }
}
