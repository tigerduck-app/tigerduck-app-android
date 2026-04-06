package org.ntust.app.tigerduck.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.ntust.app.tigerduck.data.cache.DataCache

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val dataCache = DataCache(context)
        val scheduler = AssignmentNotificationScheduler(context)
        val assignments = dataCache.loadAssignments()
        if (assignments.isNotEmpty()) {
            scheduler.scheduleAll(assignments)
        }
    }
}
