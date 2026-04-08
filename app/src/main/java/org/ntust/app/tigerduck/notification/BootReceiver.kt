package org.ntust.app.tigerduck.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.ntust.app.tigerduck.data.cache.DataCache
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: AssignmentNotificationScheduler
    @Inject lateinit var dataCache: DataCache

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            withTimeout(10_000) {
                try {
                    val assignments = dataCache.loadAssignments()
                    if (assignments.isNotEmpty()) {
                        scheduler.scheduleAll(assignments)
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
