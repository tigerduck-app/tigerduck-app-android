package org.ntust.app.tigerduck

import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone

object AppConstants {
    const val APP_NAME = "TigerDuck"

    /** All "what day/time is it?" logic must use Taipei time, not the device timezone. */
    val TAIPEI_TZ: TimeZone = TimeZone.getTimeZone("Asia/Taipei")
    val TAIPEI_ZONE: ZoneId = ZoneId.of("Asia/Taipei")

    val Periods = org.ntust.app.tigerduck.shared.Periods
    val PeriodTimes = org.ntust.app.tigerduck.shared.PeriodTimes

    /**
     * `java.util.Calendar`'s Sunday-first weekday to the Monday-first index
     * course schedules are keyed by (Mon=1 … Sun=7).
     *
     * Here rather than in a feature package because Home, the class table and
     * the schedule keys generally all need it — it is a calendar fact, like
     * [TAIPEI_TZ] and [Periods], not a rule belonging to any one screen.
     */
    fun weekdayIndex(calendarDayOfWeek: Int): Int = when (calendarDayOfWeek) {
        Calendar.MONDAY -> 1
        Calendar.TUESDAY -> 2
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4
        Calendar.FRIDAY -> 5
        Calendar.SATURDAY -> 6
        Calendar.SUNDAY -> 7
        else -> 1
    }

}
