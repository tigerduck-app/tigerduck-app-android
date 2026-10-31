package org.ntust.app.tigerduck.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.coursechanges.CourseChangeStore
import org.ntust.app.tigerduck.coursechanges.CourseChanges
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.parseHm
import org.ntust.app.tigerduck.util.logSummary
import java.time.LocalDate
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms the alarm that redraws the class widgets at the next class boundary:
 * a start or end of today's classes, the school's changed ones included, or
 * the first of the next day with a class.
 */
@Singleton
class WidgetBoundaryScheduler internal constructor(
    private val context: Context,
    private val academicCalendar: AcademicCalendarStore,
    /** The school's course changes, whose new starts and ends are boundaries too. */
    private val courseChanges: () -> CourseChanges = { CourseChanges.EMPTY },
) {
    // The real entry point, which Hilt calls; tests build the scheduler
    // around a feed of their own, as LiveActivityManager's do.
    @Inject constructor(
        @ApplicationContext context: Context,
        academicCalendar: AcademicCalendarStore,
        courseChangeStore: CourseChangeStore,
    ) : this(context, academicCalendar, courseChangeStore::current)

    fun scheduleForToday(courses: List<Course>) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pi = makePendingIntent()
        alarmManager.cancel(pi)

        val cal = AppClock.calendar()
        val weekday = cal.toWeekday()
        val nowMinute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val today = AppClock.localDateTime().toLocalDate()
        val changes = courseChanges()

        val nextTodayMinute = nextBoundaryMinuteAfter(courses, weekday, nowMinute, today, changes)
        val triggerCal = if (nextTodayMinute != null) {
            // The app's day, not the phone's: under the debug clock they differ.
            AppClock.calendar().apply {
                set(Calendar.HOUR_OF_DAY, nextTodayMinute / 60)
                set(Calendar.MINUTE, nextTodayMinute % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        } else {
            // No more classes today — hand off to the first boundary of the
            // next day that has any courses, so the widget keeps advancing
            // (tomorrow's first class, next-class preview) without relying on
            // the 30-min updatePeriodMillis tick to eventually catch up.
            firstBoundaryOnOrAfterTomorrow(courses, weekday, today, changes) ?: return
        }

        val triggerMillis = chooseTriggerMillis(
            boundaryMillis = triggerCal.timeInMillis,
            appNowMillis = AppClock.nowMillis(),
            termFlips = academicCalendar.current().termFlipMillis(),
        )

        // canScheduleExactAlarms() can race a permission revocation: the user
        // (or the system, via Doze policy) may revoke SCHEDULE_EXACT_ALARM
        // between the check and the call, in which case setExactAndAllowWhileIdle
        // throws SecurityException. Fall back to an inexact alarm — same
        // pattern as AssignmentNotificationScheduler.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    AppClock.realTimeFor(triggerMillis),
                    pi
                )
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    AppClock.realTimeFor(triggerMillis),
                    pi
                )
            }
        } catch (e: SecurityException) {
            android.util.Log.w("WidgetBoundaryScheduler", "Exact alarm denied, falling back to inexact: ${e.logSummary()}")
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                AppClock.realTimeFor(triggerMillis),
                pi
            )
        }
    }

    private fun firstBoundaryOnOrAfterTomorrow(
        courses: List<Course>,
        fromWeekday: Int,
        today: LocalDate,
        changes: CourseChanges,
    ): Calendar? {
        for (offset in 1..7) {
            val targetWeekday = ((fromWeekday - 1 + offset) % 7) + 1
            val day = today.plusDays(offset.toLong())
            val minute = nextBoundaryMinuteAfter(courses, targetWeekday, -1, day, changes) ?: continue
            return AppClock.calendar().apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, minute / 60)
                set(Calendar.MINUTE, minute % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        }
        return null
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

        /**
         * Earlier of the next class boundary and the next 開學 / 結業 flip.
         *
         * A term boundary silently changes what the Today and Next Class
         * widgets are allowed to show — the calendar's `isInSession` flips at
         * midnight — but nothing announces it. The widgets only re-read that
         * flag when something asks them to redraw, and the next thing that
         * does is the *first class boundary of 開學日*. Left alone they would
         * sit on their pre-term empty state through the whole first morning of
         * the term, which is the morning students are most likely to look.
         * Same shape at 結業.
         *
         * The refresh this triggers re-enters [scheduleForToday], which re-arms
         * the ordinary boundary chain, so a term flip costs one extra alarm per
         * term and nothing after it. [termFlips] is strictly in the future by
         * the `>` below, so the flip cannot re-arm on itself.
         */
        internal fun chooseTriggerMillis(
            boundaryMillis: Long,
            appNowMillis: Long,
            termFlips: List<Long>,
        ): Long {
            val nextTermFlip = termFlips.filter { it > appNowMillis }.minOrNull()
                ?: return boundaryMillis
            return minOf(nextTermFlip, boundaryMillis)
        }

        /**
         * The first class start or end on [weekday] after [currentMinute]. On
         * [day], whose weekday it is, the school's [changes] add the new start
         * and end of each class they move, as iOS `WidgetTimelineDerivation.entryDates`
         * does; the timetable's own stay, which costs at most a redraw that
         * changes nothing.
         */
        internal fun nextBoundaryMinuteAfter(
            courses: List<Course>,
            weekday: Int,
            currentMinute: Int,
            day: LocalDate? = null,
            changes: CourseChanges = CourseChanges.EMPTY,
        ): Int? {
            val boundaries = mutableSetOf<Int>()
            for (course in courses) {
                val periods = course.schedule[weekday] ?: continue
                for (periodId in periods) {
                    val times = AppConstants.PeriodTimes.mapping[periodId] ?: continue
                    parseHm(times.first)?.let { boundaries.add(it) }
                    parseHm(times.second)?.let { boundaries.add(it) }
                }
                if (day != null) {
                    for (block in CourseBlock.on(course, day, changes)) {
                        parseHm(block.startTime)?.let { boundaries.add(it) }
                        parseHm(block.endTime)?.let { boundaries.add(it) }
                    }
                }
            }
            return boundaries.filter { it > currentMinute }.minOrNull()
        }
    }
}
