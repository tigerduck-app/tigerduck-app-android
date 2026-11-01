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
import org.ntust.app.tigerduck.coursechanges.CourseChangeStore
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.parseHm
import org.ntust.app.tigerduck.ui.theme.buildCourseColorAssignments
import java.time.LocalDate
import java.time.LocalDateTime

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun dataCache(): DataCache
    fun authService(): AuthService
    fun appPreferences(): AppPreferences
    fun academicCalendar(): org.ntust.app.tigerduck.academic.AcademicCalendarStore
    fun courseChangeStore(): CourseChangeStore
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
        val state = state(
            courses = courses,
            now = AppClock.localDateTime(),
            isLoggedIn = isLoggedIn,
            quietDays = entry.academicCalendar().quietDays(),
            changes = entry.courseChangeStore().current(),
            courseNameScale = entry.appPreferences().courseNameScale,
        )
        val customCount = courses.count { it.customColorHex != null }
        Log.d("WidgetData", "[load] ${courses.size} courses, $customCount with customColorHex, ${state.courseColors.size} color assignments")
        courses.filter { it.customColorHex != null }.forEach { c ->
            Log.d("WidgetData", "[load] ${c.courseNo}: customHex=${c.customColorHex} resolved=${state.courseColors[c.courseNo]}")
        }
        return state
    }

    /**
     * What the widgets draw at [now], Taipei time, from the timetable as the
     * school's [changes] leave it: today's classes and the next days' as the
     * classes they split into, cancelled ones passed over for now and next.
     */
    internal fun state(
        courses: List<Course>,
        now: LocalDateTime,
        isLoggedIn: Boolean,
        quietDays: QuietDays,
        changes: CourseChanges,
        courseNameScale: Float,
    ): WidgetState {
        val today = now.toLocalDate()
        val weekday = today.dayOfWeek.value
        val minuteOfDay = now.hour * 60 + now.minute

        // Classes do not meet on a school holiday, nor before 開學 — 選課 fills
        // the timetable weeks ahead of the term, so having courses is not
        // evidence that classes have started. Either way the "now / next"
        // derivations go quiet, which is what the Next-class widget renders,
        // and the Today list empties off [WidgetState.isQuietToday].
        // `courses` is deliberately left alone: the Week grid shows the
        // timetable itself, which stays useful on a day off.
        val quietToday = today in quietDays

        val ongoing =
            if (quietToday) emptyList() else computeOngoingBlocks(courses, weekday, minuteOfDay, today, changes)
        val nextToday = if (quietToday) null else computeNextTodayBlock(
            courses, weekday, minuteOfDay, ongoing.map { it.course.courseNo }, today, changes,
        )
        val tomorrowFirst = computeTomorrowFirst(courses, today, quietDays, changes)

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
            courseColors = buildCourseColorAssignments(courses),
            courseNameScale = courseNameScale,
            today = today,
            courseChanges = changes,
        )
    }

    internal data class TomorrowFirst(
        val courseNo: String,
        val weekday: Int,
        val periodId: String,
    )

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

    /**
     * [course]'s classes on [weekday]: on [day], under the school's [changes],
     * cancelled ones included; with no day, the timetable's blocks.
     */
    private fun blocksOf(course: Course, weekday: Int, day: LocalDate?, changes: CourseChanges): List<CourseBlock> =
        if (day == null) CourseBlock.allOf(course, weekday) else CourseBlock.on(course, day, changes)

    /**
     * The block each course is in at [minuteOfDay] on [weekday], for the
     * courses going on. On [day], whose weekday it is, a class the school
     * cancelled is not going on, and one it moved runs at its new times.
     */
    internal fun computeOngoingBlocks(
        courses: List<Course>,
        weekday: Int,
        minuteOfDay: Int,
        day: LocalDate? = null,
        changes: CourseChanges = CourseChanges.EMPTY,
    ): List<CourseBlock> =
        courses.mapNotNull { course ->
            blocksOf(course, weekday, day, changes).firstOrNull { block ->
                val start = parseHm(block.startTime)
                val end = parseHm(block.endTime)
                // Over from its end minute, as iOS `WidgetTimelineDerivation.derive` has it.
                // The redraw at a class's end, the school's changed end included, is the
                // last one before the next boundary, so it must draw the class as over.
                !block.isCancelled && start != null && end != null && minuteOfDay in start until end
            }
        }

    /**
     * The block starting soonest after [minuteOfDay] today, from the courses
     * not in [ongoingNos]. A block, not a course: after a course's morning
     * block its afternoon one is next, starting at its own first period. On
     * [day] a cancelled class is passed over and a moved one starts at its
     * new start, as iOS `WidgetTimelineDerivation.derive`'s `nextToday`.
     */
    internal fun computeNextTodayBlock(
        courses: List<Course>,
        weekday: Int,
        minuteOfDay: Int,
        ongoingNos: List<String>,
        day: LocalDate? = null,
        changes: CourseChanges = CourseChanges.EMPTY,
    ): CourseBlock? =
        courses
            .filter { it.courseNo !in ongoingNos }
            .flatMap { blocksOf(it, weekday, day, changes) }
            .filter { !it.isCancelled }
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
     * the class to show is Tuesday's. So is a class the school's [changes]
     * cancel, and one they move is placed by its new start.
     */
    internal fun computeTomorrowFirst(
        courses: List<Course>,
        today: LocalDate,
        quietDays: QuietDays,
        changes: CourseChanges = CourseChanges.EMPTY,
    ): TomorrowFirst? {
        for (offset in 1L..7L) {
            val day = today.plusDays(offset)
            if (day in quietDays) continue
            val pick = courses
                .mapNotNull { course -> CourseBlock.on(course, day, changes).firstOrNull { !it.isCancelled } }
                .minByOrNull { parseHm(it.startTime) ?: Int.MAX_VALUE }
                ?: continue
            return TomorrowFirst(
                courseNo = pick.course.courseNo,
                weekday = pick.weekday,
                periodId = pick.firstPeriodId,
            )
        }
        return null
    }
}
