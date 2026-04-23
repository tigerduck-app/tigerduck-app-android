package org.ntust.app.tigerduck.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.data.parseHm
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetBoundaryScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun scheduleForToday(courses: List<Course>) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pi = makePendingIntent()
        alarmManager.cancel(pi)

        val cal = Calendar.getInstance(AppConstants.TAIPEI_TZ)
        val weekday = cal.toWeekday()
        val nowMinute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val nextMinute = nextBoundaryMinuteAfter(courses, weekday, nowMinute) ?: return

        val triggerCal = Calendar.getInstance(AppConstants.TAIPEI_TZ).apply {
            set(Calendar.HOUR_OF_DAY, nextMinute / 60)
            set(Calendar.MINUTE, nextMinute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerCal.timeInMillis, pi)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerCal.timeInMillis, pi)
        }
    }

    private fun makePendingIntent(): PendingIntent {
        val intent = Intent(context, WidgetBoundaryReceiver::class.java)
            .setAction(ACTION_BOUNDARY)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun Calendar.toWeekday(): Int = when (get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6
        else -> 7
    }

    companion object {
        internal const val ACTION_BOUNDARY = "org.ntust.app.tigerduck.WIDGET_BOUNDARY"
        internal const val REQUEST_CODE = 9001

        internal fun nextBoundaryMinuteAfter(
            courses: List<Course>,
            weekday: Int,
            currentMinute: Int,
        ): Int? {
            val boundaries = mutableSetOf<Int>()
            for (course in courses) {
                val periods = course.schedule[weekday] ?: continue
                for (periodId in periods) {
                    val times = AppConstants.PeriodTimes.mapping[periodId] ?: continue
                    parseHm(times.first)?.let { boundaries.add(it) }
                    parseHm(times.second)?.let { boundaries.add(it) }
                }
            }
            return boundaries.filter { it > currentMinute }.minOrNull()
        }
    }
}
