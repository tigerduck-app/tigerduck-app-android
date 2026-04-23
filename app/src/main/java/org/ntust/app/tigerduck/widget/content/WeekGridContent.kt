package org.ntust.app.tigerduck.widget.content

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
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
import org.ntust.app.tigerduck.data.model.Course
import org.ntust.app.tigerduck.widget.WidgetColors
import org.ntust.app.tigerduck.widget.WidgetState
import org.ntust.app.tigerduck.widget.widgetCourseColor

@Composable
fun WeekGridContent(state: WidgetState, colors: WidgetColors, tapAction: Action) {
    val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")
    val periodColWidth = 32.dp
    val outerPadding = 6.dp
    val headerHeight = 22.dp
    val headerBottomPad = 4.dp

    val widgetHeight = LocalSize.current.height
    val usableHeight = (widgetHeight - outerPadding * 2 - headerHeight - headerBottomPad)
        .coerceAtLeast(40.dp)
    val rowCount = state.activePeriodIds.size.coerceAtLeast(1)
    val cellHeight: Dp = (usableHeight.value / rowCount).dp.coerceAtLeast(20.dp)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(colors.background))
            .padding(outerPadding)
            .clickable(tapAction),
    ) {
        if (!state.isLoggedIn || state.courses.isEmpty()) {
            Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (!state.isLoggedIn) "請先登入 TigerDuck" else "尚無課程",
                    style = TextStyle(
                        color = ColorProvider(colors.onSurfaceVariant),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            return@Column
        }

        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(headerHeight)
                .padding(bottom = headerBottomPad),
        ) {
            Box(modifier = GlanceModifier.width(periodColWidth)) {}
            state.activeWeekdays.forEach { day ->
                Box(
                    modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = dayNames[day - 1],
                        style = TextStyle(
                            color = ColorProvider(colors.highlight),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }
        }

        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Column(modifier = GlanceModifier.width(periodColWidth)) {
                state.activePeriodIds.forEach { periodId ->
                    PeriodLabelCell(periodId, colors, cellHeight)
                }
            }
            state.activeWeekdays.forEach { weekday ->
                val runs = buildRuns(state.activePeriodIds, state.courses, weekday)
                Column(modifier = GlanceModifier.defaultWeight().padding(horizontal = 1.dp)) {
                    runs.forEach { run ->
                        RunCell(
                            run = run,
                            colors = colors,
                            ongoingCourseNo = state.ongoingCourseNo,
                            blockHeight = cellHeight * run.length,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeriodLabelCell(periodId: String, colors: WidgetColors, cellHeight: Dp) {
    val startTime = AppConstants.PeriodTimes.mapping[periodId]?.first ?: ""
    Column(
        modifier = GlanceModifier.fillMaxWidth().height(cellHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = periodId,
            style = TextStyle(
                color = ColorProvider(colors.onSurface),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
        )
        Text(
            text = startTime,
            style = TextStyle(
                color = ColorProvider(colors.onSurfaceVariant),
                fontSize = 9.sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

private data class Run(val courses: List<Course>, val length: Int)

@Composable
private fun RunCell(
    run: Run,
    colors: WidgetColors,
    ongoingCourseNo: String?,
    blockHeight: Dp,
) {
    if (run.courses.isEmpty()) {
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(blockHeight)
                .padding(vertical = 1.dp),
        ) {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(colors.emptyCell))
                    .cornerRadius(4.dp),
            ) {}
        }
        return
    }

    if (run.courses.size == 1) {
        val course = run.courses.first()
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(blockHeight)
                .padding(vertical = 1.dp),
        ) {
            CourseTile(
                course = course,
                colors = colors,
                ongoing = course.courseNo == ongoingCourseNo,
                maxLines = if (run.length >= 2) 3 else 2,
                compact = false,
            )
        }
        return
    }

    // 衝堂 — two courses in the same period. Split side-by-side; cap at 2.
    val shown = run.courses.take(2)
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(blockHeight)
            .padding(vertical = 1.dp),
    ) {
        shown.forEachIndexed { index, course ->
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .fillMaxHeight()
                    .padding(end = if (index == 0 && shown.size > 1) 1.dp else 0.dp),
            ) {
                CourseTile(
                    course = course,
                    colors = colors,
                    ongoing = course.courseNo == ongoingCourseNo,
                    maxLines = 2,
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun CourseTile(
    course: Course,
    colors: WidgetColors,
    ongoing: Boolean,
    maxLines: Int,
    compact: Boolean,
) {
    val baseColor = widgetCourseColor(course, colors.isDark)
    val cellBg: Color = when {
        ongoing -> colors.highlight
        colors.isDark -> baseColor
        else -> baseColor.copy(alpha = 0.55f)
    }
    val textColor: Color = when {
        ongoing -> Color.White
        colors.isDark -> Color.White
        else -> Color(0xFF1C1C1E)
    }
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(cellBg))
            .cornerRadius(6.dp)
            .padding(if (compact) 1.dp else 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = course.courseName,
            style = TextStyle(
                color = ColorProvider(textColor),
                fontSize = if (compact) 9.sp else 10.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
            maxLines = maxLines,
        )
    }
}

private fun buildRuns(
    activePeriodIds: List<String>,
    courses: List<Course>,
    weekday: Int,
): List<Run> {
    val out = mutableListOf<Run>()
    var i = 0
    while (i < activePeriodIds.size) {
        val slot = coursesAt(courses, weekday, activePeriodIds[i])
        when {
            slot.size >= 2 -> {
                // Conflict — do not merge with neighbors.
                out.add(Run(slot, 1))
                i++
            }
            slot.size == 1 -> {
                val course = slot.single()
                var j = i + 1
                while (j < activePeriodIds.size) {
                    val nextSlot = coursesAt(courses, weekday, activePeriodIds[j])
                    if (nextSlot.size == 1 && nextSlot.single().courseNo == course.courseNo) j++
                    else break
                }
                out.add(Run(listOf(course), j - i))
                i = j
            }
            else -> {
                var j = i + 1
                while (j < activePeriodIds.size &&
                    coursesAt(courses, weekday, activePeriodIds[j]).isEmpty()
                ) j++
                out.add(Run(emptyList(), j - i))
                i = j
            }
        }
    }
    return out
}

private fun coursesAt(courses: List<Course>, weekday: Int, periodId: String): List<Course> =
    courses.filter { it.schedule[weekday]?.contains(periodId) == true }
