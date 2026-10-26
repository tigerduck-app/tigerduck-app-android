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
    ) : NextClassResult() {
        /** The room of the block going on, not every room the course meets in that day. */
        val classroom: String get() = course.blockClassroom(weekday, periods)
    }

    data class NextToday(
        val course: Course,
        val startMinute: Int,
        val weekday: Int,
        /** The periods of the block that starts next. */
        val periods: List<String>,
    ) : NextClassResult() {
        /** The room of the block that starts next. */
        val classroom: String get() = course.blockClassroom(weekday, periods)
    }

    data class NextFuture(
        val course: Course,
        val daysAhead: Int,        // 1 = tomorrow
        val startMinute: Int,
        val weekday: Int,
        /** The periods of that day's first block. */
        val periods: List<String>,
    ) : NextClassResult() {
        /** The room of that day's first block. */
        val classroom: String get() = course.blockClassroom(weekday, periods)
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
) {
    /** This block's room, not every room the course meets in that day. */
    val classroom: String get() = course.blockClassroom(weekday, periods)
}

object NextClassResolver {

    /**
     * The class going on now and the next one, today or within the coming week.
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
        isQuietDay: (daysAhead: Int) -> Boolean = { false },
    ): NextClassResult {
        val todaysCourses = if (isQuietDay(0)) emptyList() else courses
        val ongoing = computeOngoingCourses(todaysCourses, weekday, minuteOfDay).firstOrNull()
        if (ongoing != null) {
            val nextToday = nextStartingToday(
                todaysCourses,
                weekday,
                minuteOfDay,
                excluding = ongoing.course.courseNo
            )
            return NextClassResult.Ongoing(
                course = ongoing.course,
                startMinute = ongoing.startMinute,
                endMinute = ongoing.endMinute,
                nextToday = nextToday?.let {
                    NextClassResult.NextToday(it.course, it.startMinute, weekday, it.periods)
                },
                weekday = weekday,
                periods = ongoing.periods,
            )
        }
        val next = nextStartingToday(todaysCourses, weekday, minuteOfDay, excluding = null)
        if (next != null) {
            return NextClassResult.NextToday(next.course, next.startMinute, weekday, next.periods)
        }
        // Walk forward up to 7 days.
        for (offset in 1..7) {
            if (isQuietDay(offset)) continue
            val futureWeekday = ((weekday - 1 + offset) % 7) + 1
            val first = firstClassOfDay(courses, futureWeekday) ?: continue
            return NextClassResult.NextFuture(
                first.course, offset, first.startMinute, futureWeekday, first.periods,
            )
        }
        return NextClassResult.Empty
    }

    /** Today's classes in start order; none when [quietToday], as [resolve] has none. */
    fun todaysClasses(
        courses: List<Course>,
        weekday: Int,
        minuteOfDay: Int,
        quietToday: Boolean = false,
    ): List<TodayClassEntry> {
        if (quietToday) return emptyList()
        val entries = mutableListOf<TodayClassEntry>()
        for (course in courses) {
            val periods = course.schedule[weekday]?.sortedBy(::periodOrder) ?: continue
            var i = 0
            while (i < periods.size) {
                var j = i
                while (j + 1 < periods.size &&
                    periodOrder(periods[j + 1]) == periodOrder(periods[j]) + 1
                ) j++
                val first = periods[i]
                val last = periods[j]
                val start = parseHm(PeriodTimes.mapping[first]?.first)
                val end = parseHm(PeriodTimes.mapping[last]?.second)
                if (start != null && end != null) {
                    val status = when {
                        minuteOfDay > end -> TodayClassStatus.Ended
                        minuteOfDay in start..end -> TodayClassStatus.Ongoing
                        else -> TodayClassStatus.Upcoming
                    }
                    entries += TodayClassEntry(
                        course, first, last, start, end, status, weekday, periods.subList(i, j + 1),
                    )
                }
                i = j + 1
            }
        }
        return entries.sortedBy { it.startMinute }
    }

    /** A course's class starting at [startMinute], in the block of [periods]. */
    private data class Pick(val course: Course, val startMinute: Int, val periods: List<String>)

    /** The run of [course]'s periods on [weekday] that holds [periodId]. */
    private fun blockHolding(course: Course, weekday: Int, periodId: String): List<String> =
        contiguousPeriodRuns(course.schedule[weekday].orEmpty()).firstOrNull { periodId in it }
            ?: listOf(periodId)

    private fun nextStartingToday(
        courses: List<Course>,
        weekday: Int,
        minuteOfDay: Int,
        excluding: String?,
    ): Pick? {
        return courses
            .filter { it.courseNo != excluding && it.schedule.containsKey(weekday) }
            .mapNotNull { course ->
                course.schedule[weekday]!!
                    .mapNotNull { pid -> parseHm(PeriodTimes.mapping[pid]?.first)?.let { pid to it } }
                    .filter { it.second > minuteOfDay }
                    .minByOrNull { it.second }
                    ?.let { (pid, start) -> Pick(course, start, blockHolding(course, weekday, pid)) }
            }
            .minByOrNull { it.startMinute }
    }

    private fun firstClassOfDay(courses: List<Course>, weekday: Int): Pick? {
        return courses
            .filter { it.schedule.containsKey(weekday) }
            .mapNotNull { course ->
                course.schedule[weekday]!!
                    .mapNotNull { pid -> parseHm(PeriodTimes.mapping[pid]?.first)?.let { pid to it } }
                    .minByOrNull { it.second }
                    ?.let { (pid, start) -> Pick(course, start, blockHolding(course, weekday, pid)) }
            }
            .minByOrNull { it.startMinute }
    }
}
