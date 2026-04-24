package org.ntust.app.tigerduck.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.data.collapseContiguousPeriods
import org.ntust.app.tigerduck.data.model.Course
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules "即將上課" notifications for upcoming class slots — analogous to
 * [AssignmentNotificationScheduler], but for class sessions. Fires an exact
 * alarm at `(classStart - classPreparingLeadTimeSec)` so the notification
 * arrives even while the app is fully closed.
 */
@Singleton
class ClassPreparingNotificationScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val trackerPrefs = context.getSharedPreferences(TRACKER_PREFS, Context.MODE_PRIVATE)

    fun scheduleAll(
        courses: List<Course>,
        skippedDates: Map<String, List<String>>,
        leadTimeSec: Long,
    ) {
        // Load the persisted slotId→requestCode map BEFORE cancelling so cancel
        // looks up the same code each alarm was originally registered with.
        // Previously we hashed slotId into the requestCode, which could collide
        // across two different slots and silently overwrite the earlier alarm.
        val codeMap = loadCodeMap()
        cancelAllTracked(codeMap)
        if (leadTimeSec <= 0 || courses.isEmpty()) return

        val now = System.currentTimeMillis()
        val scheduled = mutableSetOf<String>()

        for (slot in upcomingSlots(courses, skippedDates, daysAhead = DAYS_AHEAD)) {
            val triggerTime = slot.startMs - leadTimeSec * 1000
            if (triggerTime <= now) continue

            val requestCode = requestCodeFor(codeMap, slot.id)
            val intent = Intent(context, ClassPreparingNotificationReceiver::class.java).apply {
                putExtra(ClassPreparingNotificationReceiver.EXTRA_COURSE_NAME, slot.course.courseName)
                putExtra(ClassPreparingNotificationReceiver.EXTRA_CLASSROOM, slot.course.classroom)
                putExtra(ClassPreparingNotificationReceiver.EXTRA_INSTRUCTOR, slot.course.instructor)
                putExtra(ClassPreparingNotificationReceiver.EXTRA_START_MS, slot.startMs)
                putExtra(ClassPreparingNotificationReceiver.EXTRA_END_MS, slot.endMs)
                putExtra(ClassPreparingNotificationReceiver.EXTRA_NOTIFICATION_ID, requestCode)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            } catch (_: SecurityException) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
            scheduled.add(slot.id)
        }

        codeMap.keys.retainAll(scheduled)
        trackerPrefs.edit()
            .putStringSet(KEY_SCHEDULED_IDS, scheduled)
            .putString(KEY_CODE_MAP, encodeCodeMap(codeMap))
            .apply()
    }

    fun cancelAllTracked() {
        cancelAllTracked(loadCodeMap())
    }

    private fun cancelAllTracked(codeMap: Map<String, Int>) {
        val ids = trackerPrefs.getStringSet(KEY_SCHEDULED_IDS, emptySet()) ?: emptySet()
        for (id in ids) {
            val code = codeMap[id] ?: continue
            val intent = Intent(context, ClassPreparingNotificationReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                code,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            pendingIntent?.let { alarmManager.cancel(it) }
        }
        trackerPrefs.edit().remove(KEY_SCHEDULED_IDS).apply()
    }

    private fun requestCodeFor(map: MutableMap<String, Int>, id: String): Int =
        map.getOrPut(id) {
            val next = trackerPrefs.getInt(KEY_NEXT_CODE, 1)
            // Int.MAX_VALUE worth of slots is effectively unbounded, but wrap at
            // 1 just in case so we never hand out 0 or a negative code.
            val nextPlus = if (next == Int.MAX_VALUE) 1 else next + 1
            trackerPrefs.edit().putInt(KEY_NEXT_CODE, nextPlus).apply()
            next
        }

    private fun loadCodeMap(): MutableMap<String, Int> {
        val raw = trackerPrefs.getString(KEY_CODE_MAP, null) ?: return mutableMapOf()
        if (raw.isEmpty()) return mutableMapOf()
        val out = mutableMapOf<String, Int>()
        for (entry in raw.split(';')) {
            val sep = entry.lastIndexOf('=')
            if (sep <= 0) continue
            val key = entry.substring(0, sep)
            val code = entry.substring(sep + 1).toIntOrNull() ?: continue
            out[key] = code
        }
        return out
    }

    private fun encodeCodeMap(map: Map<String, Int>): String =
        map.entries.joinToString(";") { "${it.key}=${it.value}" }

    private data class UpcomingSlot(
        val course: Course,
        val startMs: Long,
        val endMs: Long,
        val id: String,
    )

    private fun upcomingSlots(
        courses: List<Course>,
        skippedDates: Map<String, List<String>>,
        daysAhead: Int,
    ): List<UpcomingSlot> {
        val today = LocalDate.now(AppConstants.TAIPEI_ZONE)
        val results = mutableListOf<UpcomingSlot>()
        for (dayOffset in 0 until daysAhead) {
            val date = today.plusDays(dayOffset.toLong())
            val weekdayIdx = when (date.dayOfWeek.value) {
                in 1..7 -> date.dayOfWeek.value // Monday=1 .. Sunday=7
                else -> continue
            }
            val isoDate = date.toString()
            for (course in courses) {
                val periods = course.schedule[weekdayIdx] ?: continue
                if (periods.isEmpty()) continue
                if (skippedDates[course.courseNo]?.contains(isoDate) == true) continue
                val ranges = collapseContiguousPeriods(periods)
                for ((first, last) in ranges) {
                    val start = periodStart(first) ?: continue
                    val end = periodEnd(last) ?: continue
                    val startZdt = ZonedDateTime.of(date, start, AppConstants.TAIPEI_ZONE)
                    val endZdt = ZonedDateTime.of(date, end, AppConstants.TAIPEI_ZONE)
                    val startMs = startZdt.toInstant().toEpochMilli()
                    val endMs = endZdt.toInstant().toEpochMilli()
                    results += UpcomingSlot(
                        course = course,
                        startMs = startMs,
                        endMs = endMs,
                        id = "${course.courseNo}@${isoDate}#${first}-${last}",
                    )
                }
            }
        }
        return results
    }

    companion object {
        private const val TRACKER_PREFS = "class_preparing_tracker"
        private const val KEY_SCHEDULED_IDS = "scheduled_ids"
        private const val KEY_CODE_MAP = "slot_request_codes"
        private const val KEY_NEXT_CODE = "next_request_code"
        private const val DAYS_AHEAD = 10

        private fun parseTime(text: String): LocalTime? = runCatching {
            val (h, m) = text.split(":").map { it.toInt() }
            LocalTime.of(h, m)
        }.getOrNull()

        private fun periodStart(id: String): LocalTime? =
            AppConstants.PeriodTimes.mapping[id]?.first?.let(::parseTime)

        private fun periodEnd(id: String): LocalTime? =
            AppConstants.PeriodTimes.mapping[id]?.second?.let(::parseTime)

    }
}
