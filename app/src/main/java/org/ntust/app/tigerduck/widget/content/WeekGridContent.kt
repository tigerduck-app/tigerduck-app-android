package org.ntust.app.tigerduck.widget.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
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
import org.ntust.app.tigerduck.widget.LayerCourse
import org.ntust.app.tigerduck.widget.ScheduleCell
import org.ntust.app.tigerduck.widget.WidgetColors
import org.ntust.app.tigerduck.widget.WidgetState
import org.ntust.app.tigerduck.widget.buildScheduleCells
import org.ntust.app.tigerduck.widget.renderConflictLayer
import org.ntust.app.tigerduck.widget.widgetCourseColor

@Composable
fun WeekGridContent(state: WidgetState, colors: WidgetColors, tapAction: Action) {
    val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")
    val periodColWidth = 32.dp
    val outerPadding = 6.dp
    val headerHeight = 22.dp
    val headerBottomPad = 4.dp

    val widgetSize = LocalSize.current
    val widgetHeight = widgetSize.height
    val widgetWidth = widgetSize.width
    val usableHeight = (widgetHeight - outerPadding * 2 - headerHeight - headerBottomPad)
        .coerceAtLeast(40.dp)
    val rowCount = state.activePeriodIds.size.coerceAtLeast(1)
    val cellHeight: Dp = (usableHeight.value / rowCount).dp.coerceAtLeast(20.dp)
    val dayColWidth: Dp = run {
        val numDays = state.activeWeekdays.size.coerceAtLeast(1)
        ((widgetWidth - outerPadding * 2 - periodColWidth).value / numDays).dp
            .coerceAtLeast(20.dp)
    }

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
                val cells = buildScheduleCells(state.activePeriodIds, state.courses, weekday)
                Column(modifier = GlanceModifier.defaultWeight().padding(horizontal = 1.dp)) {
                    cells.forEach { cell ->
                        ScheduleCellBox(
                            cell = cell,
                            colors = colors,
                            courseColors = state.courseColors,
                            ongoingCourseNos = state.ongoingCourseNos,
                            blockHeight = cellHeight * cell.length,
                            blockWidth = dayColWidth,
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

@Composable
private fun ScheduleCellBox(
    cell: ScheduleCell,
    colors: WidgetColors,
    courseColors: Map<String, Color>,
    ongoingCourseNos: List<String>,
    blockHeight: Dp,
    blockWidth: Dp,
) {
    when (cell) {
        is ScheduleCell.Empty -> EmptyCell(blockHeight, colors)
        is ScheduleCell.Solo -> SoloCell(
            course = cell.course,
            length = cell.length,
            ongoing = cell.course.courseNo in ongoingCourseNos,
            colors = colors,
            courseColors = courseColors,
            blockHeight = blockHeight,
        )
        is ScheduleCell.Conflict -> ConflictCell(
            cell = cell,
            colors = colors,
            courseColors = courseColors,
            ongoingCourseNos = ongoingCourseNos,
            blockHeight = blockHeight,
            blockWidth = blockWidth,
        )
    }
}

@Composable
private fun EmptyCell(blockHeight: Dp, colors: WidgetColors) {
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
}

@Composable
private fun SoloCell(
    course: Course,
    length: Int,
    ongoing: Boolean,
    colors: WidgetColors,
    courseColors: Map<String, Color>,
    blockHeight: Dp,
) {
    val baseColor = widgetCourseColor(course, courseColors, colors.isDark)
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
            .fillMaxWidth()
            .height(blockHeight)
            .padding(vertical = 1.dp),
    ) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(cellBg))
                .cornerRadius(6.dp)
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = course.courseName,
                style = TextStyle(
                    color = ColorProvider(textColor),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                ),
                maxLines = if (length >= 2) 3 else 2,
            )
        }
    }
}

@Composable
private fun ConflictCell(
    cell: ScheduleCell.Conflict,
    colors: WidgetColors,
    courseColors: Map<String, Color>,
    ongoingCourseNos: List<String>,
    blockHeight: Dp,
    blockWidth: Dp,
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val widthPx = ((blockWidth - 2.dp).value * density).toInt().coerceAtLeast(1)
    val heightPx = (blockHeight.value * density).toInt().coerceAtLeast(1)

    val ongoingA = cell.courseA.courseNo in ongoingCourseNos
    val ongoingB = cell.courseB.courseNo in ongoingCourseNos
    // If both conflicting courses are currently ongoing, painting both halves
    // with the single highlight color merges them into a solid rectangle and
    // hides the L-shape. Keep them in their own course colors in that case —
    // the "進行中" status is surfaced by the NextClass widget anyway.
    val bothOngoing = ongoingA && ongoingB

    fun tileBg(course: Course, ongoing: Boolean): Color {
        val base = widgetCourseColor(course, courseColors, colors.isDark)
        return when {
            ongoing && !bothOngoing -> colors.highlight
            colors.isDark -> base
            else -> base.copy(alpha = 0.55f)
        }
    }
    val textColor: Color = if (colors.isDark) Color.White else Color(0xFF1C1C1E)

    // renderConflictLayer allocates ARGB_8888 bitmaps and runs Canvas paths —
    // without `remember` every Glance recomposition (class-boundary ticks,
    // sync completes, theme flips) would re-allocate and re-draw both layers
    // and inflate the RemoteViews payload toward the Binder 1 MB limit.
    val fillA = tileBg(cell.courseA, ongoingA)
    val fillB = tileBg(cell.courseB, ongoingB)
    val bitmapA = remember(cell, widthPx, heightPx, density, fillA) {
        renderConflictLayer(
            clusterWidthPx = widthPx,
            clusterHeightPx = heightPx,
            densityFactor = density,
            cell = cell,
            course = LayerCourse.A,
            fillColor = fillA,
        )
    }
    val bitmapB = remember(cell, widthPx, heightPx, density, fillB) {
        renderConflictLayer(
            clusterWidthPx = widthPx,
            clusterHeightPx = heightPx,
            densityFactor = density,
            cell = cell,
            course = LayerCourse.B,
            fillColor = fillB,
        )
    }

    val rowHeight = blockHeight / cell.combinedSpan.coerceAtLeast(1)
    val barWidth = (blockWidth.value * (1f - 0.28f)).dp
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(blockHeight)
            .padding(vertical = 1.dp),
    ) {
        Box(modifier = GlanceModifier.fillMaxSize()) {
            Image(
                provider = ImageProvider(bitmapA),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
            Image(
                provider = ImageProvider(bitmapB),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
            // Course A label — pinned inside A's own Box at top-right (Γ's top bar).
            ConflictLabel(
                courseName = cell.courseA.courseName,
                textColor = if (ongoingA && !bothOngoing) Color.White else textColor,
                alignment = Alignment.TopEnd,
                boxTopPadding = rowHeight * cell.offsetA,
                boxBottomPadding = rowHeight * (cell.combinedSpan - cell.offsetA - cell.spanA),
                barWidth = barWidth,
            )
            // Course B label — pinned inside B's own Box at bottom-left (mirror-L's bottom bar).
            ConflictLabel(
                courseName = cell.courseB.courseName,
                textColor = if (ongoingB && !bothOngoing) Color.White else textColor,
                alignment = Alignment.BottomStart,
                boxTopPadding = rowHeight * cell.offsetB,
                boxBottomPadding = rowHeight * (cell.combinedSpan - cell.offsetB - cell.spanB),
                barWidth = barWidth,
            )
        }
    }
}

@Composable
private fun ConflictLabel(
    courseName: String,
    textColor: Color,
    alignment: Alignment,
    boxTopPadding: Dp,
    boxBottomPadding: Dp,
    barWidth: Dp,
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(top = boxTopPadding, bottom = boxBottomPadding),
        contentAlignment = alignment,
    ) {
        Box(
            modifier = GlanceModifier
                .width(barWidth)
                .padding(horizontal = 2.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = courseName,
                style = TextStyle(
                    color = ColorProvider(textColor),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 2,
            )
        }
    }
}
