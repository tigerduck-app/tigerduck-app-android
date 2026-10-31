package org.ntust.app.tigerduck.widget

import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.coursechanges.ClassSlot
import org.ntust.app.tigerduck.coursechanges.CourseChanges
import org.ntust.app.tigerduck.coursechanges.CourseMeetings
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.blockClassroom
import org.ntust.app.tigerduck.shared.contiguousPeriodRuns
import org.ntust.app.tigerduck.shared.parseHm
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * One block of a course: a run of periods that follow one another on one
 * weekday. A course meeting in periods 3–4 and 7–8 has two, and every card,
 * row and highlight in the class widgets stands for one of them, never for the
 * whole day, so the morning block reads "10:20–12:10 · 3–4" in its own room
 * rather than "10:20–16:20 · 3–8" in both. Mirrors iOS
 * `WidgetTimelineDerivation`, which derives the same from its contiguous runs.
 *
 * On a dated day the school's course changes can split a block into the
 * classes it runs as, cancel one, move its time or its room; the block then
 * carries what changed, as iOS `TodayRow.change` does. Never persisted.
 */
data class CourseBlock(
    val course: Course,
    val weekday: Int,
    /** Never empty, in chronological order. */
    val periods: List<String>,
    /** Whether the school cancelled this class. */
    val isCancelled: Boolean = false,
    /** "HH:mm" the school moved the start to; null keeps the timetable's. */
    val changedStart: String? = null,
    /** "HH:mm" the school moved the end to; null keeps the timetable's. */
    val changedEnd: String? = null,
    /** The room the school moved this class to; null keeps the timetable's. */
    val movedRoom: String? = null,
) {
    val firstPeriodId: String get() = periods.first()
    val lastPeriodId: String get() = periods.last()

    /** "HH:mm" the block starts, or empty for a period with no known time. */
    val startTime: String
        get() = changedStart ?: AppConstants.PeriodTimes.mapping[firstPeriodId]?.first.orEmpty()

    /** "HH:mm" the block ends, or empty for a period with no known time. */
    val endTime: String
        get() = changedEnd ?: AppConstants.PeriodTimes.mapping[lastPeriodId]?.second.orEmpty()

    val startChanged: Boolean get() = changedStart != null
    val endChanged: Boolean get() = changedEnd != null
    val timeChanged: Boolean get() = startChanged || endChanged
    val roomChanged: Boolean get() = movedRoom != null

    /** "3" for a single period, "3–4" for a longer block. */
    val periodRange: String
        get() = if (periods.size == 1) firstPeriodId else "$firstPeriodId–$lastPeriodId"

    /** This block's room, not every room the course meets in that day. */
    val classroom: String get() = movedRoom ?: course.blockClassroom(weekday, periods)

    /** [classroom] as a widget writes it: after `⇄` where the school moved the class. */
    val roomLabel: String get() = if (roomChanged) "$MOVED_MARK $classroom" else classroom

    /**
     * The new time as a block writes it: both ends, the start alone or the end
     * after a dash; null when the school left it. As iOS `WeekGridView.changedTime`.
     */
    val changedTimeLabel: String?
        get() = when {
            changedStart != null && changedEnd != null -> "$changedStart–$changedEnd"
            changedStart != null -> changedStart
            changedEnd != null -> "–$changedEnd"
            else -> null
        }

    /** How far through the block [minuteOfDay] is, 0 to 1. */
    fun progress(minuteOfDay: Int): Float {
        val start = parseHm(startTime) ?: return 0f
        val end = parseHm(endTime) ?: return 0f
        if (end <= start) return 0f
        return ((minuteOfDay - start).toFloat() / (end - start)).coerceIn(0f, 1f)
    }

    /** Whether this is [other]'s block: the same course, starting at the same period, the same day. */
    fun isSameBlock(other: CourseBlock): Boolean =
        course.courseNo == other.course.courseNo &&
                weekday == other.weekday &&
                firstPeriodId == other.firstPeriodId

    companion object {
        /** Put before a moved room, as the iPhone's `arrow.left.arrow.right`. */
        const val MOVED_MARK = "⇄"

        /** [course]'s blocks on [weekday], in the order they are taught. */
        fun allOf(course: Course, weekday: Int): List<CourseBlock> =
            contiguousPeriodRuns(course.schedule[weekday].orEmpty())
                .map { CourseBlock(course, weekday, it) }

        /** The block of [course] on [weekday] that holds [periodId], or null if none does. */
        fun containing(course: Course, weekday: Int, periodId: String): CourseBlock? =
            allOf(course, weekday).firstOrNull { periodId in it.periods }

        /**
         * [course]'s classes on [day] under the school's [changes], cancelled
         * ones included: a meeting with a change as the classes it splits into,
         * by their changed starts, else the timetable's block. A day the feed
         * leaves alone is [allOf] as it was. As iOS
         * `WidgetTimelineDerivation.meetings(of:on:in:)`.
         */
        fun on(course: Course, day: LocalDate, changes: CourseChanges): List<CourseBlock> {
            val weekday = day.dayOfWeek.value
            val changed = CourseMeetings.changedClasses(course, day, changes)
            if (changed.isEmpty()) return allOf(course, weekday)
            return allOf(course, weekday)
                .flatMap { block ->
                    changed.filter { it.periods.first() in block.periods }.map(::of).ifEmpty { listOf(block) }
                }
                .sortedBy { parseHm(it.startTime) ?: Int.MAX_VALUE }
        }

        /** [slot] as a block, with what the school changed of it. */
        fun of(slot: ClassSlot): CourseBlock = CourseBlock(
            course = slot.course,
            weekday = slot.weekday,
            periods = slot.periods,
            isCancelled = slot.isCancelled,
            changedStart = slot.originalStart?.let { hm(slot.start) },
            changedEnd = slot.originalEnd?.let { hm(slot.end) },
            movedRoom = slot.room.takeIf { slot.hasChangedRoom },
        )

        // Latin digits in every locale, as the timetable's own times are written.
        private fun hm(time: LocalTime): String = "%02d:%02d".format(Locale.US, time.hour, time.minute)
    }
}
