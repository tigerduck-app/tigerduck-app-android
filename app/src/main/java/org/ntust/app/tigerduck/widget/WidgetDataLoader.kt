package org.ntust.app.tigerduck.widget

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.cache.DataCache
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
        val isLoggedIn = entry.authService().isNtustAuthenticated

        val cal = Calendar.getInstance(AppConstants.TAIPEI_TZ)
        val weekday = cal.toWeekday()
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        val ongoingInfos = computeOngoingCourses(courses, weekday, minuteOfDay)
        val ongoingNos = ongoingInfos.map { it.course.courseNo }
        val nextCourseTodayNo = computeNextCourseTodayNo(
            courses, weekday, minuteOfDay, ongoingNos,
        )
        val (tomorrowName, tomorrowTime) = computeTomorrowFirst(courses, weekday)

        return WidgetState(
            courses = courses,
            activeWeekdays = computeActiveWeekdays(courses),
            activePeriodIds = computeActivePeriodIds(courses),
            currentWeekday = weekday,
            currentMinuteOfDay = minuteOfDay,
            isLoggedIn = isLoggedIn,
            ongoingCourseNos = ongoingNos,
            nextCourseTodayNo = nextCourseTodayNo,
            tomorrowFirstCourseName = tomorrowName,
            tomorrowFirstCourseTime = tomorrowTime,
            courseColors = buildCourseColorAssignments(courses),
        )
    }

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

    private fun computeTomorrowFirst(
        courses: List<Course>,
        todayWeekday: Int,
    ): Pair<String?, String?> {
        val tomorrowWeekday = if (todayWeekday >= 7) 1 else todayWeekday + 1
        val order = AppConstants.Periods.chronologicalOrder
        val course = courses
            .filter { it.schedule.containsKey(tomorrowWeekday) }
            .minByOrNull { c ->
                c.schedule[tomorrowWeekday]!!
                    .minByOrNull { order.indexOf(it) }
                    ?.let { order.indexOf(it) } ?: Int.MAX_VALUE
            } ?: return null to null
        val firstPeriodId = course.schedule[tomorrowWeekday]!!
            .minByOrNull { order.indexOf(it) }!!
        return course.courseName to AppConstants.PeriodTimes.mapping[firstPeriodId]?.first
    }
}
