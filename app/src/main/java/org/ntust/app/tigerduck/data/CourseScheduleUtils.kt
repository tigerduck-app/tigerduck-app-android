package org.ntust.app.tigerduck.data

typealias OngoingCourseInfo = org.ntust.app.tigerduck.shared.OngoingCourseInfo

fun computeOngoingCourses(
    courses: List<org.ntust.app.tigerduck.shared.Course>,
    weekday: Int,
    minuteOfDay: Int,
): List<org.ntust.app.tigerduck.shared.OngoingCourseInfo> =
    org.ntust.app.tigerduck.shared.computeOngoingCourses(courses, weekday, minuteOfDay)

fun parseHm(hhmm: String?): Int? =
    org.ntust.app.tigerduck.shared.parseHm(hhmm)

internal fun periodOrder(periodId: String): Int =
    org.ntust.app.tigerduck.shared.periodOrder(periodId)

internal fun collapseContiguousPeriods(periods: List<String>): List<Pair<String, String>> =
    org.ntust.app.tigerduck.shared.collapseContiguousPeriods(periods)
