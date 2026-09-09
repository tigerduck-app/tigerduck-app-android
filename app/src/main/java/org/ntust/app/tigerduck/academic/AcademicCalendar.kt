package org.ntust.app.tigerduck.academic

import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.network.model.AcademicCalendarDto
import java.time.LocalDate

/** One published term, with the inclusive days classes run between. */
data class SemesterTerm(
    val code: String,
    val start: LocalDate,
    val end: LocalDate,
) {
    fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
}

/** A named range on which classes do not meet. Single-day: [start] == [end]. */
data class Holiday(
    val id: Int,
    val nameZh: String,
    val nameEn: String,
    val start: LocalDate,
    val end: LocalDate,
) {
    fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)

    /** Chinese for any `zh-*` tag, English otherwise — matches how the
     *  backend authors the pair and how `WhatsNewRepository` picks. */
    fun name(languageTag: String): String =
        if (languageTag.startsWith("zh", ignoreCase = true)) nameZh else nameEn
}

/**
 * The school's calendar, as the app reasons about it.
 *
 * Pure and immutable so the notification scheduler — which runs from an
 * alarm, with no network and no coroutine scope — can ask it questions
 * directly. Everything that touches disk or HTTP lives in
 * [AcademicCalendarStore].
 *
 * [EMPTY] is the state before the first successful fetch, and every
 * predicate here is written so that state behaves exactly like the app did
 * before this feature existed: nothing is suppressed and the term falls back
 * to the month heuristic. Failing open matters more than failing safe —
 * silencing every class reminder because a server was unreachable would be a
 * far worse bug than ringing on one holiday.
 */
data class AcademicCalendar(
    val revision: Long,
    val terms: List<SemesterTerm>,
    val holidays: List<Holiday>,
) {
    /**
     * The term whose timetable the app should be showing.
     *
     * The most recently *begun* term, not the one containing [today]:
     * between terms there is no containing term, and the app has always kept
     * showing the last timetable rather than emptying itself. Suppression is
     * the holidays' job, so this deliberately stays permissive.
     *
     * Null when the calendar is empty, which sends callers to the month
     * heuristic they used before the feed existed.
     */
    fun currentTerm(today: LocalDate): SemesterTerm? {
        if (terms.isEmpty()) return null
        val begun = terms.filter { !today.isBefore(it.start) }
        // Before the earliest published term — a brand-new student in
        // August, say. The earliest term is the only sensible guess.
        return begun.maxByOrNull { it.start } ?: terms.minByOrNull { it.start }
    }

    /**
     * True while classes are in session, gating the "today"-scoped surfaces
     * (Home's time slider, the class table's today carousel).
     *
     * Unknown reads as in-session: with no calendar the app would otherwise
     * hide those surfaces forever on a device that has never reached the
     * backend.
     */
    fun isInSession(today: LocalDate): Boolean =
        terms.isEmpty() || terms.any { it.contains(today) }

    /**
     * The midnights at which [isInSession] changes its answer — every
     * published term's 開學日, and the day after its 結業日.
     *
     * Nothing announces a term flip on its own. The widgets' refresh chain is
     * driven by class times, so the first thing that would redraw them after
     * 開學 is the first class boundary of 開學日 — leaving the pre-term empty
     * state up through the whole first morning of the term, which is the
     * morning students are most likely to look. Same shape at 結業. See
     * [org.ntust.app.tigerduck.widget.WidgetBoundaryScheduler.chooseTriggerMillis].
     *
     * Empty for an empty calendar, which lands callers on the plain class
     * boundary they used before the feed existed.
     */
    fun termFlipMillis(): List<Long> = terms.flatMap { term ->
        listOfNotNull(startOfDayMillis(term.start), startOfDayMillis(term.end.plusDays(1)))
    }

    /** Null rather than a sentinel: an unbuildable date is simply not a flip. */
    private fun startOfDayMillis(date: LocalDate): Long? = runCatching {
        date.atStartOfDay(AppConstants.TAIPEI_ZONE).toInstant().toEpochMilli()
    }.getOrNull()

    fun holidaysOn(date: LocalDate): List<Holiday> = holidays.filter { it.contains(date) }

    /**
     * Whether class reminders, the live chip and the next-class widgets stay
     * quiet on [date].
     *
     * [optedInHolidayIds] are the holidays this user asked to keep hearing
     * about. Opting in to *any* holiday covering the day un-suppresses it:
     * the user is saying "I have class that day", and a second overlapping
     * holiday they never saw should not override what they explicitly asked
     * for.
     */
    fun suppressesClasses(date: LocalDate, optedInHolidayIds: Set<Int>): Boolean {
        val covering = holidaysOn(date)
        if (covering.isEmpty()) return false
        return covering.none { it.id in optedInHolidayIds }
    }

    companion object {
        val EMPTY = AcademicCalendar(revision = 0L, terms = emptyList(), holidays = emptyList())

        /**
         * Build from a decoded payload, dropping rows the server sent that
         * this build cannot make sense of.
         *
         * Skipping a malformed row rather than failing the whole parse is
         * deliberate: one bad holiday should cost that holiday, not the
         * entire calendar — and losing the whole calendar would silently
         * turn suppression off everywhere.
         */
        fun from(dto: AcademicCalendarDto?): AcademicCalendar {
            dto ?: return EMPTY
            val terms = dto.semesters.orEmpty().mapNotNull { row ->
                val code = row.code?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val start = parseDate(row.start) ?: return@mapNotNull null
                val end = parseDate(row.end) ?: return@mapNotNull null
                if (end.isBefore(start)) return@mapNotNull null
                SemesterTerm(code, start, end)
            }
            val holidays = dto.holidays.orEmpty().mapNotNull { row ->
                val start = parseDate(row.start) ?: return@mapNotNull null
                val end = parseDate(row.end) ?: return@mapNotNull null
                if (end.isBefore(start)) return@mapNotNull null
                // A nameless holiday would render as a blank calendar row and
                // an unexplained silence, which is worse than not having it.
                val zh = row.nameZh?.takeIf { it.isNotBlank() }
                val en = row.nameEn?.takeIf { it.isNotBlank() }
                if (zh == null && en == null) return@mapNotNull null
                Holiday(row.id, zh ?: en!!, en ?: zh!!, start, end)
            }
            return AcademicCalendar(dto.revision, terms, holidays)
        }

        private fun parseDate(text: String?): LocalDate? =
            text?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }
}
