package org.ntust.app.tigerduck.data.moodle

import org.ntust.app.tigerduck.network.model.moodle.MoodleMaterialSection
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Which of a course's material sections is this week's. Moodle numbers a weekly course's
 * sections from its start date, so those need no dates. Any other format leaves the week to the
 * section names, the only place a teacher writes it. Ported from iOS `MoodleCurrentWeek`.
 */
object MoodleCurrentWeek {
    const val WEEKLY_FORMAT = "weeks"

    /** The section's id; null when no section is this week's. [startDate] is in Unix seconds. */
    fun sectionId(
        sections: List<MoodleMaterialSection>,
        format: String?,
        startDate: Long?,
        now: Instant,
        zone: ZoneId,
    ): Int? {
        val today = now.atZone(zone).toLocalDate()
        if (format != WEEKLY_FORMAT || startDate == null) return sectionIdByNames(sections, today)
        val days = ChronoUnit.DAYS.between(Instant.ofEpochSecond(startDate).atZone(zone).toLocalDate(), today)
        if (days < 0) return null
        return sections.firstOrNull { it.number?.toLong() == days / 7 + 1 }?.id
    }

    private class DatedSection(val id: Int, val start: LocalDate, val end: LocalDate)

    /**
     * A name often gives only its week's first day, so a section lasts at least a week. Dates
     * that do not rise are likelier other numbers, such as "Quiz 1/2", than a schedule.
     */
    private fun sectionIdByNames(sections: List<MoodleMaterialSection>, today: LocalDate): Int? {
        val dated = sections.mapNotNull { section ->
            val days = monthDays(section.name).mapNotNull { (month, day) -> date(month, day, today) }
            val first = days.firstOrNull() ?: return@mapNotNull null
            DatedSection(section.id, first, maxOf(first.plusDays(7), days.last().plusDays(1)))
        }
        if (dated.zipWithNext().any { (a, b) -> a.start > b.start }) return null
        val index = dated.indexOfLast { it.start <= today }
        if (index < 0) return null
        val next = dated.getOrNull(index + 1)?.start ?: LocalDate.MAX
        return dated[index].id.takeIf { today < minOf(dated[index].end, next) }
    }

    private val monthDay = Regex("""(?<!\d)(\d{1,2})(?:[/／]|\s*月\s*)(\d{1,2})(?!\d)""")

    private fun monthDays(name: String): List<Pair<Int, Int>> =
        monthDay.findAll(name).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()

    /**
     * The date with that month and day nearest today, which puts a December section of a term
     * across New Year in the year before its January ones. Null for a day no year has, as 2/30.
     */
    private fun date(month: Int, day: Int, today: LocalDate): LocalDate? =
        (today.year - 1..today.year + 1)
            .mapNotNull { year ->
                try {
                    LocalDate.of(year, month, day)
                } catch (_: DateTimeException) {
                    null
                }
            }
            .minByOrNull { abs(ChronoUnit.DAYS.between(today, it)) }
}
