package org.ntust.app.tigerduck.data

import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.data.model.Course

data class OngoingCourseInfo(
    val course: Course,
    val weekday: Int,
    val firstPeriodId: String,
    val startMinute: Int,
    val endMinute: Int,
)

fun computeOngoingCourse(
    courses: List<Course>,
    weekday: Int,
    minuteOfDay: Int,
): OngoingCourseInfo? {
    val order = AppConstants.Periods.chronologicalOrder
    for (course in courses) {
        val periods = course.schedule[weekday]
            ?.sortedBy { order.indexOf(it) } ?: continue
        if (periods.isEmpty()) continue
        var blockStart = 0
        while (blockStart < periods.size) {
            var blockEnd = blockStart
            while (blockEnd + 1 < periods.size &&
                order.indexOf(periods[blockEnd + 1]) == order.indexOf(periods[blockEnd]) + 1
            ) blockEnd++
            val firstId = periods[blockStart]
            val lastId = periods[blockEnd]
            val startMin = parseHm(AppConstants.PeriodTimes.mapping[firstId]?.first)
            val endMin = parseHm(AppConstants.PeriodTimes.mapping[lastId]?.second)
            if (startMin != null && endMin != null && minuteOfDay in startMin..endMin) {
                return OngoingCourseInfo(
                    course = course,
                    weekday = weekday,
                    firstPeriodId = firstId,
                    startMinute = startMin,
                    endMinute = endMin,
                )
            }
            blockStart = blockEnd + 1
        }
    }
    return null
}

fun parseHm(hhmm: String?): Int? {
    hhmm ?: return null
    val parts = hhmm.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
    return h * 60 + m
}
