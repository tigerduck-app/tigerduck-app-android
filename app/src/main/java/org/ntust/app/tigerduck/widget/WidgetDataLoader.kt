package org.ntust.app.tigerduck.widget

import android.content.Context
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.academic.QuietDays
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.shared.computeOngoingCourses
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.parseHm
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.theme.buildCourseColorAssignments
import java.time.LocalDate
import java.util.Calendar

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun dataCache(): DataCache
    fun authService(): AuthService
    fun appPreferences(): AppPreferences
    fun academicCalendar(): org.ntust.app.tigerduck.academic.AcademicCalendarStore
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

        // Classes do not meet on a school holiday, nor before 開學 — 選課 fills
        // the timetable weeks ahead of the term, so having courses is not
        // evidence that classes have started. Either way the "now / next"
        // derivations go quiet, which is what the Next-class widget renders,
        // and the Today list empties off [WidgetState.isQuietToday].
        // `courses` is deliberately left alone: the Week grid shows the
        // timetable itself, which stays useful on a day off.
        val quietDays = entry.academicCalendar().quietDays()
        val today = AppClock.localDateTime().toLocalDate()
        val quietToday = today in quietDays

        val ongoing =
            if (quietToday) emptyList() else computeOngoingBlocks(courses, weekday, minuteOfDay)
        val nextToday = if (quietToday) null else computeNextTodayBlock(
            courses, weekday, minuteOfDay, ongoing.map { it.course.courseNo },
        )
        val tomorrowFirst = computeTomorrowFirst(courses, today, quietDays)

        val courseColors = buildCourseColorAssignments(courses)
        val customCount = courses.count { it.customColorHex != null }
        Log.d("WidgetData", "[load] ${courses.size} courses, $customCount with customColorHex, ${courseColors.size} color assignments")
        courses.filter { it.customColorHex != null }.forEach { c ->
            Log.d("WidgetData", "[load] ${c.courseNo}: customHex=${c.customColorHex} resolved=${courseColors[c.courseNo]}")
        }

        return WidgetState(
            courses = courses,
            activeWeekdays = computeActiveWeekdays(courses),
            activePeriodIds = computeActivePeriodIds(courses),
            currentWeekday = weekday,
            currentMinuteOfDay = minuteOfDay,
            isLoggedIn = isLoggedIn,
            isQuietToday = quietToday,
            ongoingBlocks = ongoing,
            nextTodayBlock = nextToday,
            tomorrowFirstCourseNo = tomorrowFirst?.courseNo,
            tomorrowFirstCourseWeekday = tomorrowFirst?.weekday,
            tomorrowFirstCoursePeriodId = tomorrowFirst?.periodId,
            courseColors = courseColors,
            courseNameScale = entry.appPreferences().courseNameScale,
        )
    }

    internal data class TomorrowFirst(
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

    /** The block each course is in at [minuteOfDay] on [weekday], for the courses going on. */
    internal fun computeOngoingBlocks(
        courses: List<Course>,
        weekday: Int,
        minuteOfDay: Int,
    ): List<CourseBlock> =
        computeOngoingCourses(courses, weekday, minuteOfDay)
            .map { CourseBlock(it.course, weekday, it.periods) }

    /**
     * The block starting soonest after [minuteOfDay] today, from the courses
     * not in [ongoingNos]. A block, not a course: after a course's morning
     * block its afternoon one is next, starting at its own first period.
     */
    internal fun computeNextTodayBlock(
        courses: List<Course>,
        weekday: Int,
        minuteOfDay: Int,
        ongoingNos: List<String>,
    ): CourseBlock? =
        courses
            .filter { it.courseNo !in ongoingNos }
            .flatMap { CourseBlock.allOf(it, weekday) }
            .mapNotNull { block ->
                parseHm(block.startTime)?.takeIf { it > minuteOfDay }?.let { block to it }
            }
            .minByOrNull { it.second }
            ?.first

    /**
     * Scans up to 7 future days for the earliest scheduled class. Mirrors
     * iOS `WidgetTimelineDerivation.derive`'s `tomorrowFirst` branch — without
     * the 7-day lookahead, viewing the widget on Friday with no Sat/Sun classes
     * collapses to "no more classes" instead of surfacing Monday's first class.
     *
     * A day in [quietDays] is passed over wherever it falls in the scan: on a
     * Friday evening before a holiday Monday the user has not opted back into,
     * the class to show is Tuesday's.
     */
    internal fun computeTomorrowFirst(
        courses: List<Course>,
        today: LocalDate,
        quietDays: QuietDays,
    ): TomorrowFirst? {
        val order = AppConstants.Periods.chronologicalOrder
        for (offset in 1L..7L) {
            val day = today.plusDays(offset)
            if (day in quietDays) continue
            val target = day.dayOfWeek.value
            val candidates = courses.mapNotNull { course ->
                val periods = course.schedule[target] ?: return@mapNotNull null
                val firstPeriod =
                    periods.minByOrNull { order.indexOf(it) } ?: return@mapNotNull null
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
