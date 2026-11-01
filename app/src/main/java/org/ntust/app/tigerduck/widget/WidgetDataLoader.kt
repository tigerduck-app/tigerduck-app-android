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
import org.ntust.app.tigerduck.shared.time.TaipeiClock
import org.ntust.app.tigerduck.shared.timeline.ClassTimeline
import org.ntust.app.tigerduck.ui.theme.buildCourseColorAssignments
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun dataCache(): DataCache
    fun authService(): AuthService
    fun appPreferences(): AppPreferences
    fun academicCalendar(): org.ntust.app.tigerduck.academic.AcademicCalendarStore
    fun courseChangeStore(): CourseChangeStore
    fun moodleColorStore(): org.ntust.app.tigerduck.data.MoodleColorStore
}

object WidgetDataLoader {

    suspend fun load(context: Context): WidgetState {
        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java,
        )
        // The colors of courses that take Moodle's are read from it.
        entry.moodleColorStore().ensureLoaded()
        val courses = entry.dataCache().loadCourses()
        // A course the background sync added gets its color here too.
        org.ntust.app.tigerduck.data.MoodleColorBook.shared.reconcile(courses)
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

        // The widgets read the clock to the minute, so a class is on from its
        // start minute and over from its end minute whatever second a redraw lands on.
        val minute = now.truncatedTo(ChronoUnit.MINUTES).atZone(TaipeiClock.zone).toInstant()
        val ongoing = if (quietToday) emptyList() else computeOngoingBlocks(courses, minute, changes)
        val nextToday = if (quietToday) null else computeNextTodayBlock(
            courses, minute, ongoing.map { it.course.courseNo }, changes,
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
     * The block each course is in at [now], for the courses going on, in the
     * courses' order. A course in two classes at once shows the first. A class
     * the school cancelled is not going on, and one it moved runs at its new
     * times. [now] is to the minute, as the widgets draw it.
     */
    internal fun computeOngoingBlocks(
        courses: List<Course>,
        now: Instant,
        changes: CourseChanges,
    ): List<CourseBlock> {
        val running = ClassTimeline.ongoing(courses, now, changes)
        return courses.mapNotNull { course ->
            running.firstOrNull { it.course === course }?.let(CourseBlock::of)
        }
    }

    /**
     * The block starting soonest after [now] today, from the courses not in
     * [ongoingNos]. A block, not a course: after a course's morning block its
     * afternoon one is next, starting at its own first period. A cancelled
     * class is passed over and a moved one starts at its new start, as iOS
     * `WidgetTimelineDerivation.derive`'s `nextToday`.
     */
    internal fun computeNextTodayBlock(
        courses: List<Course>,
        now: Instant,
        ongoingNos: List<String>,
        changes: CourseChanges,
    ): CourseBlock? =
        ClassTimeline.next(courses.filter { it.courseNo !in ongoingNos }, now, changes, daysAhead = 0)
            ?.let(CourseBlock::of)

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
            // Stays on CourseBlock rather than ClassTimeline: this names a course, weekday and first
            // period, not a slot with a start, and a course in a period with no known time is still
            // offered (ClassTimeline's slots drop it), so a day's first is picked by start with
            // such a course last.
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
