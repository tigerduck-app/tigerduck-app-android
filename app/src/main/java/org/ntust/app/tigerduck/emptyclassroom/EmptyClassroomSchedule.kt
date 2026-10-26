package org.ntust.app.tigerduck.emptyclassroom

import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.Holiday
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Period and day math for the finder: the 14 periods by index (0 is period 1, 13 is D), and
 * which day and period it opens on.
 */
object EmptyClassroomSchedule {
    val periodIds: List<String> = AppConstants.Periods.chronologicalOrder

    /** Today and the 13 days after it. */
    const val DAY_COUNT = 14

    /** Each period's start and end, in minutes after midnight. */
    private val periodMinutes: List<Pair<Int, Int>> = periodIds.map { id ->
        val (start, end) = AppConstants.PeriodTimes.mapping.getValue(id)
        minutes(start) to minutes(end)
    }

    fun timeRange(period: Int): Pair<String, String>? =
        periodIds.getOrNull(period)?.let { AppConstants.PeriodTimes.mapping[it] }

    fun startTime(period: Int): String = timeRange(period)?.first.orEmpty()

    fun endTime(period: Int): String = timeRange(period)?.second.orEmpty()

    /** "08:10–09:00". */
    fun timeRangeText(period: Int): String =
        timeRange(period)?.let { "${it.first}–${it.second}" }.orEmpty()

    fun periodIndex(periodId: String): Int? = periodIds.indexOf(periodId).takeIf { it >= 0 }

    /** The period in progress at [time], or during a break the next one; null after the last. */
    fun upcomingPeriod(time: LocalDateTime): Int? {
        val minute = time.hour * 60 + time.minute
        return periodMinutes.indexOfFirst { it.second > minute }.takeIf { it >= 0 }
    }

    /** How many of the day's periods have ended at [time]. */
    fun endedPeriods(time: LocalDateTime): Int {
        val minute = time.hour * 60 + time.minute
        return periodMinutes.count { it.second <= minute }
    }

    /**
     * The day and period the finder opens on: the current period, or the next one during a
     * break; after the last period, and on a day without classes, the first period of the next
     * teaching day within [horizon] days.
     */
    fun opening(now: LocalDateTime, horizon: Int, isTeachingDay: (LocalDate) -> Boolean): Pair<LocalDate, Int> {
        val today = now.toLocalDate()
        val upcoming = upcomingPeriod(now)
        if (isTeachingDay(today) && upcoming != null) return today to upcoming
        nextTeachingDay(today, horizon, isTeachingDay)?.let { return it to 0 }
        return today to (upcoming ?: 0)
    }

    fun nextTeachingDay(after: LocalDate, horizon: Int, isTeachingDay: (LocalDate) -> Boolean): LocalDate? =
        (1..horizon).asSequence().map { after.plusDays(it.toLong()) }.firstOrNull(isTeachingDay)

    fun isWeekend(day: LocalDate): Boolean =
        day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY

    /**
     * Why a day has no rooms to show, or null for a teaching day. The booking system projects
     * the weekly timetable onto every date, holidays and the weeks between terms included, so
     * those days would show rooms taken by classes that do not meet (tigerduck-app#478); on a
     * weekend it shows nothing at all.
     */
    fun dayOff(day: LocalDate, calendar: AcademicCalendar): DayOff? {
        if (isWeekend(day)) return DayOff.Weekend
        calendar.holidaysOn(day).firstOrNull()?.let { return DayOff.HolidayOff(it) }
        return if (calendar.isInSession(day)) null else DayOff.OutOfTerm
    }

    fun isTeachingDay(day: LocalDate, calendar: AcademicCalendar): Boolean = dayOff(day, calendar) == null

    private fun minutes(time: String): Int {
        val (hours, minutes) = time.split(":").map(String::toInt)
        return hours * 60 + minutes
    }
}

/** A day the finder shows no rooms for. */
sealed interface DayOff {
    data object Weekend : DayOff
    data object OutOfTerm : DayOff
    data class HolidayOff(val holiday: Holiday) : DayOff
}
