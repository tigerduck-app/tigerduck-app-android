package org.ntust.app.tigerduck.ui.screen.classtable

import org.ntust.app.tigerduck.AppConstants
import java.time.Instant
import java.time.LocalDate

/**
 * The class table as a week from today, the mode in which it shows the school's course changes.
 * Ported from iOS `ClassTableViewModel.showsThisWeek` and `columnDate`, and
 * `TimetableGridView.nowLineOffset`.
 */
object ClassTableThisWeek {
    /**
     * The current term's table runs as a week from today once the changes feed has answered;
     * other terms, and every term before then, stay a plain timetable. The feed's term is not
     * compared with the table's, as on iOS.
     */
    fun showsThisWeek(feedTerm: String, tableSemester: String, currentSemester: String): Boolean =
        feedTerm.isNotEmpty() && tableSemester == currentSemester

    /**
     * The date the [weekday] column (1 = Monday … 7 = Sunday) shows. The week runs from [today], so
     * on a Wednesday the Monday and Tuesday columns are next week's.
     */
    fun columnDate(weekday: Int, today: LocalDate): LocalDate =
        today.plusDays(((weekday - today.dayOfWeek.value + 7) % 7).toLong())

    /** [columnDate] for the Taipei day of [now]. */
    fun columnDate(weekday: Int, now: Instant): LocalDate =
        columnDate(weekday, now.atZone(AppConstants.TAIPEI_ZONE).toLocalDate())

    /**
     * Where the current-time line crosses the rows, from the top of the first one, row `i` starting
     * at `i * rowPitch`: inside a period by the share of it gone, between two shown periods in the
     * middle of their gap. Null before the first period and after the last. [periods] are the
     * shown rows' start and end, in minutes of the day.
     */
    fun nowLineOffset(minute: Int, periods: List<IntRange>, rowPitch: Float, cellHeight: Float): Float? {
        for ((index, period) in periods.withIndex()) {
            val top = index * rowPitch
            if (minute < period.first) return if (index == 0) null else top - (rowPitch - cellHeight) / 2
            if (minute <= period.last) {
                return top + cellHeight * (minute - period.first) / maxOf(1, period.last - period.first)
            }
        }
        return null
    }
}
