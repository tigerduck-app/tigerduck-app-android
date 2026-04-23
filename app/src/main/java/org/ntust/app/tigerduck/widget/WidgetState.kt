package org.ntust.app.tigerduck.widget

import org.ntust.app.tigerduck.data.model.Course

data class WidgetState(
    val courses: List<Course>,
    val activeWeekdays: List<Int>,
    val activePeriodIds: List<String>,
    val currentWeekday: Int,
    val currentMinuteOfDay: Int,
    val isLoggedIn: Boolean,
    val ongoingCourseNo: String?,
    val nextCourseTodayNo: String?,
    val tomorrowFirstCourseName: String?,
    val tomorrowFirstCourseTime: String?,
)
