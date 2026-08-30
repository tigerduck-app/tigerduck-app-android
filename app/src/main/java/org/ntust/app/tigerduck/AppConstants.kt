package org.ntust.app.tigerduck

import org.ntust.app.tigerduck.shared.clock.AppClock
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

object AppConstants {
    const val APP_NAME = "TigerDuck"

    /** All "what day/time is it?" logic must use Taipei time, not the device timezone. */
    val TAIPEI_TZ: TimeZone = TimeZone.getTimeZone("Asia/Taipei")
    val TAIPEI_ZONE: ZoneId = ZoneId.of("Asia/Taipei")

    val Periods = org.ntust.app.tigerduck.shared.Periods
    val PeriodTimes = org.ntust.app.tigerduck.shared.PeriodTimes

    /**
     * The academic term the app currently considers "now".
     *
     * Hard-coded on purpose: nothing in the app knows when classes actually
     * start or end. [org.ntust.app.tigerduck.network.CourseService.heuristicSemesterCode]
     * only guesses a term *code* from the gregorian month, and
     * [org.ntust.app.tigerduck.network.SemesterCatalog] reports which term the
     * 選課 system has open — which runs weeks ahead of the term in session.
     *
     * Swap [CODE] / [START] / [END] for the academic calendar feed
     * ([org.ntust.app.tigerduck.network.CalendarService] already fetches the ICS
     * carrying 開學/結業) when that lands; every consumer reads [CODE] or
     * [isInSession] and needs no change.
     */
    object CurrentTerm {
        // TODO(開學時間): CODE / START / END are hard-coded for 115-1. Replace
        //  them with a backend-served 開學時間 / 結業時間 so a term rollover stops
        //  needing an app release. Every consumer already goes through [CODE],
        //  [isInSession] or [containsDate], so only this object has to change
        //  when that lands.

        /** 115 學年度第 1 學期. */
        const val CODE = "1151"

        /** First day of classes (開學日), Taipei wall time. */
        val START: Long = taipeiEpochMillis(2026, 9, 7)

        /**
         * Exclusive upper bound — the day *after* the last day of classes, so
         * 2026-12-25 counts as in session right up to midnight.
         */
        val END: Long = taipeiEpochMillis(2026, 12, 26)

        /**
         * True while classes are in session.
         *
         * Gates the "today"-scoped surfaces — Home's time slider and the class
         * table's today carousel — which outside the term would either sit
         * empty or scrub a day that has no classes.
         */
        fun isInSession(): Boolean {
            val now = AppClock.nowMillis()
            return now in START..<END
        }

        /**
         * True when [date] falls inside the term.
         *
         * Date-level counterpart to [isInSession], for the notification paths
         * that decide per-day rather than per-instant: class-preparing alarms
         * are armed several days ahead, and the Live Update resolves today's
         * slots. Both read straight from the timetable, which is already
         * populated well before 開學 — 選課 opens weeks ahead of the term — so
         * without this gate the app fires 即將上課 for classes that have not
         * started yet.
         *
         * Fails closed exactly as [isInSession] does: a date that cannot be
         * resolved reads false rather than leaking a notification.
         */
        fun containsDate(date: LocalDate): Boolean {
            val startOfDay = runCatching {
                date.atStartOfDay(TAIPEI_ZONE).toInstant().toEpochMilli()
            }.getOrNull() ?: return false
            return startOfDay in START..<END
        }

        /**
         * Fails closed: an unbuildable date lands in the distant future, so
         * [isInSession] reads false rather than true-forever.
         */
        private fun taipeiEpochMillis(year: Int, month: Int, day: Int): Long =
            runCatching {
                LocalDate.of(year, month, day).atStartOfDay(TAIPEI_ZONE).toInstant().toEpochMilli()
            }.getOrDefault(Long.MAX_VALUE)
    }
}
