package org.ntust.app.tigerduck.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import org.ntust.app.tigerduck.data.cache.DataCache
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: AssignmentNotificationScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val dataCache = DataCache(context)
        val assignments = dataCache.loadAssignments()
        if (assignments.isNotEmpty()) {
            scheduler.scheduleAll(assignments)
        }
    }
}
