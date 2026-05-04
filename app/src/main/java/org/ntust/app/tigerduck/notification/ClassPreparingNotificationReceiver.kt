package org.ntust.app.tigerduck.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
        // Stable id injected by the scheduler's persisted code map. Matches the
        // PendingIntent request code, so two concurrent class alerts never
        // collide the way slotId.hashCode() could.
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notificationId < 0) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(context, nm)

        val timeRange = formatTimeRange(startMs, endMs)
        val detail = listOfNotNull(
            timeRange.takeIf { it.isNotBlank() },
            classroom.takeIf { it.isNotBlank() },
            instructor.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                context.getString(R.string.notification_class_preparing_title, courseName)
            )
            .setContentText(
                detail.ifBlank {
                    context.getString(R.string.notification_class_preparing_content_fallback)
                }
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()

        nm.notify(notificationId, notification)
    }

    private fun ensureChannel(context: Context, nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_class_preparing_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description =
                context.getString(R.string.notification_class_preparing_channel_description)
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
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
