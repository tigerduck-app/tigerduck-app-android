// Where the subscribed clubs' activities go on the class table, as the iPhone's
// ClassTableClubLayout: a course always wins, and of two clubs the higher priority wins, so each
// activity is drawn as what is left of it once the day's courses and every higher club's
// activities are taken out, in proportion to its time.

package org.ntust.app.tigerduck.ui.screen.classtable

import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.clubs.ClubOccurrence
import org.ntust.app.tigerduck.data.model.TimetablePeriod
import org.ntust.app.tigerduck.shared.Course
import java.time.LocalDate

object ClassTableClubLayout {
    /** The block shows its club's name from this tall, in dp. */
    const val NAME_HEIGHT = 24f

    /** And its location from this tall. */
    const val LOCATION_HEIGHT = 44f

    /** A span of minutes after midnight. */
    data class Interval(val start: Int, val end: Int)

    /** What is left of [occurrence] between [start] and [end], and whether a blocker meets each end. */
    data class Segment(
        val occurrence: ClubOccurrence,
        val start: Int,
        val end: Int,
        val startsAtBlocker: Boolean,
        val endsAtBlocker: Boolean,
    )

    /** A segment placed on the column, [top] and [height] in dp from the first row's top. */
    data class Block(val segment: Segment, val top: Float, val height: Float) {
        val id: String get() = "${segment.occurrence.id}@${segment.start}"
        val showsName: Boolean get() = height >= NAME_HEIGHT
        val showsLocation: Boolean get() = height >= LOCATION_HEIGHT
    }

    /** Each activity of one day less [courses] and every activity ranked above it. */
    fun segments(day: List<ClubOccurrence>, courses: List<Interval>): List<Segment> {
        val blockers = courses.toMutableList()
        val segments = mutableListOf<Segment>()
        for (occurrence in ranked(day)) {
            pieces(Interval(occurrence.start, occurrence.end), blockers).forEach { piece ->
                segments += Segment(
                    occurrence, piece.start, piece.end,
                    startsAtBlocker = blockers.any { it.end == piece.start },
                    endsAtBlocker = blockers.any { it.start == piece.end },
                )
            }
            blockers += Interval(occurrence.start, occurrence.end)
        }
        return segments
    }

    /**
     * [segments] on rows [rowPitch] apart and [cellHeight] tall, [periods] their times; a segment
     * that meets a blocker leaves the gap between rows to it, and one with no height is not drawn.
     */
    fun blocks(segments: List<Segment>, periods: List<Interval>, rowPitch: Float, cellHeight: Float): List<Block> {
        val gap = rowPitch - cellHeight
        return segments.mapNotNull { segment ->
            val top = position(segment.start, periods, rowPitch, cellHeight) + if (segment.startsAtBlocker) gap else 0f
            val bottom = position(segment.end, periods, rowPitch, cellHeight) - if (segment.endsAtBlocker) gap else 0f
            if (bottom > top) Block(segment, top, bottom - top) else null
        }
    }

    /**
     * Where [minute] falls on the rows: inside a period by the share of it gone, between two in
     * the gap by the share of the break gone, held to the first row's top and the last row's
     * bottom. As the iPhone's `TimetableNowLine.position`.
     */
    fun position(minute: Int, periods: List<Interval>, rowPitch: Float, cellHeight: Float): Float {
        fun share(minute: Int, start: Int, end: Int) = (minute - start).toFloat() / maxOf(1, end - start)
        var previousEnd: Int? = null
        for ((index, period) in periods.withIndex()) {
            val top = index * rowPitch
            if (minute < period.start) {
                val before = previousEnd ?: return 0f
                val gap = rowPitch - cellHeight
                return top - gap + gap * share(minute, before, period.start)
            }
            if (minute <= period.end) return top + cellHeight * share(minute, period.start, period.end)
            previousEnd = period.end
        }
        return if (periods.isEmpty()) 0f else (periods.size - 1) * rowPitch + cellHeight
    }

    /** The times of [courses]' periods on [weekday]. */
    fun courseTimes(courses: List<Course>, weekday: Int): List<Interval> =
        courses.flatMap { it.schedule[weekday].orEmpty() }.mapNotNull { id -> TimetablePeriod.byId[id]?.let(::times) }

    fun times(period: TimetablePeriod): Interval = Interval(minutes(period.startTime), minutes(period.endTime))

    /** The drawn activities whose time meets that of [occurrence], itself included, highest ranked first. */
    fun overlapping(occurrence: ClubOccurrence, drawn: List<ClubOccurrence>): List<ClubOccurrence> {
        val seen = HashSet<Int>()
        return ranked(drawn.filter { it.start < occurrence.end && occurrence.start < it.end && seen.add(it.id) })
    }

    /** [occurrences] by the weekday (1 = Monday … 7 = Sunday) whose column stands for their day in the week from [today]. */
    fun byWeekday(occurrences: List<ClubOccurrence>, today: LocalDate): Map<Int, List<ClubOccurrence>> {
        val weekdays = (1..7).associateBy { ClassTableThisWeek.columnDate(it, today).toString() }
        return occurrences.groupBy { weekdays[it.day] ?: 0 }.filterKeys { it != 0 }
    }

    /** The dates of the week from [today], `yyyy-MM-dd`, which the class table's columns stand for. */
    fun weekDays(today: LocalDate): Set<String> = (1..7).mapTo(HashSet()) { ClassTableThisWeek.columnDate(it, today).toString() }

    /** [weekdays] with the weekend days a club reaches. */
    fun activeWeekdays(weekdays: List<Int>, clubs: Map<Int, List<ClubOccurrence>>): List<Int> {
        val days = weekdays.toSortedSet()
        clubs.forEach { (weekday, day) -> if (day.isNotEmpty()) days += weekday }
        return days.toList()
    }

    /** [periods] with every period a club's activity meets, in the day's order. */
    fun activePeriods(periods: List<TimetablePeriod>, clubs: Map<Int, List<ClubOccurrence>>): List<TimetablePeriod> {
        val ids = periods.mapTo(HashSet()) { it.id }
        val occurrences = clubs.values.flatten()
        TimetablePeriod.all.forEach { period ->
            val times = times(period)
            if (occurrences.any { it.start < times.end && times.start < it.end }) ids += period.id
        }
        return AppConstants.Periods.chronologicalOrder.filter { it in ids }.mapNotNull { TimetablePeriod.byId[it] }
    }

    private fun ranked(occurrences: List<ClubOccurrence>): List<ClubOccurrence> =
        occurrences.sortedWith(compareBy<ClubOccurrence>({ it.priority }, { it.start }, { it.id }))

    private fun pieces(interval: Interval, blockers: List<Interval>): List<Interval> =
        blockers.fold(listOf(interval)) { pieces, blocker ->
            pieces.flatMap { piece ->
                if (blocker.start >= piece.end || piece.start >= blocker.end) {
                    listOf(piece)
                } else {
                    listOf(Interval(piece.start, blocker.start), Interval(blocker.end, piece.end)).filter { it.start < it.end }
                }
            }
        }

    private fun minutes(hm: String): Int = hm.split(":").let { (h, m) -> h.toInt() * 60 + m.toInt() }
}
