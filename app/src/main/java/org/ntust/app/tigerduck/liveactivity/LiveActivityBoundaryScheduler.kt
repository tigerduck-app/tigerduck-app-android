package org.ntust.app.tigerduck.liveactivity

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules a single AlarmManager exact alarm at the next Live Activity
 * scenario boundary (e.g. class start / end, assignment lead-time crossing).
 * Replaces the in-process `coroutineScope.launch { delay(...); refresh() }`
 * pattern, which silently dies when Android kills the app process — leaving
 * the ongoing notification stuck on the prior scenario (e.g. CLASS_PREPARING)
 * even after the boundary has passed.
 */
@Singleton
class LiveActivityBoundaryScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun scheduleAt(triggerAtMillis: Long) {
        val pi = makePendingIntent()
        alarmManager.cancel(pi)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } catch (_: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    fun cancel() {
        val pi = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, LiveActivityBoundaryReceiver::class.java).setAction(ACTION_BOUNDARY),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        pi?.let { alarmManager.cancel(it) }
    }

    private fun makePendingIntent(): PendingIntent {
        val intent = Intent(context, LiveActivityBoundaryReceiver::class.java)
            .setAction(ACTION_BOUNDARY)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        internal const val ACTION_BOUNDARY = "org.ntust.app.tigerduck.LIVE_ACTIVITY_BOUNDARY"
        internal const val REQUEST_CODE = 9101
    }
}
