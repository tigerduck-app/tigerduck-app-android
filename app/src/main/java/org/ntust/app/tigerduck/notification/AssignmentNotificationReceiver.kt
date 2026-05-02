package org.ntust.app.tigerduck.notification

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.ntust.app.tigerduck.R

class AssignmentNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val courseName = intent.getStringExtra(EXTRA_COURSE_NAME) ?: ""
        val assignmentId = intent.getStringExtra(EXTRA_ASSIGNMENT_ID) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_assignment_due_title))
            .setContentText("$courseName — $title")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(assignmentId.hashCode() and 0x7FFFFFFF, notification)
    }

    companion object {
        const val CHANNEL_ID = NotificationChannels.ASSIGNMENT_DUE
        const val EXTRA_TITLE = "title"
        const val EXTRA_COURSE_NAME = "course_name"
        const val EXTRA_ASSIGNMENT_ID = "assignment_id"
    }
}
