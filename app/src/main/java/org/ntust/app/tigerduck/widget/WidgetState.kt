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
    val ongoingCourseNos: List<String>,
    val nextCourseTodayNo: String?,
    val tomorrowFirstCourseName: String?,
    val tomorrowFirstCourseTime: String?,
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
