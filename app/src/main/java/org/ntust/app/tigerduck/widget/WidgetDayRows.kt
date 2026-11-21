package org.ntust.app.tigerduck.widget

import org.ntust.app.tigerduck.shared.parseHm
import org.ntust.app.tigerduck.shared.timeline.ClassTimeline

/**
 * A Today row and where the day is with it: over, going on with how far through it is, or still
 * to come. As iOS `WidgetClassRow`.
 */
internal data class WidgetClassRow(
    val block: CourseBlock,
    /** Minutes of the day the class starts and ends at, the school's changed ones where it moved them. */
    val start: Int,
    val end: Int,
    val isPast: Boolean,
    /** How far through the class is, 0 to 1, while it is going on; null otherwise. A cancelled class is never on. */
    val progress: Float?,
)

/** How a widget lays out a day's classes, as iOS `WidgetDayLayout`. */
internal object WidgetDayRows {

    /** How often a widget redraws during class hours, for its progress bars to move. */
    const val TICK_MINUTES = 5

    /**
     * [blocks], a day's classes in order, as of [minute] of that day: on and over by [ClassTimeline]'s
     * rule, the one every screen follows. A block with a period of no known time has no place in the
     * day and is left out.
     */
    fun rows(blocks: List<CourseBlock>, minute: Int): List<WidgetClassRow> = blocks.mapNotNull { block ->
        val start = parseHm(block.startTime) ?: return@mapNotNull null
        val end = parseHm(block.endTime) ?: return@mapNotNull null
        val ongoing = !block.isCancelled && ClassTimeline.isOn(start, end, minute)
        WidgetClassRow(
            block = block,
            start = start,
            end = end,
            isPast = ClassTimeline.isOver(end, minute),
            progress = if (ongoing) (minute - start).toFloat() / maxOf(end - start, 1) else null,
        )
    }

    /** The rows not over yet, which the medium Today widget lists. */
    fun upcoming(rows: List<WidgetClassRow>): List<WidgetClassRow> = rows.filter { !it.isPast }

    /**
     * At most [limit] of [rows], starting at the first class not over where they do not all fit,
     * and ending with the day's last where that leaves room.
     */
    fun window(rows: List<WidgetClassRow>, limit: Int): List<WidgetClassRow> {
        if (rows.size <= limit) return rows
        val firstNotPast = rows.indexOfFirst { !it.isPast }.let { if (it < 0) rows.size else it }
        val start = minOf(firstNotPast, rows.size - limit)
        return rows.subList(start, start + limit)
    }

    /**
     * The day's class hours: from the hour the first class starts in to the hour after the last
     * one ends, at least one hour; null for a day with no class.
     */
    fun hours(rows: List<WidgetClassRow>): IntRange? {
        val first = rows.minOfOrNull { it.start } ?: return null
        val last = rows.maxOf { it.end }
        return first / 60..maxOf(first / 60 + 1, (last + 59) / 60)
    }

    /**
     * The next minute after [minute] a widget redraws at for its progress: each [TICK_MINUTES]
     * through the class [hours] of [rows], the day's classes not cancelled; null past them or on a
     * day with none.
     */
    fun nextTick(rows: List<WidgetClassRow>, minute: Int): Int? {
        val hours = hours(rows.filter { !it.block.isCancelled }) ?: return null
        val first = hours.first * 60
        val last = hours.last * 60
        if (minute >= last) return null
        if (minute < first) return first
        return (minute / TICK_MINUTES + 1) * TICK_MINUTES
    }
}
