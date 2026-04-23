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
                Spacer(GlanceModifier.defaultWeight())
                Column(horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
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
                Spacer(GlanceModifier.defaultWeight())
            }
        }
    }
}

@Composable
private fun FullLayout(state: WidgetState, colors: WidgetColors, tapAction: Action) {
    val ongoing = state.ongoingCourseNos.mapNotNull { no -> state.courses.find { it.courseNo == no } }
    val heightDp = LocalSize.current.height.value
    // Widget is resizable between 2-cell (~110dp) and 4-cell (~250dp) heights.
    // A midpoint threshold picks the right variant regardless of whether the
    // widget is at its narrow (2-cell wide) or wide (3-cell wide) size.
    val tall = heightDp >= 180f

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(colors.background))
            .padding(12.dp)
            .clickable(tapAction),
    ) {
        when {
            !state.isLoggedIn -> {
                Text(
                    text = "請先登入 TigerDuck",
                    style = TextStyle(color = ColorProvider(colors.onSurfaceVariant), fontSize = 15.sp),
                )
            }

            ongoing.size >= 2 && tall -> {
                // Two stacked full-size ongoing cards, each owning half the
                // widget height via defaultWeight so the progress bars
                // naturally fall to the bottom of their halves.
                Column(modifier = GlanceModifier.defaultWeight()) {
                    OngoingCard(ongoing[0], state, colors)
                }
                Spacer(GlanceModifier.height(12.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    OngoingCard(ongoing[1], state, colors)
                }
            }

            ongoing.size >= 2 -> {
                // Default 2-cell height: compact mini cards, evenly
                // distributed so the gap above, between, and below matches.
                Spacer(GlanceModifier.defaultWeight())
                OngoingMiniCard(ongoing[0], state, colors)
                Spacer(GlanceModifier.defaultWeight())
                OngoingMiniCard(ongoing[1], state, colors)
                Spacer(GlanceModifier.defaultWeight())
            }

            ongoing.size == 1 -> OngoingCard(ongoing[0], state, colors)

            state.nextCourseTodayNo != null -> {
                val course = state.courses.find { it.courseNo == state.nextCourseTodayNo }
                if (course != null) NextCard(course, state, colors)
            }

            else -> TomorrowCard(state, colors)
        }
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

    Column(modifier = GlanceModifier.fillMaxSize()) {
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
        // Weighted spacer pushes the progress bar to the bottom of whatever
        // vertical space the caller allocates (full widget for the single
        // -ongoing case, half for the tall two-ongoing case).
        Spacer(GlanceModifier.defaultWeight())
        val widgetWidth = LocalSize.current.width
        val filledWidth = (widgetWidth.value * progress - 24f).coerceAtLeast(0f).dp
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
}

@Composable
private fun OngoingMiniCard(course: Course, state: WidgetState, colors: WidgetColors) {
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
    Spacer(GlanceModifier.height(3.dp))
    val widgetWidth = LocalSize.current.width
    val filledWidth = (widgetWidth.value * progress - 24f).coerceAtLeast(0f).dp
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(3.dp)
            .background(ColorProvider(colors.emptyCell))
            .cornerRadius(1.5.dp),
    ) {
        Box(
            modifier = GlanceModifier.width(filledWidth).fillMaxHeight()
                .background(ColorProvider(colors.highlight))
                .cornerRadius(1.5.dp),
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
    Box(
        modifier = GlanceModifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
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
    }
}
