package org.ntust.app.tigerduck.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.tigerduck.app.R

class AssignmentNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val courseName = intent.getStringExtra(EXTRA_COURSE_NAME) ?: ""
        val assignmentId = intent.getStringExtra(EXTRA_ASSIGNMENT_ID) ?: return

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create channel if needed
        val channel = NotificationChannel(
            CHANNEL_ID,
            "作業到期提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "提醒你作業即將到期"
        }
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle("作業即將到期")
            .setContentText("$courseName — $title")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(assignmentId.hashCode(), notification)
    }

    companion object {
        const val CHANNEL_ID = "assignment_due"
        const val EXTRA_TITLE = "title"
        const val EXTRA_COURSE_NAME = "course_name"
        const val EXTRA_ASSIGNMENT_ID = "assignment_id"
    }
}
