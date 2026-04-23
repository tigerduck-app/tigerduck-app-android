package org.ntust.app.tigerduck.widget.content

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.data.parseHm
import org.ntust.app.tigerduck.widget.WidgetColors
import org.ntust.app.tigerduck.widget.WidgetState

@Composable
fun NextClassContent(state: WidgetState, colors: WidgetColors, tapAction: Action) {
    val height = LocalSize.current.height
    val compact = height < 90.dp

    if (compact) CompactLayout(state, colors, tapAction)
    else FullLayout(state, colors, tapAction)
}

@Composable
private fun CompactLayout(state: WidgetState, colors: WidgetColors, tapAction: Action) {
    val order = AppConstants.Periods.chronologicalOrder
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(colors.background))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clickable(tapAction),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        when {
            !state.isLoggedIn -> {
                Text(
                    text = "請先登入 TigerDuck",
                    style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 14.sp),
                )
            }

            state.ongoingCourseNo != null -> {
                val course = state.courses.find { it.courseNo == state.ongoingCourseNo } ?: return@Row
                val periods = course.schedule[state.currentWeekday]?.sortedBy { order.indexOf(it) } ?: emptyList()
                val endTime = periods.lastOrNull()?.let { AppConstants.PeriodTimes.mapping[it]?.second } ?: ""
                Box(
                    modifier = GlanceModifier
                        .background(ColorProvider(colors.highlight))
                        .cornerRadius(10.dp)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "進行中",
                        style = TextStyle(color = ColorProvider(Color.White), fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    )
                }
                Spacer(GlanceModifier.width(8.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = course.courseName,
                        style = TextStyle(color = ColorProvider(colors.onSurface), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                    Text(
                        text = buildString {
                            if (endTime.isNotEmpty()) append("至 $endTime")
                            if (course.classroom.isNotEmpty()) {
                                if (isNotEmpty()) append("  ")
                                append(course.classroom)
                            }
                        },
                        style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 12.sp),
                        maxLines = 1,
                    )
                }
            }

            state.nextCourseTodayNo != null -> {
                val course = state.courses.find { it.courseNo == state.nextCourseTodayNo } ?: return@Row
                val periods = course.schedule[state.currentWeekday]?.sortedBy { order.indexOf(it) } ?: emptyList()
                val startTime = periods.firstOrNull()?.let { AppConstants.PeriodTimes.mapping[it]?.first } ?: ""
                Text(
                    text = "下一堂",
                    style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 12.sp, fontWeight = FontWeight.Bold),
                )
                Spacer(GlanceModifier.width(8.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = course.courseName,
                        style = TextStyle(color = ColorProvider(colors.onSurface), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                    Text(
                        text = buildString {
                            if (startTime.isNotEmpty()) append(startTime)
                            if (course.classroom.isNotEmpty()) {
                                if (isNotEmpty()) append("  ")
                                append(course.classroom)
                            }
                        },
                        style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 12.sp),
                        maxLines = 1,
                    )
                }
            }

            else -> {
                val name = state.tomorrowFirstCourseName
                val time = state.tomorrowFirstCourseTime
                Text(
                    text = "已下課",
                    style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 12.sp, fontWeight = FontWeight.Bold),
                )
                Spacer(GlanceModifier.width(8.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = name ?: "今日課程已結束",
                        style = TextStyle(color = ColorProvider(colors.onSurface), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                    if (name != null && time != null) {
                        Text(
                            text = "明天 $time",
                            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 12.sp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FullLayout(state: WidgetState, colors: WidgetColors, tapAction: Action) {
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
                    style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 15.sp),
                )
            }

            state.ongoingCourseNo != null -> {
                val course = state.courses.find { it.courseNo == state.ongoingCourseNo }
                if (course != null) OngoingCard(course, state, colors)
            }

            state.nextCourseTodayNo != null -> {
                val course = state.courses.find { it.courseNo == state.nextCourseTodayNo }
                if (course != null) NextCard(course, state, colors)
            }

            else -> TomorrowCard(state, colors)
        }

        Spacer(GlanceModifier.defaultWeight())
    }
}

@Composable
private fun OngoingCard(course: Course, state: WidgetState, colors: WidgetColors) {
    val order = AppConstants.Periods.chronologicalOrder
    val weekday = state.currentWeekday
    val periods = course.schedule[weekday]?.sortedBy { order.indexOf(it) } ?: emptyList()
    val startTime = periods.firstOrNull()?.let { AppConstants.PeriodTimes.mapping[it]?.first } ?: ""
    val endTime = periods.lastOrNull()?.let { AppConstants.PeriodTimes.mapping[it]?.second } ?: ""
    val startMin = parseHm(startTime) ?: 0
    val endMin = parseHm(endTime) ?: 0
    val progress = if (endMin > startMin) {
        ((state.currentMinuteOfDay - startMin).toFloat() / (endMin - startMin))
            .coerceIn(0f, 1f)
    } else 0f
    val periodRange = if (periods.size > 1) "${periods.first()}–${periods.last()}" else periods.firstOrNull() ?: ""

    Box(
        modifier = GlanceModifier
            .background(ColorProvider(colors.highlight))
            .cornerRadius(12.dp)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            text = "進行中",
            style = TextStyle(color = ColorProvider(Color.White), fontSize = 12.sp, fontWeight = FontWeight.Bold),
        )
    }
    Spacer(GlanceModifier.height(6.dp))
    Text(
        text = course.courseName,
        style = TextStyle(
            color = ColorProvider(colors.onSurface),
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
        ),
        maxLines = 2,
    )
    Spacer(GlanceModifier.height(3.dp))
    Text(
        text = "$startTime–$endTime  $periodRange",
        style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 13.sp),
    )
    if (course.classroom.isNotEmpty()) {
        Text(
            text = course.classroom,
            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 13.sp),
        )
    }
    Spacer(GlanceModifier.height(10.dp))
    val widgetWidth = LocalSize.current.width
    val filledWidth = (widgetWidth.value * progress - 24).coerceAtLeast(0f).dp
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(5.dp)
            .background(ColorProvider(colors.emptyCell))
            .cornerRadius(2.5.dp),
    ) {
        Box(
            modifier = GlanceModifier.width(filledWidth).fillMaxHeight()
                .background(ColorProvider(colors.highlight))
                .cornerRadius(2.5.dp),
        ) {}
    }
}

@Composable
private fun NextCard(course: Course, state: WidgetState, colors: WidgetColors) {
    val order = AppConstants.Periods.chronologicalOrder
    val periods = course.schedule[state.currentWeekday]?.sortedBy { order.indexOf(it) } ?: emptyList()
    val startTime = periods.firstOrNull()?.let { AppConstants.PeriodTimes.mapping[it]?.first } ?: ""

    Text(
        text = "下一堂課",
        style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 13.sp, fontWeight = FontWeight.Bold),
    )
    Spacer(GlanceModifier.height(5.dp))
    Text(
        text = course.courseName,
        style = TextStyle(
            color = ColorProvider(colors.onSurface),
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
        ),
        maxLines = 2,
    )
    if (startTime.isNotEmpty()) {
        Text(
            text = startTime,
            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 14.sp),
        )
    }
    if (course.classroom.isNotEmpty()) {
        Text(
            text = course.classroom,
            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 13.sp),
        )
    }
}

@Composable
private fun TomorrowCard(state: WidgetState, colors: WidgetColors) {
    Text(
        text = "今日課程已結束",
        style = TextStyle(
            color = ColorProvider(colors.onSurface),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
    val name = state.tomorrowFirstCourseName
    val time = state.tomorrowFirstCourseTime
    if (name != null && time != null) {
        Spacer(GlanceModifier.height(10.dp))
        Text(
            text = "明天",
            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 13.sp, fontWeight = FontWeight.Bold),
        )
        Text(
            text = name,
            style = TextStyle(color = ColorProvider(colors.onSurface), fontSize = 15.sp),
            maxLines = 1,
        )
        Text(
            text = time,
            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 13.sp),
        )
    }
}
