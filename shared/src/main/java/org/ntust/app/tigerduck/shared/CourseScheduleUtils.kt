package org.ntust.app.tigerduck.shared

fun periodOrder(periodId: String): Int =
    Periods.chronologicalOrder.indexOf(periodId)

fun collapseContiguousPeriods(periods: List<String>): List<Pair<String, String>> =
    contiguousPeriodRuns(periods).map { it.first() to it.last() }

/**
 * [periods] in chronological order, split into runs of periods that follow one
 * another: `["3", "4", "7", "8"]` gives `[["3", "4"], ["7", "8"]]`. Each run is
 * one block of a class, the unit a course meeting twice a day shows as twice.
 * A period missing from [Periods.chronologicalOrder] is a run of its own.
 */
fun contiguousPeriodRuns(periods: List<String>): List<List<String>> {
    val sorted = periods.sortedBy(::periodOrder)
    val out = mutableListOf<List<String>>()
    var run = mutableListOf<String>()
    for (cur in sorted) {
        val prev = run.lastOrNull()
        if (prev != null && periodOrder(prev) >= 0 && periodOrder(cur) == periodOrder(prev) + 1) {
            run.add(cur)
        } else {
            if (run.isNotEmpty()) out += run
            run = mutableListOf(cur)
        }
    }
    if (run.isNotEmpty()) out += run
    return out
}

/**
 * The room of one block of this course: the rooms the per-period map gives
 * [periods] on [weekday], deduped and comma-joined in period order. Not
 * [Course.classroom] for the day, which joins every block's room, so a course
 * meeting in room A in the morning and room B in the afternoon says A for the
 * morning block. Falls back to the flat [Course.classroom] when the map has no
 * room for the block, as iOS `CourseBlockRoom.room` does.
 */
fun Course.blockClassroom(weekday: Int, periods: List<String>): String {
    val map = classroomMap
    val seen = LinkedHashSet<String>()
    for (period in periods.sortedBy(::periodOrder)) {
        val raw = map["$weekday-$period"] ?: continue
        seen.addAll(Course.splitRooms(raw))
    }
    return if (seen.isEmpty()) Course.dedupRooms(classroom) else seen.joinToString(", ")
}

/**
 * The room of this course's block on [weekday] that holds [periodId], or else of its first block
 * after it: where a 衝堂 tile's later course meets, given the tile's first period. The day's rooms
 * when the course has neither.
 */
fun Course.blockClassroomFrom(weekday: Int, periodId: String): String {
    val runs = contiguousPeriodRuns(schedule[weekday].orEmpty())
    val run = runs.firstOrNull { periodId in it }
        ?: runs.firstOrNull { periodOrder(it.first()) > periodOrder(periodId) }
    return if (run != null) blockClassroom(weekday, run) else classroom(weekday)
}

data class OngoingCourseInfo(
    val course: Course,
    val weekday: Int,
    val firstPeriodId: String,
    val startMinute: Int,
    val endMinute: Int,
    /** Every period of the block going on, in order; starts with [firstPeriodId]. */
    val periods: List<String>,
)

fun computeOngoingCourses(
    courses: List<Course>,
    weekday: Int,
    minuteOfDay: Int,
): List<OngoingCourseInfo> {
    val results = mutableListOf<OngoingCourseInfo>()
    for (course in courses) {
        val periods = course.schedule[weekday] ?: continue
        for (block in contiguousPeriodRuns(periods)) {
            val startMin = parseHm(PeriodTimes.mapping[block.first()]?.first)
            val endMin = parseHm(PeriodTimes.mapping[block.last()]?.second)
            if (startMin != null && endMin != null && minuteOfDay in startMin..endMin) {
                results.add(
                    OngoingCourseInfo(
                        course = course,
                        weekday = weekday,
                        firstPeriodId = block.first(),
                        startMinute = startMin,
                        endMinute = endMin,
                        periods = block,
                    )
                )
                break
            }
        }
    }
    return results
}

fun parseHm(hhmm: String?): Int? {
    hhmm ?: return null
    val parts = hhmm.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
    return h * 60 + m
}
