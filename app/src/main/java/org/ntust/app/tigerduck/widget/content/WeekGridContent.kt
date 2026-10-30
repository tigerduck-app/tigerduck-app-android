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
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.coursechanges.CourseMeetings
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableThisWeek
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy.ClassTableGridStyle
import org.ntust.app.tigerduck.widget.CourseBlock
import org.ntust.app.tigerduck.widget.LayerCourse
import org.ntust.app.tigerduck.widget.LocalWidgetScale
import org.ntust.app.tigerduck.widget.ScheduleCell
import org.ntust.app.tigerduck.widget.WidgetColors
import org.ntust.app.tigerduck.widget.WidgetScale
import org.ntust.app.tigerduck.widget.WidgetState
import org.ntust.app.tigerduck.widget.buildScheduleCells
import org.ntust.app.tigerduck.widget.isOngoingTile
import org.ntust.app.tigerduck.widget.renderConflictLayer
import org.ntust.app.tigerduck.widget.scaled
import org.ntust.app.tigerduck.widget.widgetChangeInk
import org.ntust.app.tigerduck.widget.widgetCourseColor

/**
 * The Week widget: the class table's grid, drawn in the interface style as the
 * class table is — blocks, or under TAT a pastel tile per period, the period
 * without its time, bare empty slots and banded rows.
 */
@Composable
fun WeekGridContent(
    state: WidgetState,
    colors: WidgetColors,
    style: VisualStylePolicy,
    tapAction: Action,
) {
    val context = LocalContext.current
    val dayNames = listOf(
        context.getString(R.string.weekday_mon_short),
        context.getString(R.string.weekday_tue_short),
        context.getString(R.string.weekday_wed_short),
        context.getString(R.string.weekday_thu_short),
        context.getString(R.string.weekday_fri_short),
        context.getString(R.string.weekday_sat_short),
        context.getString(R.string.weekday_sun_short),
    )
    val showsPeriodTimes = style.classTableShowsPeriodTimes
    // Narrower where the column holds only the period, growing with a large
    // font as the class table's does, so "10" stays on one line.
    // Each held to its text where text sizes it, at the widget text size too.
    val scale = LocalWidgetScale.current
    val periodColWidth = if (showsPeriodTimes) {
        32.dp * scale.textBox
    } else {
        maxOf(20.dp.scaled, (16 * context.resources.configuration.fontScale * scale.text).dp)
    }
    val outerPadding = 6.dp.scaled
    val headerHeight = 22.dp * scale.textBox
    val headerBottomPad = 4.dp.scaled

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

    val showsGrid = state.isLoggedIn && state.courses.isNotEmpty()

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(colors.background))
            .clickable(tapAction),
    ) {
        // Behind the grid and outside its padding, so the bands run from edge
        // to edge, as on the class table.
        if (showsGrid) {
            WeekRowBands(
                style = style,
                onSurface = colors.onSurface,
                rowCount = state.activePeriodIds.size,
                top = outerPadding + headerHeight,
                cellHeight = cellHeight,
            )
        }
        Column(modifier = GlanceModifier.fillMaxSize().padding(outerPadding)) {
            if (!showsGrid) {
                Box(
                    modifier = GlanceModifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (!state.isLoggedIn) {
                            context.getString(R.string.widget_sign_in)
                        } else {
                            context.getString(R.string.widget_no_courses)
                        },
                        style = TextStyle(
                            color = ColorProvider(colors.onSurfaceVariant),
                            fontSize = 14.sp.scaled,
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
                                fontSize = 12.sp.scaled,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            ),
                        )
                    }
                }
            }

            Row(modifier = GlanceModifier.fillMaxWidth()) {
                GlanceStack(
                    items = state.activePeriodIds,
                    modifier = GlanceModifier.width(periodColWidth),
                ) { periodId ->
                    PeriodLabelCell(periodId, colors, cellHeight, showsPeriodTimes)
                }
                state.activeWeekdays.forEach { weekday ->
                    val cells = buildScheduleCells(state.activePeriodIds, state.courses, weekday)
                    // The row each cell starts at, for the periods its tiles cover.
                    val cellStarts = cells.runningFold(0) { row, cell -> row + cell.length }
                    GlanceStack(
                        items = cells.zip(cellStarts),
                        modifier = GlanceModifier.defaultWeight().padding(horizontal = 1.dp.scaled),
                    ) { (cell, start) ->
                        ScheduleCellBox(
                            cell = cell,
                            colors = colors,
                            style = style,
                            courseColors = state.courseColors,
                            // Per tile, not per course: only the block going
                            // on now lights up, not the course's other blocks.
                            isOngoing = { course, offset, span ->
                                state.isOngoingTile(
                                    course,
                                    weekday,
                                    state.activePeriodIds.rows(start + offset, span),
                                )
                            },
                            changeOf = { course, offset, span ->
                                weekBlockChange(state, course, weekday, state.activePeriodIds.rows(start + offset, span))
                            },
                            blockHeight = cellHeight * cell.length,
                            blockWidth = dayColWidth,
                            courseNameScale = state.courseNameScale,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Whether a tile of a course is a block going on now, from the course, the
 * row the tile starts at within its cell and the rows it spans.
 */
private typealias TileOngoing = (course: Course, offset: Int, span: Int) -> Boolean

/** A tile's block as the school changed it, from the same; see [weekBlockChange]. */
private typealias TileChange = (course: Course, offset: Int, span: Int) -> CourseBlock?

/** The period ids of [span] rows from row [start]. */
private fun List<String>.rows(start: Int, span: Int): List<String> =
    subList(start.coerceIn(0, size), (start + span).coerceIn(0, size))

/**
 * The bands behind the grid's [rowCount] period rows, where the style has
 * them: each one row high and the widget's whole width, from [top] down.
 */
@Composable
private fun WeekRowBands(
    style: VisualStylePolicy,
    onSurface: Color,
    rowCount: Int,
    top: Dp,
    cellHeight: Dp,
) {
    val bands = List(rowCount) { style.classTableRowBand(it, onSurface) }
    if (bands.all { it == null }) return
    // The offset is padding rather than a spacer: a spacer would be one more
    // child, and Glance draws only a container's first ten.
    GlanceStack(
        items = bands,
        modifier = GlanceModifier.fillMaxWidth().padding(top = top),
    ) { band ->
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(cellHeight)
                .background(ColorProvider(band ?: Color.Transparent)),
        ) {}
    }
}

/** The most children a Glance Row, Column or Box draws; it drops the rest. */
private const val GLANCE_MAX_CHILDREN = 10

/**
 * A column of one child per item, which, past [GLANCE_MAX_CHILDREN] items,
 * nests them in runs of that many so none is dropped: a grid of more than ten
 * periods would otherwise lose its last rows, and a course run of more than
 * ten its last period tiles.
 */
@Composable
private fun <T> GlanceStack(
    items: List<T>,
    modifier: GlanceModifier,
    content: @Composable (T) -> Unit,
) {
    Column(modifier = modifier) {
        if (items.size <= GLANCE_MAX_CHILDREN) {
            items.forEach { content(it) }
        } else {
            items.chunked(GLANCE_MAX_CHILDREN).forEach { run ->
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    run.forEach { content(it) }
                }
            }
        }
    }
}

@Composable
private fun PeriodLabelCell(
    periodId: String,
    colors: WidgetColors,
    cellHeight: Dp,
    showsTime: Boolean,
) {
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
                fontSize = 12.sp.scaled,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
        )
        if (showsTime) {
            Text(
                text = startTime,
                style = TextStyle(
                    color = ColorProvider(colors.onSurfaceVariant),
                    fontSize = 9.sp.scaled,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

@Composable
private fun ScheduleCellBox(
    cell: ScheduleCell,
    colors: WidgetColors,
    style: VisualStylePolicy,
    courseColors: Map<String, Color>,
    isOngoing: TileOngoing,
    changeOf: TileChange,
    blockHeight: Dp,
    blockWidth: Dp,
    courseNameScale: Float,
) {
    when (cell) {
        is ScheduleCell.Empty -> EmptyCell(blockHeight, colors, style)
        // Solo blocks only, as on iOS: a 衝堂 cluster keeps the weekly look.
        is ScheduleCell.Solo -> SoloCell(
            course = cell.course,
            length = cell.length,
            ongoing = isOngoing(cell.course, 0, cell.length),
            change = changeOf(cell.course, 0, cell.length),
            colors = colors,
            style = style,
            courseColors = courseColors,
            blockHeight = blockHeight,
            courseNameScale = courseNameScale,
        )

        is ScheduleCell.Conflict -> ConflictCell(
            cell = cell,
            colors = colors,
            style = style,
            courseColors = courseColors,
            isOngoing = isOngoing,
            blockHeight = blockHeight,
            blockWidth = blockWidth,
            courseNameScale = courseNameScale,
        )

        is ScheduleCell.MultiConflict -> MultiConflictCell(
            cell = cell,
            colors = colors,
            style = style,
            courseColors = courseColors,
            isOngoing = isOngoing,
            blockHeight = blockHeight,
            courseNameScale = courseNameScale,
        )
    }
}

@Composable
private fun EmptyCell(blockHeight: Dp, colors: WidgetColors, style: VisualStylePolicy) {
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(blockHeight)
            .padding(vertical = 1.dp.scaled),
    ) {
        // Left bare under a style that leaves empty slots bare, as on the class table.
        if (style.classTableGridStyle == ClassTableGridStyle.Blocks) {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(colors.emptyCell))
                    .cornerRadius(4.dp.scaled),
            ) {}
        }
    }
}

@Composable
private fun SoloCell(
    course: Course,
    length: Int,
    ongoing: Boolean,
    /** The block as the school changed it on its column's date; null for the timetable's. */
    change: CourseBlock?,
    colors: WidgetColors,
    style: VisualStylePolicy,
    courseColors: Map<String, Color>,
    blockHeight: Dp,
    courseNameScale: Float,
) {
    val cancelled = change?.isCancelled == true
    val cellBg = weekCourseFill(course, ongoing, colors, style, courseColors, cancelled)
    // A cancelled class steps back, as on the iPhone; it is never in session.
    val textColor = if (cancelled) colors.onSurfaceVariant else weekCourseTextColor(ongoing, colors, style)
    // Only a block of more than one period has room to say what changed, as on iOS.
    val lines = weekBlockLines(
        change.takeIf { length > 1 },
        cancelledLabel = LocalContext.current.getString(R.string.course_card_cancelled),
        ink = weekChangeInk(ongoing, colors, style),
        stepBack = colors.onSurfaceVariant,
    )
    when (style.classTableGridStyle) {
        ClassTableGridStyle.Blocks -> CourseTile(
            name = course.displayName,
            fill = cellBg,
            textColor = textColor,
            height = blockHeight,
            cornerRadius = 6.dp.scaled,
            maxLines = ((if (length >= 2) 3 else 2) - lines.count).coerceAtLeast(1),
            courseNameScale = courseNameScale,
            struckThrough = cancelled,
            topLine = lines.top,
            bottomLine = lines.bottom,
        )

        // One tile per period, each naming the course, as on the class table.
        // The change goes on the first tile and the last, where they fit it.
        ClassTableGridStyle.PeriodTiles -> {
            val tileHeight = blockHeight / length
            val fontScale = LocalContext.current.resources.configuration.fontScale
            val nameLines = tileNameLines(tileHeight, courseNameScale, fontScale, LocalWidgetScale.current)
            val fits = nameLines >= 2
            GlanceStack(
                items = List(length) { it },
                modifier = GlanceModifier.fillMaxWidth().height(blockHeight),
            ) { index ->
                val top = lines.top.takeIf { fits && index == 0 }
                val bottom = lines.bottom.takeIf { fits && index == length - 1 }
                CourseTile(
                    name = course.displayName,
                    fill = cellBg,
                    textColor = textColor,
                    height = tileHeight,
                    // A period tile's corners, tighter than a block's.
                    cornerRadius = 4.dp.scaled,
                    maxLines = nameLines - listOfNotNull(top, bottom).size,
                    courseNameScale = courseNameScale,
                    struckThrough = cancelled,
                    topLine = top,
                    bottomLine = bottom,
                )
            }
        }
    }
}

/** A short line on a Week block besides its name, such as its changed time or room. */
internal data class TileLine(val text: String, val color: Color)

/** What a Week block says of [change] above its name and below it. */
internal data class WeekBlockLines(val top: TileLine?, val bottom: TileLine?) {
    val count: Int get() = listOfNotNull(top, bottom).size
}

/**
 * The lines a changed Week block adds, as iOS `WeekGridView.courseBlock`: a
 * changed time above the name in [ink]; below it the cancellation in
 * [stepBack] or a moved room after `⇄` in [ink]. None for no [change].
 */
internal fun weekBlockLines(change: CourseBlock?, cancelledLabel: String, ink: Color, stepBack: Color): WeekBlockLines {
    if (change == null) return WeekBlockLines(null, null)
    if (change.isCancelled) return WeekBlockLines(null, TileLine(cancelledLabel, stepBack))
    return WeekBlockLines(
        top = change.changedTimeLabel?.let { TileLine(it, ink) },
        bottom = change.takeIf { it.roomChanged }?.let { TileLine(it.roomLabel, ink) },
    )
}

/**
 * A course's name on its own fill, [height] high, with the 1dp above and below
 * that every cell keeps; struck through for a cancelled class, between a
 * [topLine] and a [bottomLine] where the school changed it.
 */
@Composable
private fun CourseTile(
    name: String,
    fill: Color,
    textColor: Color,
    height: Dp,
    cornerRadius: Dp,
    maxLines: Int,
    courseNameScale: Float,
    struckThrough: Boolean = false,
    topLine: TileLine? = null,
    bottomLine: TileLine? = null,
) {
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(height)
            .padding(vertical = 1.dp.scaled),
    ) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(fill))
                .cornerRadius(cornerRadius)
                .padding(2.dp.scaled),
            contentAlignment = Alignment.Center,
        ) {
            val nameText = @Composable {
                Text(
                    text = name,
                    style = TextStyle(
                        color = ColorProvider(textColor),
                        fontSize = 10.sp.scaled * courseNameScale,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        textDecoration = if (struckThrough) TextDecoration.LineThrough else null,
                    ),
                    maxLines = maxLines.coerceAtLeast(1),
                )
            }
            if (topLine == null && bottomLine == null) {
                nameText()
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    topLine?.let { TileLineText(it) }
                    nameText()
                    bottomLine?.let { TileLineText(it) }
                }
            }
        }
    }
}

@Composable
private fun TileLineText(line: TileLine) {
    Text(
        text = line.text,
        style = TextStyle(
            color = ColorProvider(line.color),
            fontSize = 8.sp.scaled,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        ),
        maxLines = 1,
    )
}

@Composable
private fun ConflictCell(
    cell: ScheduleCell.Conflict,
    colors: WidgetColors,
    style: VisualStylePolicy,
    courseColors: Map<String, Color>,
    isOngoing: TileOngoing,
    blockHeight: Dp,
    blockWidth: Dp,
    courseNameScale: Float,
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    // The L-shapes' corners, at the widget display size as the other tiles' are.
    val shapeDensity = density * LocalWidgetScale.current.ui
    val widthPx = ((blockWidth - 2.dp.scaled).value * density).toInt().coerceAtLeast(1)
    val heightPx = (blockHeight.value * density).toInt().coerceAtLeast(1)

    val ongoingA = isOngoing(cell.courseA, cell.offsetA, cell.spanA)
    val ongoingB = isOngoing(cell.courseB, cell.offsetB, cell.spanB)
    // If both conflicting courses are currently ongoing, painting both halves
    // with the single highlight color merges them into a solid rectangle and
    // hides the L-shape. Keep them in their own course colors in that case —
    // the "進行中" status is surfaced by the NextClass widget anyway.
    val bothOngoing = ongoingA && ongoingB
    val highlightA = ongoingA && !bothOngoing
    val highlightB = ongoingB && !bothOngoing

    // renderConflictLayer allocates ARGB_8888 bitmaps and runs Canvas paths —
    // without `remember` every Glance recomposition (class-boundary ticks,
    // sync completes, theme flips) would re-allocate and re-draw both layers
    // and inflate the RemoteViews payload toward the Binder 1 MB limit.
    val fillA = weekCourseFill(cell.courseA, highlightA, colors, style, courseColors)
    val fillB = weekCourseFill(cell.courseB, highlightB, colors, style, courseColors)
    val bitmapA = remember(cell, widthPx, heightPx, shapeDensity, fillA) {
        renderConflictLayer(
            clusterWidthPx = widthPx,
            clusterHeightPx = heightPx,
            densityFactor = shapeDensity,
            cell = cell,
            course = LayerCourse.A,
            fillColor = fillA,
        )
    }
    val bitmapB = remember(cell, widthPx, heightPx, shapeDensity, fillB) {
        renderConflictLayer(
            clusterWidthPx = widthPx,
            clusterHeightPx = heightPx,
            densityFactor = shapeDensity,
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
            .padding(vertical = 1.dp.scaled),
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
                courseName = cell.courseA.displayName,
                textColor = weekCourseTextColor(highlightA, colors, style),
                alignment = Alignment.TopEnd,
                boxTopPadding = rowHeight * cell.offsetA,
                boxBottomPadding = rowHeight * (cell.combinedSpan - cell.offsetA - cell.spanA),
                barWidth = barWidth,
                courseNameScale = courseNameScale,
            )
            // Course B label — pinned inside B's own Box at bottom-left (mirror-L's bottom bar).
            ConflictLabel(
                courseName = cell.courseB.displayName,
                textColor = weekCourseTextColor(highlightB, colors, style),
                alignment = Alignment.BottomStart,
                boxTopPadding = rowHeight * cell.offsetB,
                boxBottomPadding = rowHeight * (cell.combinedSpan - cell.offsetB - cell.spanB),
                barWidth = barWidth,
                courseNameScale = courseNameScale,
            )
        }
    }
}

/**
 * Renders a 3+ course cluster as side-by-side lane columns. Each lane stacks
 * its members vertically with empty gaps preserved as transparent spacers, so
 * the in-app class-table's MultiConflictStart layout is mirrored on widgets
 * without needing a 3-tile bitmap path.
 */
@Composable
private fun MultiConflictCell(
    cell: ScheduleCell.MultiConflict,
    colors: WidgetColors,
    style: VisualStylePolicy,
    courseColors: Map<String, Color>,
    isOngoing: TileOngoing,
    blockHeight: Dp,
    courseNameScale: Float,
) {
    val rowHeight = blockHeight / cell.combinedSpan.coerceAtLeast(1)

    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(blockHeight)
            .padding(vertical = 1.dp.scaled),
    ) {
        Row(modifier = GlanceModifier.fillMaxSize()) {
            for (laneIdx in 0 until cell.laneCount) {
                val laneMembers = cell.members
                    .filter { it.lane == laneIdx }
                    .sortedBy { it.offset }
                Column(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .fillMaxHeight()
                        .padding(horizontal = 0.5.dp.scaled),
                ) {
                    var cursor = 0
                    laneMembers.forEach { member ->
                        if (member.offset > cursor) {
                            Box(modifier = GlanceModifier.height(rowHeight * (member.offset - cursor))) {}
                        }
                        val ongoing = isOngoing(member.course, member.offset, member.span)
                        val tileFill = weekCourseFill(member.course, ongoing, colors, style, courseColors)
                        val tileTextColor = weekCourseTextColor(ongoing, colors, style)
                        Box(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .height(rowHeight * member.span)
                                .padding(vertical = 0.5.dp.scaled),
                        ) {
                            Box(
                                modifier = GlanceModifier
                                    .fillMaxSize()
                                    .background(ColorProvider(tileFill))
                                    .cornerRadius(4.dp.scaled)
                                    .padding(1.dp.scaled),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = member.course.displayName,
                                    style = TextStyle(
                                        color = ColorProvider(tileTextColor),
                                        fontSize = 9.sp.scaled * courseNameScale,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.Center,
                                    ),
                                    maxLines = if (member.span >= 2) 3 else 2,
                                )
                            }
                        }
                        cursor = member.offset + member.span
                    }
                    if (cursor < cell.combinedSpan) {
                        Box(modifier = GlanceModifier.height(rowHeight * (cell.combinedSpan - cursor))) {}
                    }
                }
            }
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
    courseNameScale: Float,
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
                .padding(horizontal = 2.dp.scaled, vertical = 2.dp.scaled),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = courseName,
                style = TextStyle(
                    color = ColorProvider(textColor),
                    fontSize = 9.sp.scaled * courseNameScale,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 2,
            )
        }
    }
}

/**
 * A course's fill on the Week widget: the highlight while the class is on,
 * else the class table's pastel under TAT, else the widget's own tint, a
 * little stronger in light mode than the class table's; fainter for a block
 * the school [cancelled].
 */
internal fun weekCourseFill(
    course: Course,
    highlighted: Boolean,
    colors: WidgetColors,
    style: VisualStylePolicy,
    courseColors: Map<String, Color>,
    cancelled: Boolean = false,
): Color {
    if (highlighted) return colors.highlight
    val themed = widgetCourseColor(course, courseColors, colors.isDark)
    val fill = when (style.classTableGridStyle) {
        ClassTableGridStyle.Blocks -> if (colors.isDark) themed else themed.copy(alpha = 0.55f)
        ClassTableGridStyle.PeriodTiles -> style.classTableCourseFill(
            courseColor = widgetCourseColor(course, courseColors, isDark = false),
            themeColor = themed,
            isDark = colors.isDark,
        )
    }
    // Two fifths of it, as the iPhone's 0.1 to 0.25 and the class table's cancelled block.
    return if (cancelled) fill.copy(alpha = fill.alpha * 0.4f) else fill
}

/** A course's name on [weekCourseFill]. */
internal fun weekCourseTextColor(
    highlighted: Boolean,
    colors: WidgetColors,
    style: VisualStylePolicy,
): Color = if (highlighted) colors.onHighlight else style.classTableCourseTextColor(colors.isDark)

/**
 * How many lines of a course name fit a period tile [height] high, from one up
 * to two: a second line that does not fit is cut through the middle, on every
 * tile of the run. The name is 10sp, scaled by [courseNameScale] and the
 * system [fontScale] and the widget text size, inside the tile's 1dp above
 * and below and 2dp padding at the widget display size;
 * a line of Chinese, which most course names are, takes about 1.45 times
 * its size.
 */
internal fun tileNameLines(height: Dp, courseNameScale: Float, fontScale: Float, scale: WidgetScale = WidgetScale()): Int {
    val textHeight = height.value - (2 * 1f + 2 * 2f) * scale.ui
    val lineHeight = 10f * courseNameScale * fontScale * scale.text * 1.45f
    return (textHeight / lineHeight).toInt().coerceIn(1, 2)
}

/**
 * The ink a Week block marks the school's change in: on the highlight, the
 * text on it, as the Today list's full-color row; on a pastel, which stays
 * light in either theme, the light ink; else the theme's.
 */
internal fun weekChangeInk(highlighted: Boolean, colors: WidgetColors, style: VisualStylePolicy): Color = when {
    highlighted -> colors.onHighlight
    style.classTableGridStyle == ClassTableGridStyle.PeriodTiles -> widgetChangeInk(isDark = false)
    else -> widgetChangeInk(colors.isDark)
}

/**
 * The block of [course] over [periods] in the [weekday] column as the school
 * changed it: the changed meeting that starts at the block's first period on
 * the column's date, the week running from today, whole and cancelled only
 * when every period is. A tile that spans a hidden period draws two meetings
 * as one, and merging them would lose the first one's changed end. Null where
 * nothing a block shows changed, notes alone included, and for a state with no
 * [WidgetState.today]. As iOS `WeekGridView`'s look-up of the snapshot's
 * `meetingChanges`.
 */
internal fun weekBlockChange(state: WidgetState, course: Course, weekday: Int, periods: List<String>): CourseBlock? {
    val today = state.today ?: return null
    val date = ClassTableThisWeek.columnDate(weekday, today)
    val meeting = CourseMeetings.changedMeetings(course, date, state.courseChanges)
        .firstOrNull { it.periods.firstOrNull() == periods.firstOrNull() } ?: return null
    return CourseBlock.of(meeting).takeIf { it.isCancelled || it.roomChanged || it.timeChanged }
}
