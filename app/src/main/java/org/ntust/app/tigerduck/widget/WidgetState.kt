package org.ntust.app.tigerduck.widget

import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.longPreferencesKey
import org.ntust.app.tigerduck.data.model.Course

data class WidgetState(
    val courses: List<Course>,
    val activeWeekdays: List<Int>,
    val activePeriodIds: List<String>,
    val currentWeekday: Int,
    val currentMinuteOfDay: Int,
    val isLoggedIn: Boolean,
    /**
     * Whether [org.ntust.app.tigerduck.AppConstants.CurrentTerm] is in session.
     *
     * Gates the "today"-scoped widgets — Next Class and Today — which would
     * otherwise announce classes for a term that has not started, since 選課
     * populates the timetable weeks before 開學. The Week grid deliberately
     * ignores this and keeps rendering: it is a reference timetable, matching
     * the class table screen, where the today carousel hides outside the term
     * but the grid stays.
     */
    val isTermInSession: Boolean,
    val ongoingCourseNos: List<String>,
    val nextCourseTodayNo: String?,
    /**
     * `(courseNo, weekday, firstPeriodId)` for the next class beyond today —
     * scans up to 7 future weekdays so the Next Class widget shows Monday's
     * first class when a Fri/Sat/Sun stretch has nothing scheduled (mirrors
     * iOS `WidgetTimelineDerivation.derive`'s `tomorrowFirst` branch). Null
     * when no course exists in the next 7 days.
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
