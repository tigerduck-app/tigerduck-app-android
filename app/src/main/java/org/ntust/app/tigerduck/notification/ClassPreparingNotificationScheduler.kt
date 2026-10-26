package org.ntust.app.tigerduck.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.network.SemesterCodes
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.blockClassroom
import org.ntust.app.tigerduck.shared.contiguousPeriodRuns
import org.ntust.app.tigerduck.shared.clock.AppClock
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
) : ClassReminderAlarms {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val trackerPrefs = context.getSharedPreferences(TRACKER_PREFS, Context.MODE_PRIVATE)

    // Serializes scheduleAll / cancelAllTracked across boot, live-activity
    // refresh, and BackgroundSyncWorker. Without this, concurrent callers
    // would each load their own codeMap copy, mint duplicate request codes
    // off KEY_NEXT_CODE (read-inc-apply is async + non-atomic), and clobber
    // each other's persisted state.
    private val schedulerLock = Any()

    /**
     * @param coursesByTerm each term's courses, by code: a day's reminders
     *   come from the term [termOn] puts it in, and a day whose term is
     *   missing here gets none.
     * @param calendar the school calendar, used to skip days classes do not
     *   meet; [AcademicCalendar.EMPTY] suppresses nothing.
     * @param optedInHolidayIds holidays this user asked to keep hearing
     *   about, which put their days back in.
     */
    override fun scheduleAll(
        coursesByTerm: Map<String, List<Course>>,
        skippedDates: Map<String, List<String>>,
        leadTimeSec: Long,
        calendar: AcademicCalendar,
        optedInHolidayIds: Set<Int>,
    ): Long? = synchronized(schedulerLock) {
        // Load the persisted slotId→requestCode map BEFORE cancelling so cancel
        // looks up the same code each alarm was originally registered with.
        // Previously we hashed slotId into the requestCode, which could collide
        // across two different slots and silently overwrite the earlier alarm.
        val codeMap = loadCodeMap()
        cancelAllTrackedLocked(codeMap)
        if (leadTimeSec <= 0 || coursesByTerm.values.all { it.isEmpty() }) return@synchronized null

        val now = AppClock.nowMillis()
        val scheduled = mutableSetOf<String>()
        var nextCode = trackerPrefs.getInt(KEY_NEXT_CODE, 1)
        var nextClassStart: Long? = null

        for (slot in upcomingSlots(
            coursesByTerm, skippedDates, daysAhead = DAYS_AHEAD,
            calendar = calendar, optedInHolidayIds = optedInHolidayIds,
        )) {
            // Before the trigger check: a class whose reminder has gone off
            // still has its banner up until it begins.
            if (slot.startMs > now) nextClassStart = minOf(nextClassStart ?: slot.startMs, slot.startMs)
            val triggerTime = slot.startMs - leadTimeSec * 1000
            if (triggerTime <= now) continue

            val requestCode = codeMap.getOrPut(slot.id) {
                val current = nextCode
                nextCode = if (current == Int.MAX_VALUE) 1 else current + 1
                current
            }
            val intent = Intent(context, ClassPreparingNotificationReceiver::class.java).apply {
                putExtra(
                    ClassPreparingNotificationReceiver.EXTRA_COURSE_NAME,
                    slot.course.displayName
                )
                putExtra(ClassPreparingNotificationReceiver.EXTRA_CLASSROOM, slot.classroom)
                putExtra(
                    ClassPreparingNotificationReceiver.EXTRA_INSTRUCTOR,
                    slot.course.instructor
                )
                putExtra(ClassPreparingNotificationReceiver.EXTRA_TERM, slot.term)
                putExtra(ClassPreparingNotificationReceiver.EXTRA_START_MS, slot.startMs)
                putExtra(ClassPreparingNotificationReceiver.EXTRA_END_MS, slot.endMs)
                putExtra(ClassPreparingNotificationReceiver.EXTRA_NOTIFICATION_ID, requestCode)
                putExtra(
                    ClassPreparingNotificationReceiver.EXTRA_LEAD_TIME_MS,
                    leadTimeSec * 1000L,
                )
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            // Without exact alarms, which Android 14 grants no app by default,
            // the reminder is still allowed through Doze: a plain set() would
            // wait for Doze's next maintenance window, however late that is.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        AppClock.realTimeFor(triggerTime),
                        pendingIntent
                    )
                } else {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        AppClock.realTimeFor(triggerTime),
                        pendingIntent
                    )
                }
            } catch (_: SecurityException) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    AppClock.realTimeFor(triggerTime),
                    pendingIntent
                )
            }
            scheduled.add(slot.id)
        }

        codeMap.keys.retainAll(scheduled)
        trackerPrefs.edit()
            .putStringSet(KEY_SCHEDULED_IDS, scheduled)
            .putString(KEY_CODE_MAP, encodeCodeMap(codeMap))
            .putInt(KEY_NEXT_CODE, nextCode)
            .apply()
        nextClassStart
    }

    override fun cancelAllTracked() = synchronized(schedulerLock) {
        cancelAllTrackedLocked(loadCodeMap())
    }

    private fun cancelAllTrackedLocked(codeMap: Map<String, Int>) {
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
        // Drop the per-slot code map alongside the scheduled-ids set so it
        // doesn't accumulate dead entries across reschedules, boots, and
        // logouts. KEY_NEXT_CODE is intentionally preserved to avoid handing
        // out a code that some still-pending alarm might be using.
        trackerPrefs.edit()
            .remove(KEY_SCHEDULED_IDS)
            .remove(KEY_CODE_MAP)
            .apply()
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
        /** The term whose timetable [course] came from. */
        val term: String,
        val startMs: Long,
        val endMs: Long,
        val id: String,
        val weekday: Int,
        /** This block's periods, not every period of the course that day. */
        val periods: List<String>,
    ) {
        /** This block's room: a course meeting twice a day can switch rooms in between. */
        val classroom: String get() = course.blockClassroom(weekday, periods)
    }

    private fun upcomingSlots(
        coursesByTerm: Map<String, List<Course>>,
        skippedDates: Map<String, List<String>>,
        daysAhead: Int,
        calendar: AcademicCalendar,
        optedInHolidayIds: Set<Int>,
    ): List<UpcomingSlot> {
        val today = AppClock.localDateTime().toLocalDate()
        val results = mutableListOf<UpcomingSlot>()
        for (dayOffset in 0 until daysAhead) {
            val date = today.plusDays(dayOffset.toLong())
            val weekdayIdx = when (date.dayOfWeek.value) {
                in 1..7 -> date.dayOfWeek.value // Monday=1 .. Sunday=7
                else -> continue
            }
            // No 即將上課 alarms outside the term. The timetable is populated
            // weeks before 開學 (選課 opens ahead of the term), so an install in
            // late August would otherwise arm reminders for classes that have
            // not started.
            if (!calendar.isInSession(date)) continue
            // Classes do not meet on a school holiday, so nothing should be
            // scheduled for that day at all. Both checks are per day rather
            // than at the call site because this loop reaches ten days ahead
            // and either 開學 or a holiday can land partway through that window.
            if (calendar.suppressesClasses(date, optedInHolidayIds)) continue
            // Each day from its own term's timetable: the window reaches past
            // 開學 while the term the app shows is still the last one, whose
            // classes, times and rooms are not the new term's. A term with
            // nothing cached arms nothing rather than borrow another's.
            val term = termOn(calendar, date)
            val courses = coursesByTerm[term] ?: continue
            val isoDate = date.toString()
            for (course in courses) {
                val periods = course.schedule[weekdayIdx] ?: continue
                if (periods.isEmpty()) continue
                if (skippedDates[course.courseNo]?.contains(isoDate) == true) continue
                for (block in contiguousPeriodRuns(periods)) {
                    val first = block.first()
                    val last = block.last()
                    val start = periodStart(first) ?: continue
                    val end = periodEnd(last) ?: continue
                    val startZdt = ZonedDateTime.of(date, start, AppConstants.TAIPEI_ZONE)
                    val endZdt = ZonedDateTime.of(date, end, AppConstants.TAIPEI_ZONE)
                    val startMs = startZdt.toInstant().toEpochMilli()
                    val endMs = endZdt.toInstant().toEpochMilli()
                    results += UpcomingSlot(
                        course = course,
                        term = term,
                        startMs = startMs,
                        endMs = endMs,
                        id = "${course.courseNo}@${isoDate}#${first}-${last}",
                        weekday = weekdayIdx,
                        periods = block,
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

        /**
         * The term whose timetable [date]'s reminders come from: the one the
         * calendar puts that day in. With no calendar, the month guess
         * DataCache falls back on too, the same term for every day.
         */
        internal fun termOn(calendar: AcademicCalendar, date: LocalDate): String =
            calendar.currentTerm(date)?.code ?: SemesterCodes.heuristic()

        /**
         * The terms whose courses [scheduleAll] needs to arm the days ahead
         * from [today]; see [termOn].
         */
        internal fun termsAhead(calendar: AcademicCalendar, today: LocalDate): Set<String> =
            (0 until DAYS_AHEAD).mapTo(linkedSetOf()) { termOn(calendar, today.plusDays(it.toLong())) }

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
