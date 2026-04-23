package org.ntust.app.tigerduck.widget.content

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.widget.WidgetColors
import org.ntust.app.tigerduck.widget.WidgetState
import org.ntust.app.tigerduck.widget.widgetCourseColor

@Composable
fun WeekGridContent(state: WidgetState, colors: WidgetColors, tapAction: Action) {
    val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")
    val periodLabelWidth = 18.dp
    val cellHeight = 26.dp

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(colors.background))
            .padding(4.dp)
            .clickable(tapAction),
    ) {
        if (!state.isLoggedIn || state.courses.isEmpty()) {
            Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (!state.isLoggedIn) "請先登入 TigerDuck" else "今日沒有課",
                    style = TextStyle(
                        color = ColorProvider(colors.onSurfaceVariant),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            return@Column
        }

        Row(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 2.dp)) {
            Box(modifier = GlanceModifier.width(periodLabelWidth)) {}
            state.activeWeekdays.forEach { day ->
                Box(
                    modifier = GlanceModifier.defaultWeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = dayNames[day - 1],
                        style = TextStyle(
                            color = ColorProvider(colors.onSurface),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }
        }

        state.activePeriodIds.forEach { periodId ->
            Row(
                modifier = GlanceModifier.fillMaxWidth().height(cellHeight),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Box(
                    modifier = GlanceModifier.width(periodLabelWidth).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = periodId,
                        style = TextStyle(
                            color = ColorProvider(colors.onSurfaceVariant),
                            fontSize = 7.sp,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }

                state.activeWeekdays.forEach { weekday ->
                    val course = state.courses.firstOrNull {
                        it.schedule[weekday]?.contains(periodId) == true
                    }
                    val isOngoing = course?.courseNo != null &&
                            course.courseNo == state.ongoingCourseNo
                    val isFirstPeriod = course != null && run {
                        val order = AppConstants.Periods.chronologicalOrder
                        val prevId = order.getOrNull(order.indexOf(periodId) - 1)
                        prevId == null || course.schedule[weekday]?.contains(prevId) != true
                    }
                    val cellBg: Color = when {
                        isOngoing -> colors.highlight
                        course != null -> widgetCourseColor(course, colors.isDark)
                        else -> colors.emptyCell
                    }

                    Box(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight()
                            .padding(1.dp)
                            .background(ColorProvider(cellBg)),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        if (course != null && isFirstPeriod) {
                            Text(
                                text = course.courseName,
                                style = TextStyle(
                                    color = ColorProvider(Color.White),
                                    fontSize = 6.sp,
                                ),
                                maxLines = 2,
                                modifier = GlanceModifier.padding(1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
