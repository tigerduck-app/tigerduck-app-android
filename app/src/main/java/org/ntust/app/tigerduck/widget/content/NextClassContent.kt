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
        val ongoing = state.ongoingCourseNos.mapNotNull { no -> state.courses.find { it.courseNo == no } }
        when {
            !state.isLoggedIn -> {
                Text(
                    text = "請先登入 TigerDuck",
                    style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 14.sp),
                )
            }

            ongoing.isNotEmpty() -> {
                val first = ongoing.first()
                val periods = first.schedule[state.currentWeekday]?.sortedBy { order.indexOf(it) } ?: emptyList()
                val endTime = periods.lastOrNull()?.let { AppConstants.PeriodTimes.mapping[it]?.second } ?: ""
                Box(
                    modifier = GlanceModifier
                        .background(ColorProvider(colors.highlight))
                        .cornerRadius(10.dp)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (ongoing.size > 1) "進行中×${ongoing.size}" else "進行中",
                        style = TextStyle(color = ColorProvider(Color.White), fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    )
                }
                Spacer(GlanceModifier.width(8.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = first.courseName,
                        style = TextStyle(color = ColorProvider(colors.onSurface), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                    val second = ongoing.getOrNull(1)
                    Text(
                        text = if (second != null) second.courseName else buildString {
                            if (endTime.isNotEmpty()) append("至 $endTime")
                            if (first.classroom.isNotEmpty()) {
                                if (isNotEmpty()) append("  ")
                                append(first.classroom)
                            }
                        },
                        style = TextStyle(
                            color = ColorProvider(if (second != null) colors.onSurface else colors.onSurfaceVariant),
                            fontSize = if (second != null) 14.sp else 12.sp,
                            fontWeight = if (second != null) FontWeight.Bold else FontWeight.Normal,
                        ),
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
    val ongoing = state.ongoingCourseNos.mapNotNull { no -> state.courses.find { it.courseNo == no } }
    val heightDp = LocalSize.current.height.value
    // Stay at 1.0 up to ~170dp so the default 2-cell-tall widget doesn't
    // inflate past its bounds; only scale up when the user makes it larger.
    val scale = ((heightDp - 170f) / 160f + 1f).coerceIn(1f, 2.2f)
    val pad = (12f * scale).dp
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(colors.background))
            .padding(pad)
            .clickable(tapAction),
    ) {
        when {
            !state.isLoggedIn -> {
                Text(
                    text = "請先登入 TigerDuck",
                    style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = (15f * scale).sp),
                )
            }

            ongoing.size >= 2 -> {
                // Fixed, compact layout so both cards fit in the default
                // 2-cell-tall widget. Scaling intentionally omitted — the
                // two-card case trades extra whitespace for guaranteed fit.
                OngoingMiniCard(ongoing[0], state, colors)
                Spacer(GlanceModifier.height(6.dp))
                OngoingMiniCard(ongoing[1], state, colors)
            }

            ongoing.size == 1 -> OngoingCard(ongoing[0], state, colors, scale)

            state.nextCourseTodayNo != null -> {
                val course = state.courses.find { it.courseNo == state.nextCourseTodayNo }
                if (course != null) NextCard(course, state, colors, scale)
            }

            else -> TomorrowCard(state, colors, scale)
        }
    }
}

@Composable
private fun OngoingCard(course: Course, state: WidgetState, colors: WidgetColors, scale: Float) {
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
            .padding(horizontal = (10f * scale).dp, vertical = (3f * scale).dp),
    ) {
        Text(
            text = "進行中",
            style = TextStyle(color = ColorProvider(Color.White), fontSize = (12f * scale).sp, fontWeight = FontWeight.Bold),
        )
    }
    Spacer(GlanceModifier.height((6f * scale).dp))
    Text(
        text = course.courseName,
        style = TextStyle(
            color = ColorProvider(colors.onSurface),
            fontSize = (19f * scale).sp,
            fontWeight = FontWeight.Bold,
        ),
        maxLines = (2 * scale).toInt().coerceAtLeast(2),
    )
    Spacer(GlanceModifier.height((3f * scale).dp))
    Text(
        text = "$startTime–$endTime  $periodRange",
        style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = (13f * scale).sp),
    )
    if (course.classroom.isNotEmpty()) {
        Text(
            text = course.classroom,
            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = (13f * scale).sp),
        )
    }
    Spacer(GlanceModifier.height((10f * scale).dp))
    val widgetWidth = LocalSize.current.width
    val filledWidth = (widgetWidth.value * progress - 24f * scale).coerceAtLeast(0f).dp
    val barHeight = (5f * scale).dp
    val barRadius = (2.5f * scale).dp
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(barHeight)
            .background(ColorProvider(colors.emptyCell))
            .cornerRadius(barRadius),
    ) {
        Box(
            modifier = GlanceModifier.width(filledWidth).fillMaxHeight()
                .background(ColorProvider(colors.highlight))
                .cornerRadius(barRadius),
        ) {}
    }
}

@Composable
private fun OngoingMiniCard(course: Course, state: WidgetState, colors: WidgetColors) {
    val order = AppConstants.Periods.chronologicalOrder
    val weekday = state.currentWeekday
    val periods = course.schedule[weekday]?.sortedBy { order.indexOf(it) } ?: emptyList()
    val startTime = periods.firstOrNull()?.let { AppConstants.PeriodTimes.mapping[it]?.first } ?: ""
    val endTime = periods.lastOrNull()?.let { AppConstants.PeriodTimes.mapping[it]?.second } ?: ""

    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
        Box(
            modifier = GlanceModifier
                .background(ColorProvider(colors.highlight))
                .cornerRadius(6.dp)
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text(
                text = "進行中",
                style = TextStyle(color = ColorProvider(Color.White), fontSize = 9.sp, fontWeight = FontWeight.Bold),
            )
        }
        Spacer(GlanceModifier.width(5.dp))
        Text(
            text = course.courseName,
            style = TextStyle(
                color = ColorProvider(colors.onSurface),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
    }
    Text(
        text = buildString {
            if (startTime.isNotEmpty() && endTime.isNotEmpty()) append("$startTime–$endTime")
            if (course.classroom.isNotEmpty()) {
                if (isNotEmpty()) append("  ")
                append(course.classroom)
            }
        },
        style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 10.sp),
        maxLines = 1,
    )
}

@Composable
private fun NextCard(course: Course, state: WidgetState, colors: WidgetColors, scale: Float) {
    val order = AppConstants.Periods.chronologicalOrder
    val periods = course.schedule[state.currentWeekday]?.sortedBy { order.indexOf(it) } ?: emptyList()
    val startTime = periods.firstOrNull()?.let { AppConstants.PeriodTimes.mapping[it]?.first } ?: ""

    Text(
        text = "下一堂課",
        style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = (13f * scale).sp, fontWeight = FontWeight.Bold),
    )
    Spacer(GlanceModifier.height((5f * scale).dp))
    Text(
        text = course.courseName,
        style = TextStyle(
            color = ColorProvider(colors.onSurface),
            fontSize = (19f * scale).sp,
            fontWeight = FontWeight.Bold,
        ),
        maxLines = (2 * scale).toInt().coerceAtLeast(2),
    )
    if (startTime.isNotEmpty()) {
        Text(
            text = startTime,
            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = (14f * scale).sp),
        )
    }
    if (course.classroom.isNotEmpty()) {
        Text(
            text = course.classroom,
            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = (13f * scale).sp),
        )
    }
}

@Composable
private fun TomorrowCard(state: WidgetState, colors: WidgetColors, scale: Float) {
    Text(
        text = "今日課程已結束",
        style = TextStyle(
            color = ColorProvider(colors.onSurface),
            fontSize = (16f * scale).sp,
            fontWeight = FontWeight.Bold,
        ),
    )
    val name = state.tomorrowFirstCourseName
    val time = state.tomorrowFirstCourseTime
    if (name != null && time != null) {
        Spacer(GlanceModifier.height((10f * scale).dp))
        Text(
            text = "明天",
            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = (13f * scale).sp, fontWeight = FontWeight.Bold),
        )
        Text(
            text = name,
            style = TextStyle(color = ColorProvider(colors.onSurface), fontSize = (15f * scale).sp),
            maxLines = 1,
        )
        Text(
            text = time,
            style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = (13f * scale).sp),
        )
    }
}
