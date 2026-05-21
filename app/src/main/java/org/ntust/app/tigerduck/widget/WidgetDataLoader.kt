package org.ntust.app.tigerduck.widget

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.data.computeOngoingCourses
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.data.parseHm
import org.ntust.app.tigerduck.ui.theme.buildCourseColorAssignments
import java.util.Calendar

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun dataCache(): DataCache
    fun authService(): AuthService
}

object WidgetDataLoader {

    suspend fun load(context: Context): WidgetState {
        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java,
        )
        val courses = entry.dataCache().loadCourses()
        // "Logged in" here means the user has stored credentials, not that
        // their SSO cookies are still fresh. Cookie validity gates fetching;
        // it shouldn't gate displaying the cached classtable. Without this,
        // an expired 1h cookie or a process restart while offline makes the
        // widget say "Please sign in" even though we have cached courses.
        val isLoggedIn = entry.authService().authState.value

        val cal = AppClock.calendar()
        val weekday = cal.toWeekday()
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        val ongoingInfos = computeOngoingCourses(courses, weekday, minuteOfDay)
        val ongoingNos = ongoingInfos.map { it.course.courseNo }
        val nextCourseTodayNo = computeNextCourseTodayNo(
            courses, weekday, minuteOfDay, ongoingNos,
        )
        val tomorrowFirst = computeTomorrowFirst(courses, weekday)

        return WidgetState(
            courses = courses,
            activeWeekdays = computeActiveWeekdays(courses),
            activePeriodIds = computeActivePeriodIds(courses),
            currentWeekday = weekday,
            currentMinuteOfDay = minuteOfDay,
            isLoggedIn = isLoggedIn,
            ongoingCourseNos = ongoingNos,
            nextCourseTodayNo = nextCourseTodayNo,
            tomorrowFirstCourseNo = tomorrowFirst?.courseNo,
            tomorrowFirstCourseWeekday = tomorrowFirst?.weekday,
            tomorrowFirstCoursePeriodId = tomorrowFirst?.periodId,
            courseColors = buildCourseColorAssignments(courses),
        )
    }

    private data class TomorrowFirst(
        val courseNo: String,
        val weekday: Int,
        val periodId: String,
    )

    private fun Calendar.toWeekday(): Int = when (get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6
        else -> 7
    }

    private fun computeActiveWeekdays(courses: List<Course>): List<Int> {
        val days = courses.flatMap { it.schedule.keys }.toMutableSet()
        val result = (1..5).toMutableList()
        if (6 in days) result.add(6)
        if (7 in days) result.add(7)
        return result
    }

    private fun computeActivePeriodIds(courses: List<Course>): List<String> {
        val ids = AppConstants.Periods.defaultVisible.toMutableSet()
        courses.forEach { course -> course.schedule.values.forEach { ids.addAll(it) } }
        return AppConstants.Periods.chronologicalOrder.filter { it in ids }
    }

    private fun computeNextCourseTodayNo(
        courses: List<Course>,
        weekday: Int,
        minuteOfDay: Int,
        ongoingNos: List<String>,
    ): String? {
        return courses
            .filter { it.schedule.containsKey(weekday) && it.courseNo !in ongoingNos }
            .mapNotNull { course ->
                val firstFutureMinute = course.schedule[weekday]!!
                    .mapNotNull { pid -> parseHm(AppConstants.PeriodTimes.mapping[pid]?.first) }
                    .filter { it > minuteOfDay }
                    .minOrNull()
                firstFutureMinute?.let { course to it }
            }
            .minByOrNull { it.second }
            ?.first?.courseNo
    }

    /**
     * Scans up to 7 future weekdays for the earliest scheduled class. Mirrors
     * iOS `WidgetTimelineDerivation.derive`'s `tomorrowFirst` branch — without
     * the 7-day lookahead, viewing the widget on Friday with no Sat/Sun classes
     * collapses to "no more classes" instead of surfacing Monday's first class.
     */
    private fun computeTomorrowFirst(
        courses: List<Course>,
        todayWeekday: Int,
    ): TomorrowFirst? {
        val order = AppConstants.Periods.chronologicalOrder
        for (offset in 1..7) {
            val target = ((todayWeekday - 1 + offset) % 7) + 1
            val candidates = courses.mapNotNull { course ->
                val periods = course.schedule[target] ?: return@mapNotNull null
                val firstPeriod = periods.minByOrNull { order.indexOf(it) } ?: return@mapNotNull null
                Triple(course, target, firstPeriod)
            }
            val pick = candidates.minByOrNull { order.indexOf(it.third) } ?: continue
            return TomorrowFirst(
                courseNo = pick.first.courseNo,
                weekday = pick.second,
                periodId = pick.third,
            )
        }
        return null
    }
}
