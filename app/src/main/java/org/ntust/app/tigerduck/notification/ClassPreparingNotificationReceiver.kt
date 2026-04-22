package org.ntust.app.tigerduck.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.R
import java.time.Instant
import java.time.ZoneId

class ClassPreparingNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val courseName = intent.getStringExtra(EXTRA_COURSE_NAME) ?: return
        val classroom = intent.getStringExtra(EXTRA_CLASSROOM).orEmpty()
        val instructor = intent.getStringExtra(EXTRA_INSTRUCTOR).orEmpty()
        val startMs = intent.getLongExtra(EXTRA_START_MS, 0L)
        val endMs = intent.getLongExtra(EXTRA_END_MS, 0L)
        val slotId = intent.getStringExtra(EXTRA_SLOT_ID) ?: courseName

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)

        val timeRange = formatTimeRange(startMs, endMs)
        val detail = listOfNotNull(
            timeRange.takeIf { it.isNotBlank() },
            classroom.takeIf { it.isNotBlank() },
            instructor.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("即將上課：$courseName")
            .setContentText(detail.ifBlank { "課程即將開始" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()

        nm.notify(slotId.hashCode() and 0x7FFFFFFF, notification)
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "即將上課提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "在每堂課開始前依設定的時間送出提醒"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun formatTimeRange(startMs: Long, endMs: Long): String {
        if (startMs <= 0 || endMs <= 0) return ""
        val zone: ZoneId = AppConstants.TAIPEI_ZONE
        val s = Instant.ofEpochMilli(startMs).atZone(zone).toLocalTime()
        val e = Instant.ofEpochMilli(endMs).atZone(zone).toLocalTime()
        return "%02d:%02d–%02d:%02d".format(s.hour, s.minute, e.hour, e.minute)
    }

    companion object {
        const val CHANNEL_ID = "class_preparing"
        const val EXTRA_COURSE_NAME = "course_name"
        const val EXTRA_CLASSROOM = "classroom"
        const val EXTRA_INSTRUCTOR = "instructor"
        const val EXTRA_START_MS = "start_ms"
        const val EXTRA_END_MS = "end_ms"
        const val EXTRA_SLOT_ID = "slot_id"
    }
}
