package org.ntust.app.tigerduck.widget

import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.longPreferencesKey
import org.ntust.app.tigerduck.shared.Course

data class WidgetState(
    val courses: List<Course>,
    val activeWeekdays: List<Int>,
    val activePeriodIds: List<String>,
    val currentWeekday: Int,
    val currentMinuteOfDay: Int,
    val isLoggedIn: Boolean,
    /**
     * Whether no class meets today: the school calendar says the term is not
     * in session — see [org.ntust.app.tigerduck.academic.AcademicCalendar.isInSession]
     * — or today is a holiday the user has not opted back into — see
     * [org.ntust.app.tigerduck.academic.AcademicCalendar.suppressesClasses].
     *
     * Empties the Today widget, which would otherwise list classes for a term
     * that has not started, since 選課 populates the timetable weeks before
     * 開學, or for a day off. The Next Class fields are already empty on such
     * a day. The Week grid deliberately ignores this and keeps rendering: it
     * is a reference timetable, matching the class table screen, where the
     * today carousel hides outside the term but the grid stays.
     */
    val isQuietToday: Boolean,
    /**
     * The blocks going on now, one per course: the block, not the course, so
     * a course meeting twice today shows the times, periods and room of the
     * block it is in, and only that block is highlighted.
     */
    val ongoingBlocks: List<CourseBlock>,
    /** The next block to start today, from a course not going on now. */
    val nextTodayBlock: CourseBlock?,
    /**
     * `(courseNo, weekday, firstPeriodId)` for the next class beyond today —
     * scans up to 7 future days so the Next Class widget shows Monday's
     * first class when a Fri/Sat/Sun stretch has nothing scheduled (mirrors
     * iOS `WidgetTimelineDerivation.derive`'s `tomorrowFirst` branch), passing
     * over quiet days. Null when no class meets in the next 7 days.
     */
    val tomorrowFirstCourseNo: String?,
    val tomorrowFirstCourseWeekday: Int?,
    val tomorrowFirstCoursePeriodId: String?,
    /**
     * Resolved course colors matching the app's palette assignment (with
     * collision probing). Keyed by courseNo. Empty entries fall back to the
     * hash-based palette color inside [widgetCourseColor].
     */
    val courseColors: Map<String, Color>,
    /**
     * User-tunable multiplier (0.8…1.6, default 1.0) applied to course-name
     * `fontSize` in every widget. Pulled from
     * [org.ntust.app.tigerduck.data.preferences.AppPreferences.courseNameScale]
     * at load time so the widget render and the in-app class table stay in
     * sync — [org.ntust.app.tigerduck.ui.AppState] requests a widget refresh
     * whenever the slider moves.
     */
    val courseNameScale: Float,
) {
    companion object {
        /**
         * Monotonic refresh token stored in each widget's Glance preferences.
         * Bumping it from [WidgetUpdater.updateAll] forces every widget's
         * composition to recompose and reload its state from disk. Reading it
         * inside the composable via `currentState(TickKey)` is what makes the
         * recomposition observable — without this handshake, Glance reuses
         * the stale captured state from the widget's initial `provideGlance`.
         */
        val TickKey = longPreferencesKey("widget_refresh_tick")
    }
}

/** Whether [block] is one of the blocks going on now. */
fun WidgetState.isOngoing(block: CourseBlock): Boolean =
    ongoingBlocks.any { it.isSameBlock(block) }

/**
 * Whether the Week grid's tile of [course] covering [tilePeriods] on [weekday]
 * holds a block going on now: today's column only, and only the tile of the
 * block in progress, not the course's other blocks that day or other days.
 *
 * An overlap rather than a match on the first period, because the grid has
 * rows only for [WidgetState.activePeriodIds]: with period 5 hidden, a course
 * on 4 and 6 is one tile in the grid but two blocks in the schedule.
 */
fun WidgetState.isOngoingTile(course: Course, weekday: Int, tilePeriods: List<String>): Boolean =
    weekday == currentWeekday && ongoingBlocks.any { block ->
        block.course.courseNo == course.courseNo &&
                block.weekday == weekday &&
                block.periods.any { it in tilePeriods }
    }
