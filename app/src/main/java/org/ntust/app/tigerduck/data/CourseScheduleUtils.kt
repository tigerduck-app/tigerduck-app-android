package org.ntust.app.tigerduck.data

import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.data.model.Course

internal fun periodOrder(periodId: String): Int =
    AppConstants.Periods.chronologicalOrder.indexOf(periodId)

internal fun collapseContiguousPeriods(periods: List<String>): List<Pair<String, String>> {
    val sorted = periods.sortedBy(::periodOrder)
    if (sorted.isEmpty()) return emptyList()

    val out = mutableListOf<Pair<String, String>>()
    var rangeStart = sorted.first()
    var prev = rangeStart
    for (i in 1 until sorted.size) {
        val cur = sorted[i]
        val prevOrder = periodOrder(prev)
        val curOrder = periodOrder(cur)
        if (prevOrder >= 0 && curOrder == prevOrder + 1) {
            prev = cur
        } else {
            out += rangeStart to prev
            rangeStart = cur
            prev = cur
        }
    }
    out += rangeStart to prev
    return out
}

data class OngoingCourseInfo(
    val course: Course,
    val weekday: Int,
    val firstPeriodId: String,
    val startMinute: Int,
    val endMinute: Int,
)

fun computeOngoingCourses(
    courses: List<Course>,
    weekday: Int,
    minuteOfDay: Int,
): List<OngoingCourseInfo> {
    val results = mutableListOf<OngoingCourseInfo>()
    for (course in courses) {
        val periods = course.schedule[weekday]
            ?.sortedBy(::periodOrder) ?: continue
        if (periods.isEmpty()) continue
        var blockStart = 0
        while (blockStart < periods.size) {
            var blockEnd = blockStart
            while (blockEnd + 1 < periods.size &&
                periodOrder(periods[blockEnd + 1]) == periodOrder(periods[blockEnd]) + 1
            ) blockEnd++
            val firstId = periods[blockStart]
            val lastId = periods[blockEnd]
            val startMin = parseHm(AppConstants.PeriodTimes.mapping[firstId]?.first)
            val endMin = parseHm(AppConstants.PeriodTimes.mapping[lastId]?.second)
            if (startMin != null && endMin != null && minuteOfDay in startMin..endMin) {
                results.add(
                    OngoingCourseInfo(
                        course = course,
                        weekday = weekday,
                        firstPeriodId = firstId,
                        startMinute = startMin,
                        endMinute = endMin,
                    )
                )
                break
            }
            blockStart = blockEnd + 1
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
