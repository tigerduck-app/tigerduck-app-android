package org.ntust.app.tigerduck.shared

sealed class NextClassResult {
    data class Ongoing(
        val course: Course,
        val startMinute: Int,
        val endMinute: Int,
        val nextToday: NextToday?,
        /** Calendar weekday (1=Mon..7=Sun) — pass to [Course.classroom] for the right room. */
        val weekday: Int,
    ) : NextClassResult()

    data class NextToday(
        val course: Course,
        val startMinute: Int,
        val weekday: Int,
    ) : NextClassResult()

    data class NextFuture(
        val course: Course,
        val daysAhead: Int,        // 1 = tomorrow
        val startMinute: Int,
        val weekday: Int,
    ) : NextClassResult()

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
)

object NextClassResolver {

    fun resolve(
        courses: List<Course>,
        weekday: Int,
        minuteOfDay: Int,
    ): NextClassResult {
        val ongoing = computeOngoingCourses(courses, weekday, minuteOfDay).firstOrNull()
        if (ongoing != null) {
            val nextToday = nextStartingToday(
                courses,
                weekday,
                minuteOfDay,
                excluding = ongoing.course.courseNo
            )
            return NextClassResult.Ongoing(
                course = ongoing.course,
                startMinute = ongoing.startMinute,
                endMinute = ongoing.endMinute,
                nextToday = nextToday?.let {
                    NextClassResult.NextToday(
                        it.first,
                        it.second,
                        weekday
                    )
                },
                weekday = weekday,
            )
        }
        val next = nextStartingToday(courses, weekday, minuteOfDay, excluding = null)
        if (next != null) {
            return NextClassResult.NextToday(next.first, next.second, weekday)
        }
        // Walk forward up to 7 days.
        for (offset in 1..7) {
            val futureWeekday = ((weekday - 1 + offset) % 7) + 1
            val first = firstClassOfDay(courses, futureWeekday) ?: continue
            return NextClassResult.NextFuture(first.first, offset, first.second, futureWeekday)
        }
        return NextClassResult.Empty
    }

    fun todaysClasses(
        courses: List<Course>,
        weekday: Int,
        minuteOfDay: Int,
    ): List<TodayClassEntry> {
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
                    entries += TodayClassEntry(course, first, last, start, end, status, weekday)
                }
                i = j + 1
            }
        }
        return entries.sortedBy { it.startMinute }
    }

    private fun nextStartingToday(
        courses: List<Course>,
        weekday: Int,
        minuteOfDay: Int,
        excluding: String?,
    ): Pair<Course, Int>? {
        return courses
            .filter { it.courseNo != excluding && it.schedule.containsKey(weekday) }
            .mapNotNull { course ->
                val firstFutureMinute = course.schedule[weekday]!!
                    .mapNotNull { pid -> parseHm(PeriodTimes.mapping[pid]?.first) }
                    .filter { it > minuteOfDay }
                    .minOrNull()
                firstFutureMinute?.let { course to it }
            }
            .minByOrNull { it.second }
    }

    private fun firstClassOfDay(courses: List<Course>, weekday: Int): Pair<Course, Int>? {
        return courses
            .filter { it.schedule.containsKey(weekday) }
            .mapNotNull { course ->
                val firstMinute = course.schedule[weekday]!!
                    .mapNotNull { pid -> parseHm(PeriodTimes.mapping[pid]?.first) }
                    .minOrNull()
                firstMinute?.let { course to it }
            }
            .minByOrNull { it.second }
    }
}
