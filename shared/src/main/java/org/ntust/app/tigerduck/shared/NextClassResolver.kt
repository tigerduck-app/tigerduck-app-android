package org.ntust.app.tigerduck.shared

import org.ntust.app.tigerduck.shared.time.TaipeiClock
import org.ntust.app.tigerduck.shared.timeline.ClassSlot
import org.ntust.app.tigerduck.shared.timeline.ClassTimeline
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import java.time.LocalDate

sealed class NextClassResult {
    data class Ongoing(
        val course: Course,
        val startMinute: Int,
        val endMinute: Int,
        val nextToday: NextToday?,
        /** Calendar weekday (1=Mon..7=Sun) — pass to [Course.classroom] for the right room. */
        val weekday: Int,
        /** The periods of the block going on. */
        val periods: List<String>,
        /** What the school changed of this class today; null for the timetable's block. */
        val change: WatchClassChange? = null,
    ) : NextClassResult() {
        /** The room of the class going on, a moved one included, not every room the course meets in that day. */
        val classroom: String get() = change?.room ?: course.blockClassroom(weekday, periods)
    }

    data class NextToday(
        val course: Course,
        val startMinute: Int,
        val weekday: Int,
        /** The periods of the block that starts next. */
        val periods: List<String>,
        /** What the school changed of this class today; null for the timetable's block. */
        val change: WatchClassChange? = null,
    ) : NextClassResult() {
        /** The room of the class that starts next, a moved one included. */
        val classroom: String get() = change?.room ?: course.blockClassroom(weekday, periods)
    }

    data class NextFuture(
        val course: Course,
        val daysAhead: Int,        // 1 = tomorrow
        val startMinute: Int,
        val weekday: Int,
        /** The periods of that day's first block. */
        val periods: List<String>,
        /** What the school changed of this class that day; null for the timetable's block. */
        val change: WatchClassChange? = null,
    ) : NextClassResult() {
        /** The room of that day's first class, a moved one included. */
        val classroom: String get() = change?.room ?: course.blockClassroom(weekday, periods)
    }

    object Empty : NextClassResult()
}

enum class TodayClassStatus { Ended, Ongoing, Upcoming }

data class TodayClassEntry(
    val course: Course,
    val firstPeriodId: String,
    val lastPeriodId: String,
    val startMinute: Int,
    val endMinute: Int,
    val status: TodayClassStatus,
    val weekday: Int,
    /** Every period of this block, from [firstPeriodId] to [lastPeriodId]. */
    val periods: List<String>,
    /** What the school changed of this class today; null for the timetable's block. */
    val change: WatchClassChange? = null,
) {
    /** This class's room, a moved one included, not every room the course meets in that day. */
    val classroom: String get() = change?.room ?: course.blockClassroom(weekday, periods)
}

/**
 * What the watch shows now and next. The day's classes, with the school's changes laid over the
 * timetable, come from [ClassTimeline], the one home of that rule; what stays here is how the
 * watch picks among them:
 *  - a quiet day (outside the term, or a holiday the user kept) has no class, and the walk ahead
 *    skips it, which [ClassTimeline] does not know;
 *  - the class going on and the one after it are [ClassTimeline.nowAndNext]'s, as on iOS: the
 *    earliest start is going on, and the next is the first other class not yet over, so a later
 *    block of the same course is next and so is a class of another course that began meanwhile,
 *    with its real start;
 *  - of classes starting together the first course in the list comes first.
 */
object NextClassResolver {

    /**
     * The class going on now and the next one, today or within the coming week.
     *
     * [changes] are the classes the school changed, cancelled ones included: each stands in for the
     * block it starts in, at its changed times and in its changed room, and a cancelled one is
     * left out, as iOS `WatchCourse.on(dayKey)` has them. A block the school left alone keeps its
     * timetable times.
     *
     * [isQuietDay] tells whether no class meets for this user on the day
     * `daysAhead` from [today] (0 is today): outside the term, or a school
     * holiday they have not opted back into. Such a day's classes are left
     * out, so on a quiet today the next class is the first one of the next day
     * that has any.
     */
    fun resolve(
        courses: List<Course>,
        today: LocalDate,
        minuteOfDay: Int,
        changes: WatchCourseChanges = WatchCourseChanges.EMPTY,
        isQuietDay: (daysAhead: Int) -> Boolean = { false },
    ): NextClassResult {
        val timeline = changes.toCourseChanges(courses)
        val weekday = today.dayOfWeek.value
        val todaysCourses = if (isQuietDay(0)) emptyList() else courses
        val todays = classesOn(todaysCourses, today, changes, timeline)
        val now = today.atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(TaipeiClock.zone).toInstant()
        val found = ClassTimeline.nowAndNext(todays.map { it.slot }, now)
        val ongoing = found.current?.let { slot -> todays.first { it.slot === slot } }
        val nextToday = found.next?.let { slot -> todays.first { it.slot === slot } }
        if (ongoing != null) {
            return NextClassResult.Ongoing(
                course = ongoing.slot.course,
                startMinute = ongoing.startMinute,
                endMinute = ongoing.endMinute,
                nextToday = nextToday?.let {
                    NextClassResult.NextToday(it.slot.course, it.startMinute, weekday, it.periods, it.change)
                },
                weekday = weekday,
                periods = ongoing.periods,
                change = ongoing.change,
            )
        }
        if (nextToday != null) {
            return NextClassResult.NextToday(
                nextToday.slot.course, nextToday.startMinute, weekday, nextToday.periods, nextToday.change,
            )
        }
        // Walk forward up to 7 days.
        for (offset in 1..7) {
            if (isQuietDay(offset)) continue
            val day = today.plusDays(offset.toLong())
            val first = classesOn(courses, day, changes, timeline).firstOrNull() ?: continue
            return NextClassResult.NextFuture(
                first.slot.course, offset, first.startMinute, day.dayOfWeek.value, first.periods, first.change,
            )
        }
        return NextClassResult.Empty
    }

    /**
     * Today's classes in start order; none when [quietToday], as [resolve] has none. A class the
     * school changed today, from [changes], stands in for its block as in [resolve].
     */
    fun todaysClasses(
        courses: List<Course>,
        today: LocalDate,
        minuteOfDay: Int,
        quietToday: Boolean = false,
        changes: WatchCourseChanges = WatchCourseChanges.EMPTY,
    ): List<TodayClassEntry> {
        if (quietToday) return emptyList()
        return classesOn(courses, today, changes, changes.toCourseChanges(courses)).map { dayClass ->
            val status = when {
                minuteOfDay >= dayClass.endMinute -> TodayClassStatus.Ended
                minuteOfDay >= dayClass.startMinute -> TodayClassStatus.Ongoing
                else -> TodayClassStatus.Upcoming
            }
            TodayClassEntry(
                dayClass.slot.course, dayClass.periods.first(), dayClass.periods.last(), dayClass.startMinute,
                dayClass.endMinute, status, today.dayOfWeek.value, dayClass.periods, dayClass.change,
            )
        }
    }

    /** One class of a course on a day: a timetable block, or a class the school changed one into. */
    private class DayClass(val slot: ClassSlot, val change: WatchClassChange?) {
        val periods: List<String> get() = slot.periods
        val startMinute: Int get() = slot.start.hour * 60 + slot.start.minute
        val endMinute: Int get() = slot.end.hour * 60 + slot.end.minute
    }

    /** The classes of [courses] that meet on [day], by start: [ClassTimeline]'s, the cancelled ones left out. */
    private fun classesOn(
        courses: List<Course>,
        day: LocalDate,
        changes: WatchCourseChanges,
        timeline: CourseChanges,
    ): List<DayClass> =
        ClassTimeline.meetings(courses, day, timeline).filter { !it.isCancelled }.map { slot ->
            DayClass(slot, changes.on(slot.course.courseNo, day).firstOrNull { it.periods == slot.periods })
        }
}
