package org.ntust.app.tigerduck.widget.content

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.action.Action
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.data.parseHm
import org.ntust.app.tigerduck.widget.WidgetColors
import org.ntust.app.tigerduck.widget.WidgetState

@Composable
fun NextClassContent(state: WidgetState, colors: WidgetColors, tapAction: Action) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(colors.background))
            .padding(12.dp)
            .clickable(tapAction),
    ) {
        Spacer(GlanceModifier.defaultWeight())

        when {
            !state.isLoggedIn -> {
                Text(
                    text = "請先登入 TigerDuck",
                    style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 12.sp),
                )
            }

            state.ongoingCourseNo != null -> {
                val course = state.courses.find { it.courseNo == state.ongoingCourseNo }
                if (course != null) {
                    val weekday = state.currentWeekday
                    val order = AppConstants.Periods.chronologicalOrder
                    val periods = course.schedule[weekday]?.sortedBy { order.indexOf(it) } ?: emptyList()
                    val startTime = periods.firstOrNull()
                        ?.let { AppConstants.PeriodTimes.mapping[it]?.first } ?: ""
                    val endTime = periods.lastOrNull()
                        ?.let { AppConstants.PeriodTimes.mapping[it]?.second } ?: ""
                    val startMin = parseHm(startTime) ?: 0
                    val endMin = parseHm(endTime) ?: 0
                    val progress = if (endMin > startMin) {
                        ((state.currentMinuteOfDay - startMin).toFloat() / (endMin - startMin))
                            .coerceIn(0f, 1f)
                    } else 0f
                    val periodRange = if (periods.size > 1)
                        "${periods.first()}–${periods.last()}" else periods.firstOrNull() ?: ""

                    Box(
                        modifier = GlanceModifier
                            .background(ColorProvider(colors.highlight))
                            .cornerRadius(12.dp)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "進行中",
                            style = TextStyle(color = ColorProvider(Color.White), fontSize = 9.sp),
                        )
                    }
                    Spacer(GlanceModifier.height(6.dp))
                    Text(
                        text = course.courseName,
                        style = TextStyle(
                            color = ColorProvider(colors.onSurface),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 2,
                    )
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        text = "$startTime–$endTime  $periodRange",
                        style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 10.sp),
                    )
                    if (course.classroom.isNotEmpty()) {
                        Text(
                            text = course.classroom,
                            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 10.sp),
                        )
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    val widgetWidth = LocalSize.current.width
                    val filledWidth = (widgetWidth.value * progress - 24).coerceAtLeast(0f).dp
                    Row(
                        modifier = GlanceModifier.fillMaxWidth().height(4.dp)
                            .background(ColorProvider(colors.emptyCell))
                            .cornerRadius(2.dp),
                    ) {
                        Box(
                            modifier = GlanceModifier.width(filledWidth).fillMaxHeight()
                                .background(ColorProvider(colors.highlight))
                                .cornerRadius(2.dp),
                        ) {}
                    }
                }
            }

            state.nextCourseTodayNo != null -> {
                val course = state.courses.find { it.courseNo == state.nextCourseTodayNo }
                if (course != null) {
                    val weekday = state.currentWeekday
                    val order = AppConstants.Periods.chronologicalOrder
                    val periods = course.schedule[weekday]?.sortedBy { order.indexOf(it) } ?: emptyList()
                    val startTime = periods.firstOrNull()
                        ?.let { AppConstants.PeriodTimes.mapping[it]?.first } ?: ""

                    Text(
                        text = "下一堂課",
                        style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 10.sp),
                    )
                    Spacer(GlanceModifier.height(4.dp))
                    Text(
                        text = course.courseName,
                        style = TextStyle(
                            color = ColorProvider(colors.onSurface),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 2,
                    )
                    if (startTime.isNotEmpty()) {
                        Text(
                            text = startTime,
                            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 11.sp),
                        )
                    }
                    if (course.classroom.isNotEmpty()) {
                        Text(
                            text = course.classroom,
                            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 10.sp),
                        )
                    }
                }
            }

            else -> {
                Text(
                    text = "今日課程已結束",
                    style = TextStyle(
                        color = ColorProvider(colors.onSurface),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                val name = state.tomorrowFirstCourseName
                val time = state.tomorrowFirstCourseTime
                if (name != null && time != null) {
                    Spacer(GlanceModifier.height(10.dp))
                    Text(
                        text = "明天",
                        style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 10.sp),
                    )
                    Text(
                        text = name,
                        style = TextStyle(color = ColorProvider(colors.onSurface), fontSize = 12.sp),
                        maxLines = 1,
                    )
                    Text(
                        text = time,
                        style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 10.sp),
                    )
                }
            }
        }

        Spacer(GlanceModifier.defaultWeight())
    }
}
