package org.ntust.app.tigerduck.liveactivity

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.shared.clock.AppClock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules a single AlarmManager exact alarm at the next Live Activity
 * scenario boundary (e.g. class start / end, assignment lead-time crossing).
 * Replaces the in-process `coroutineScope.launch { delay(...); refresh() }`
 * pattern, which silently dies when Android kills the app process — leaving
 * the ongoing notification stuck on the prior scenario (e.g. CLASS_PREPARING)
 * even after the boundary has passed.
 *
 * A second alarm, [scheduleMinuteChangeAt], re-posts a countdown spelled out
 * as text when its minute changes. That one does not wake the phone.
 */
@Singleton
class LiveActivityBoundaryScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun scheduleAt(triggerAtMillis: Long) =
        schedule(REQUEST_CODE, AlarmManager.RTC_WAKEUP, triggerAtMillis)

    /**
     * Arms the next change of the countdown's minute, or clears it when
     * [triggerAtMillis] is null.
     *
     * Not a wake-up alarm, unlike [scheduleAt]: an assignment's countdown can
     * run for hours, and a phone asleep in a pocket shows no island to keep
     * up to date. One that comes due while the phone sleeps fires as soon as
     * it wakes, so the island has the right minute by the time anyone looks.
     * A boundary that matters asleep — a class starting, say — is the
     * other alarm's, and that one still wakes it.
     */
    fun scheduleMinuteChangeAt(triggerAtMillis: Long?) {
        if (triggerAtMillis == null) cancel(MINUTE_REQUEST_CODE)
        else schedule(MINUTE_REQUEST_CODE, AlarmManager.RTC, triggerAtMillis)
    }

    fun cancel() {
        cancel(REQUEST_CODE)
        cancel(MINUTE_REQUEST_CODE)
    }

    private fun schedule(requestCode: Int, type: Int, triggerAtMillis: Long) {
        // Caller works in AppClock time; AlarmManager needs wall-clock time.
        // Without the translation, an active debug-clock override would push
        // the boundary alarm to the wrong real moment (or never), mirroring
        // the pattern used by AssignmentNotificationScheduler.
        val realTrigger = AppClock.realTimeFor(triggerAtMillis)
        val pi = makePendingIntent(requestCode)
        alarmManager.cancel(pi)
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms() ->
                    setInexact(type, realTrigger, pi)
                type == AlarmManager.RTC_WAKEUP ->
                    alarmManager.setExactAndAllowWhileIdle(type, realTrigger, pi)
                else -> alarmManager.setExact(type, realTrigger, pi)
            }
        } catch (_: SecurityException) {
            setInexact(type, realTrigger, pi)
        }
    }

    /**
     * The alarm for a phone without exact alarms, which Android 14 grants no
     * app by default. A plain wake-up alarm waits for Doze's next maintenance
     * window, so a phone left on a desk would show a class as starting soon
     * long after it began; allowed while idle, it comes through, if a little
     * late. The minute alarm has no one to update on an idle phone, so it
     * still waits.
     */
    private fun setInexact(type: Int, realTrigger: Long, pi: PendingIntent) {
        if (type == AlarmManager.RTC_WAKEUP) alarmManager.setAndAllowWhileIdle(type, realTrigger, pi)
        else alarmManager.set(type, realTrigger, pi)
    }

    private fun cancel(requestCode: Int) {
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, LiveActivityBoundaryReceiver::class.java).setAction(ACTION_BOUNDARY),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        pi?.let { alarmManager.cancel(it) }
    }

    private fun makePendingIntent(requestCode: Int): PendingIntent {
        val intent = Intent(context, LiveActivityBoundaryReceiver::class.java)
            .setAction(ACTION_BOUNDARY)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        internal const val ACTION_BOUNDARY = "org.ntust.app.tigerduck.LIVE_ACTIVITY_BOUNDARY"
        internal const val REQUEST_CODE = 9101
        internal const val MINUTE_REQUEST_CODE = 9102
    }
}
