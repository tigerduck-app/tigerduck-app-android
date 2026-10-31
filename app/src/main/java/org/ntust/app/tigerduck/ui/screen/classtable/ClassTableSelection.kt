// What the class table can say about a course once you know which cell the
// user tapped, and what "now" is.
//
// All of it derives from a course plus a weekday, a period, or a wall-clock
// minute — no state, no I/O — so it is here rather than on the ViewModel,
// where reaching any of it meant standing up Hilt.

package org.ntust.app.tigerduck.ui.screen.classtable

import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.academic.QuietDays
import org.ntust.app.tigerduck.coursechanges.ClassSlot
import org.ntust.app.tigerduck.coursechanges.CourseChanges
import org.ntust.app.tigerduck.coursechanges.CourseMeetings
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.contiguousPeriodRuns
import java.time.LocalDate
import java.time.LocalTime
import java.util.Calendar

object ClassTableSelection {

    /**
     * Whether a course picked now is someone else's, which its detail shows read-only: one picked
     * from the class table while it shows someone else's. One picked from the live term, as Home
     * and the today strip pick it, is the user's own whatever the class table shows, as iOS opens
     * it from Home.
     */
    fun isSomeoneElses(showingSomeoneElse: Boolean, fromLiveTerm: Boolean): Boolean =
        showingSomeoneElse && !fromLiveTerm

    /**
     * The course the detail shows for [selected], as it is now, so a rename made from the detail
     * shows at once: the copy in the term it was picked from. That is [courses], the grid's term,
     * unless it was picked [fromAnotherTerm], the live term the today strip shows while the grid
     * is on another, whose copy is in [liveCourses]; one course number can be in both. A course
     * no longer listed keeps the copy that was picked.
     */
    fun detailCourse(
        selected: Course?,
        courses: List<Course>,
        liveCourses: List<Course>,
        fromAnotherTerm: Boolean,
    ): Course? {
        selected ?: return null
        val term = if (fromAnotherTerm) liveCourses else courses
        return term.firstOrNull { it.courseNo == selected.courseNo } ?: selected
    }

    /**
     * The course a rename of [courseNo] is measured against and synced for: the copy of the term
     * it was opened from, the live term's for a course picked from the today strip
     * ([fromAnotherTerm]), else the grid term's; failing that, the other term's copy.
     */
    fun renameTarget(
        courseNo: String,
        courses: List<Course>,
        liveCourses: List<Course>,
        fromAnotherTerm: Boolean,
    ): Course? {
        val (first, second) = if (fromAnotherTerm) liveCourses to courses else courses to liveCourses
        return first.firstOrNull { it.courseNo == courseNo } ?: second.firstOrNull { it.courseNo == courseNo }
    }

    /** [courses] with [courseNo]'s own name set to [name], or cleared with null. */
    fun renamed(courses: List<Course>, courseNo: String, name: String?): List<Course> =
        courses.map { if (it.courseNo == courseNo) it.copy(customCourseName = name) else it }

    /** The detail's title: the user's own name for the course, else its [fullName], else its name. */
    fun detailTitle(course: Course, fullName: String?): String =
        course.customCourseName ?: fullName ?: course.displayName

    /** Weekday (Mon=1 … Sun=7) paired with minutes since midnight. */
    data class DayTime(val weekday: Int, val minuteOfDay: Int)

    /**
     * Read a [DayTime] off a calendar. Monday-first, because that is how
     * course schedules are keyed; `Calendar` is Sunday-first.
     */
    fun dayTimeFrom(calendar: Calendar): DayTime {
        return DayTime(
            weekday = AppConstants.weekdayIndex(calendar.get(Calendar.DAY_OF_WEEK)),
            minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE),
        )
    }

    /**
     * Today's courses in the order they are taught.
     *
     * Sorted by the chronological index of each course's *first* period today
     * rather than by period id, because period ids are not lexicographically
     * ordered — "10" sorts before "2", and the lunch and evening periods are
     * lettered. A course with no resolvable period sinks to the end instead
     * of throwing off the whole ordering.
     */
    fun coursesOn(courses: List<Course>, weekday: Int): List<Course> = courses
        .filter { it.schedule.containsKey(weekday) }
        .sortedBy { course ->
            course.schedule[weekday]
                ?.minByOrNull { AppConstants.Periods.chronologicalOrder.indexOf(it) }
                ?.let { AppConstants.Periods.chronologicalOrder.indexOf(it) }
                ?: Int.MAX_VALUE
        }

    /**
     * The today carousel's courses: [coursesOn] for [today], or none on a
     * quiet day, which hides the carousel as a day without classes does. The
     * timetable is filled weeks before 開學, and no class meets on a holiday
     * the user has not opted back into.
     */
    fun todayCourses(courses: List<Course>, today: LocalDate, quietDays: QuietDays): List<Course> =
        if (today in quietDays) emptyList() else coursesOn(courses, today.dayOfWeek.value)

    /**
     * "09:10 - 12:00" for the tapped day, spanning the course's first to last
     * period on it. Null when the course does not meet that day, or when a
     * period has no time mapping — better a missing line than a wrong one.
     */
    fun timeRange(course: Course, weekday: Int): String? =
        course.schedule[weekday]?.let(::timeRange)

    /** "10:20 - 12:10" from the first of [periods] to the last, in the order they are taught. */
    fun timeRange(periods: List<String>): String? {
        val sorted = periods.sortedBy { AppConstants.Periods.chronologicalOrder.indexOf(it) }
        if (sorted.isEmpty()) return null
        val first = AppConstants.PeriodTimes.mapping[sorted.first()] ?: return null
        val last = AppConstants.PeriodTimes.mapping[sorted.last()] ?: return null
        return "${first.first} - ${last.second}"
    }

    /**
     * The run of [course]'s periods on [weekday], consecutive in [order], that [periodId] falls
     * in, or else the first after it: a 衝堂 pick names the cluster's first period, where the
     * course may start later. The grid passes the rows it draws, which leave out a period no
     * course takes, so two blocks either side of it are one cell and open together. As iOS
     * `SDCourse.block`. Null when the course has no such run that day.
     */
    fun block(
        course: Course,
        weekday: Int,
        periodId: String,
        order: List<String> = AppConstants.Periods.chronologicalOrder,
    ): List<String>? {
        val own = course.schedule[weekday].orEmpty().toSet()
        val runs = mutableListOf<MutableList<String>>()
        order.forEachIndexed { index, row ->
            if (row !in own) return@forEachIndexed
            if (index > 0 && order[index - 1] in own && runs.isNotEmpty()) runs.last() += row
            else runs += mutableListOf(row)
        }
        val start = order.indexOf(periodId)
        return runs.firstOrNull { periodId in it }
            ?: runs.firstOrNull { order.indexOf(it.first()) > start }
    }

    /**
     * The course detail's time: the [block] it was opened on, so a course meeting twice a day
     * shows the times of the one tapped; the whole day's without one.
     */
    fun detailTimeRange(course: Course, weekday: Int, block: List<String>?): String? =
        block?.let(::timeRange) ?: timeRange(course, weekday)

    /**
     * The block of [course] on [weekday] a today-carousel card stands for at [minuteOfDay]: the
     * first not yet over, or the last once all are. A course meeting twice a day shows the
     * afternoon block's times and room once the morning one ends. Null when it does not meet.
     */
    fun carouselBlock(course: Course, weekday: Int, minuteOfDay: Int): List<String>? {
        val runs = contiguousPeriodRuns(course.schedule[weekday].orEmpty())
        return runs.firstOrNull { run ->
            val end = AppConstants.PeriodTimes.mapping[run.last()]?.second?.let(::minuteOfDay)
            end == null || minuteOfDay <= end
        } ?: runs.lastOrNull()
    }

    /**
     * The classes of [courses] running at [minuteOfDay] on [today] as the school changed them, at
     * most one a course: a cancelled class is not running, and a moved one has its own room and
     * times. A class's last minute still counts, as the today strip has always counted it. As iOS
     * `ongoingCourses(at:changes:)`.
     */
    fun ongoingClasses(courses: List<Course>, today: LocalDate, minuteOfDay: Int, changes: CourseChanges): List<ClassSlot> =
        CourseMeetings.buildSlots(courses, today, changes)
            .filter { !it.isCancelled && minuteOfDay in minuteOf(it.start)..minuteOf(it.end) }
            .distinctBy { it.course.courseNo }

    /**
     * A today-strip card: the block of the course it stands for, the block as the school changed it
     * when a change touches it, and whether the course's classes today are over.
     */
    data class TodayCard(val periods: List<String>?, val meeting: ClassSlot?, val isFinished: Boolean)

    /**
     * The today-strip card of [course] at [minuteOfDay] on [today]: [carouselBlock] and
     * [isFinishedAt] by the changed times, so a class moved later is not over at its timetable end.
     * The block is merged back whole, as the class table shows it.
     */
    fun todayCard(course: Course, today: LocalDate, minuteOfDay: Int, changes: CourseChanges): TodayCard {
        val weekday = today.dayOfWeek.value
        if (changes.changes(course.courseNo, today).isEmpty()) {
            return TodayCard(
                carouselBlock(course, weekday, minuteOfDay),
                null,
                isFinishedAt(course, DayTime(weekday, minuteOfDay)),
            )
        }
        // By their changed starts: a meeting moved to the evening comes after the afternoon's.
        val meetings = contiguousPeriodRuns(course.schedule[weekday].orEmpty())
            .mapNotNull { CourseMeetings.meeting(course, it, today, changes) }
            .sortedBy { it.start }
        val meeting = meetings.firstOrNull { minuteOfDay <= minuteOf(it.end) } ?: meetings.lastOrNull()
        return TodayCard(
            meeting?.periods,
            meeting?.takeIf { it.changes.isNotEmpty() },
            meetings.maxOfOrNull { minuteOf(it.end) }?.let { minuteOfDay > it } ?: false,
        )
    }

    private fun minuteOf(time: LocalTime): Int = time.hour * 60 + time.minute

    /**
     * The room for the tapped slot, narrowing as far as the caller can say.
     *
     * A course legitimately meets in different rooms on different days, so
     * tapping a Wednesday cell must not show Monday's room. With no weekday
     * the best available answer is the de-duplicated union across the week;
     * with a weekday but no period, the day's room.
     */
    fun classroom(course: Course, weekday: Int?, periodId: String?): String = when {
        weekday == null -> Course.dedupRooms(course.classroom)
        periodId == null -> course.classroom(weekday)
        else -> course.classroom(weekday, periodId)
    }

    /**
     * True once the course's last period today has ended.
     *
     * False whenever that cannot be established — the course does not meet
     * today, or a period is missing a time mapping. Used to grey out finished
     * classes, so guessing "finished" would hide a class that is still on.
     */
    fun isFinishedAt(course: Course, dayTime: DayTime): Boolean {
        val lastPeriodId = course.schedule[dayTime.weekday]
            ?.sortedBy { AppConstants.Periods.chronologicalOrder.indexOf(it) }
            ?.lastOrNull() ?: return false
        val endMinutes = minuteOfDay(
            AppConstants.PeriodTimes.mapping[lastPeriodId]?.second ?: return false
        ) ?: return false
        return dayTime.minuteOfDay > endMinutes
    }

    /** "14:20" -> 860. Null on anything that is not HH:MM. */
    private fun minuteOfDay(clockTime: String): Int? {
        val parts = clockTime.split(":")
        val hours = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minutes = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return hours * 60 + minutes
    }
}
