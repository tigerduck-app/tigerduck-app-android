package org.ntust.app.tigerduck.widget

import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.blockClassroom
import org.ntust.app.tigerduck.shared.contiguousPeriodRuns
import org.ntust.app.tigerduck.shared.parseHm

/**
 * One block of a course: a run of periods that follow one another on one
 * weekday. A course meeting in periods 3–4 and 7–8 has two, and every card,
 * row and highlight in the class widgets stands for one of them, never for the
 * whole day, so the morning block reads "10:20–12:10 · 3–4" in its own room
 * rather than "10:20–16:20 · 3–8" in both. Mirrors iOS
 * `WidgetTimelineDerivation`, which derives the same from its contiguous runs.
 */
data class CourseBlock(
    val course: Course,
    val weekday: Int,
    /** Never empty, in chronological order. */
    val periods: List<String>,
) {
    val firstPeriodId: String get() = periods.first()
    val lastPeriodId: String get() = periods.last()

    /** "HH:mm" the block starts, or empty for a period with no known time. */
    val startTime: String get() = AppConstants.PeriodTimes.mapping[firstPeriodId]?.first.orEmpty()

    /** "HH:mm" the block ends, or empty for a period with no known time. */
    val endTime: String get() = AppConstants.PeriodTimes.mapping[lastPeriodId]?.second.orEmpty()

    /** "3" for a single period, "3–4" for a longer block. */
    val periodRange: String
        get() = if (periods.size == 1) firstPeriodId else "$firstPeriodId–$lastPeriodId"

    /** This block's room, not every room the course meets in that day. */
    val classroom: String get() = course.blockClassroom(weekday, periods)

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
        /** [course]'s blocks on [weekday], in the order they are taught. */
        fun allOf(course: Course, weekday: Int): List<CourseBlock> =
            contiguousPeriodRuns(course.schedule[weekday].orEmpty())
                .map { CourseBlock(course, weekday, it) }

        /** The block of [course] on [weekday] that holds [periodId], or null if none does. */
        fun containing(course: Course, weekday: Int, periodId: String): CourseBlock? =
            allOf(course, weekday).firstOrNull { periodId in it.periods }
    }
}
