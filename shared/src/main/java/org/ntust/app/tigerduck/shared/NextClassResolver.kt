package org.ntust.app.tigerduck.shared

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

object NextClassResolver {

    /** No class changed: every course meets as its timetable has it. */
    val NO_CHANGES: (courseNo: String, daysAhead: Int) -> List<WatchClassChange> = { _, _ -> emptyList() }

    /**
     * The class going on now and the next one, today or within the coming week.
     *
     * [changedClasses] gives a course's classes the school changed on the day `daysAhead` from
     * today, cancelled ones included: each stands in for the block it starts in, at its changed
     * times and in its changed room, and a cancelled one is left out, as iOS
     * `WatchCourse.on(dayKey)` has them. A block the school left alone keeps its timetable times.
     *
     * [isQuietDay] tells whether no class meets for this user on the day
     * `daysAhead` from today (0 is today): outside the term, or a school
     * holiday they have not opted back into. Such a day's classes are left
     * out, so on a quiet today the next class is the first one of the next day
     * that has any.
     */
    fun resolve(
        courses: List<Course>,
        weekday: Int,
        minuteOfDay: Int,
        changedClasses: (courseNo: String, daysAhead: Int) -> List<WatchClassChange> = NO_CHANGES,
        isQuietDay: (daysAhead: Int) -> Boolean = { false },
    ): NextClassResult {
        val todaysCourses = if (isQuietDay(0)) emptyList() else courses
        val today = { course: Course -> classesOf(course, weekday, changedClasses(course.courseNo, 0)) }
        val ongoing = todaysCourses.firstNotNullOfOrNull { course ->
            today(course).firstOrNull { minuteOfDay in it.startMinute..it.endMinute }?.let { Pick(course, it.startMinute, it) }
        }
        if (ongoing != null) {
            // The course going on is left out of what comes next, as before changes; but a meeting
            // the school split runs as its classes, so only the one going on is left out of it.
            val split = changedClasses(ongoing.course.courseNo, 0).isNotEmpty()
            val nextToday = earliestStart(todaysCourses, after = minuteOfDay, today) { course, dayClass ->
                course.courseNo == ongoing.course.courseNo && (!split || dayClass.periods == ongoing.dayClass.periods)
            }
            return NextClassResult.Ongoing(
                course = ongoing.course,
                startMinute = ongoing.startMinute,
                endMinute = ongoing.dayClass.endMinute,
                nextToday = nextToday?.let {
                    NextClassResult.NextToday(it.course, it.startMinute, weekday, it.dayClass.periods, it.dayClass.change)
                },
                weekday = weekday,
                periods = ongoing.dayClass.periods,
                change = ongoing.dayClass.change,
            )
        }
        val next = earliestStart(todaysCourses, after = minuteOfDay, today)
        if (next != null) {
            return NextClassResult.NextToday(next.course, next.startMinute, weekday, next.dayClass.periods, next.dayClass.change)
        }
        // Walk forward up to 7 days.
        for (offset in 1..7) {
            if (isQuietDay(offset)) continue
            val futureWeekday = ((weekday - 1 + offset) % 7) + 1
            val first = earliestStart(courses, after = null, classes = {
                classesOf(it, futureWeekday, changedClasses(it.courseNo, offset))
            }) ?: continue
            return NextClassResult.NextFuture(
                first.course, offset, first.startMinute, futureWeekday, first.dayClass.periods, first.dayClass.change,
            )
        }
        return NextClassResult.Empty
    }

    /**
     * Today's classes in start order; none when [quietToday], as [resolve] has none. A class the
     * school changed today, from [changedClasses], stands in for its block as in [resolve].
     */
    fun todaysClasses(
        courses: List<Course>,
        weekday: Int,
        minuteOfDay: Int,
        quietToday: Boolean = false,
        changedClasses: (courseNo: String) -> List<WatchClassChange> = { emptyList() },
    ): List<TodayClassEntry> {
        if (quietToday) return emptyList()
        return courses.flatMap { course ->
            classesOf(course, weekday, changedClasses(course.courseNo)).map { dayClass ->
                val status = when {
                    minuteOfDay > dayClass.endMinute -> TodayClassStatus.Ended
                    minuteOfDay in dayClass.startMinute..dayClass.endMinute -> TodayClassStatus.Ongoing
                    else -> TodayClassStatus.Upcoming
                }
                TodayClassEntry(
                    course, dayClass.periods.first(), dayClass.periods.last(), dayClass.startMinute,
                    dayClass.endMinute, status, weekday, dayClass.periods, dayClass.change,
                )
            }
        }.sortedBy { it.startMinute }
    }

    /** One class of a course on a day: a timetable block, or a class the school changed one into. */
    private class DayClass(
        val periods: List<String>,
        val startMinute: Int,
        val endMinute: Int,
        val change: WatchClassChange?,
    ) {
        /**
         * The minutes it counts as starting at when looking for the next class: each period's
         * start in a timetable block, as before changes, and a changed class's own start alone.
         */
        val starts: List<Int>
            get() = if (change != null) {
                listOf(startMinute)
            } else {
                periods.mapNotNull { parseHm(PeriodTimes.mapping[it]?.first) }
            }
    }

    /** A course's class picked up at [startMinute]. */
    private class Pick(val course: Course, val startMinute: Int, val dayClass: DayClass)

    /**
     * [course]'s classes on [weekday] that meet: each block, or the classes in [changed] whose
     * first period is in it, minus the cancelled ones.
     */
    private fun classesOf(course: Course, weekday: Int, changed: List<WatchClassChange>): List<DayClass> =
        contiguousPeriodRuns(course.schedule[weekday].orEmpty()).flatMap { block ->
            val classes = changed.filter { it.periods.firstOrNull() in block }
            if (classes.isEmpty()) {
                listOfNotNull(dayClass(block, change = null))
            } else {
                classes.filter { !it.cancelled }.mapNotNull { dayClass(it.periods, it) }
            }
        }

    private fun dayClass(periods: List<String>, change: WatchClassChange?): DayClass? {
        val first = periods.firstOrNull() ?: return null
        val start = parseHm(change?.start) ?: parseHm(PeriodTimes.mapping[first]?.first) ?: return null
        val end = parseHm(change?.end) ?: parseHm(PeriodTimes.mapping[periods.last()]?.second) ?: return null
        return DayClass(periods, start, end, change)
    }

    /** The class that starts first, after [after] when given, of the classes [excluding] leaves. */
    private fun earliestStart(
        courses: List<Course>,
        after: Int?,
        classes: (Course) -> List<DayClass>,
        excluding: (Course, DayClass) -> Boolean = { _, _ -> false },
    ): Pick? = courses
        .mapNotNull { course ->
            classes(course)
                .filter { dayClass -> !excluding(course, dayClass) }
                .flatMap { dayClass -> dayClass.starts.map { Pick(course, it, dayClass) } }
                .filter { after == null || it.startMinute > after }
                .minByOrNull { it.startMinute }
        }
        .minByOrNull { it.startMinute }
}
