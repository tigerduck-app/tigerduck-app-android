package org.ntust.app.tigerduck.widget.content

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.widget.WidgetColors
import org.ntust.app.tigerduck.widget.WidgetState

@Composable
fun TodayListContent(state: WidgetState, colors: WidgetColors, tapAction: Action) {
    val context = LocalContext.current
    val today = state.currentWeekday
    val dayNames = listOf(
        context.getString(R.string.weekday_mon_short),
        context.getString(R.string.weekday_tue_short),
        context.getString(R.string.weekday_wed_short),
        context.getString(R.string.weekday_thu_short),
        context.getString(R.string.weekday_fri_short),
        context.getString(R.string.weekday_sat_short),
        context.getString(R.string.weekday_sun_short),
    )
    val isWeekend = today == 6 || today == 7
    val order = AppConstants.Periods.chronologicalOrder

    val todayCourses = state.courses
        .filter { it.schedule.containsKey(today) }
        .sortedBy { course ->
            course.schedule[today]!!
                .minByOrNull { order.indexOf(it) }
                ?.let { order.indexOf(it) } ?: Int.MAX_VALUE
        }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(colors.background))
            .padding(8.dp)
            .clickable(tapAction),
    ) {
        Text(
            text = if (today in 1..7) {
                context.getString(R.string.widget_today_weekday_title, dayNames[today - 1])
            } else {
                context.getString(R.string.widget_today_schedule_title)
            },
            style = TextStyle(
                color = ColorProvider(colors.onSurface),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            ),
            modifier = GlanceModifier.padding(bottom = 6.dp),
        )

        when {
            !state.isLoggedIn -> {
                Box(
                    modifier = GlanceModifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = context.getString(R.string.widget_sign_in),
                        style = TextStyle(
                            color = ColorProvider(colors.onSurfaceVariant),
                            fontSize = 14.sp,
                        ),
                    )
                }
            }

            todayCourses.isEmpty() && isWeekend -> {
                Text(
                    text = context.getString(R.string.widget_no_classes_weekend),
                    style = TextStyle(
                        color = ColorProvider(colors.onSurfaceVariant),
                        fontSize = 14.sp
                    ),
                )
            }

            todayCourses.isEmpty() -> {
                Text(
                    text = context.getString(R.string.widget_no_classes_today),
                    style = TextStyle(
                        color = ColorProvider(colors.onSurfaceVariant),
                        fontSize = 14.sp
                    ),
                )
            }

            else -> {
                todayCourses.forEach { course ->
                    val isOngoing = course.courseNo in state.ongoingCourseNos
                    val periods = course.schedule[today]!!.sortedBy { order.indexOf(it) }
                    val firstPeriod = periods.first()
                    val lastPeriod = periods.last()
                    val startTime = AppConstants.PeriodTimes.mapping[firstPeriod]?.first ?: ""
                    val endTime = AppConstants.PeriodTimes.mapping[lastPeriod]?.second ?: ""
                    val periodRange = if (firstPeriod == lastPeriod) firstPeriod
                    else "$firstPeriod–$lastPeriod"
                    val rowBg = if (isOngoing) colors.highlight else colors.surface
                    val primaryText = if (isOngoing) Color.White else colors.onSurface
                    val secondaryText =
                        if (isOngoing) Color.White.copy(alpha = 0.8f) else colors.onSurfaceVariant

                    Row(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .background(ColorProvider(rowBg))
                            .cornerRadius(4.dp)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                    ) {
                        Column(modifier = GlanceModifier.width(70.dp)) {
                            Text(
                                text = periodRange,
                                style = TextStyle(
                                    color = ColorProvider(primaryText),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                            )
                            Text(
                                text = "$startTime–$endTime",
                                style = TextStyle(
                                    color = ColorProvider(secondaryText),
                                    fontSize = 11.sp,
                                ),
                            )
                        }
                        Column(
                            modifier = GlanceModifier.defaultWeight().padding(start = 6.dp),
                        ) {
                            Text(
                                text = course.courseName,
                                style = TextStyle(
                                    color = ColorProvider(primaryText),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                maxLines = 1,
                            )
                            if (course.classroom.isNotEmpty()) {
                                Text(
                                    text = course.classroom,
                                    style = TextStyle(
                                        color = ColorProvider(secondaryText),
                                        fontSize = 11.sp,
                                    ),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
