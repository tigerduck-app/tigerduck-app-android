package org.ntust.app.tigerduck.widget.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
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
import org.ntust.app.tigerduck.shared.timeline.CourseMeetings
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
import org.ntust.app.tigerduck.widget.ongoingTileProgress
import org.ntust.app.tigerduck.widget.renderConflictLayer
import org.ntust.app.tigerduck.widget.scaled
import org.ntust.app.tigerduck.widget.widgetChangeInk
import org.ntust.app.tigerduck.widget.widgetCourseColor

/**
 * The Week widget: the class table's grid, drawn in the interface style as the
 * class table is — blocks, or under the pastel style a pastel tile per period,
 * the period without its time, bare empty slots and banded rows — under a
 * header with the term's week and today marked, and each day's date on a
 * large widget. A block in session is outlined, with its progress.
 */
@Composable
fun WeekGridContent(
    state: WidgetState,
    colors: WidgetColors,
    style: VisualStylePolicy,
    tapAction: Action,
) {
    val context = LocalContext.current
    val online = onlineRoomWord()
    val showsPeriodTimes = style.classTableShowsPeriodTimes
    // Narrower where the column holds only the period, growing with a large
    // font as the class table's does, so "10" stays on one line.
    // Each held to its text where text sizes it, at the widget text size too.
    val scale = LocalWidgetScale.current
    val periodColWidth = if (showsPeriodTimes) {
        32.dp * scale.textBox
    } else {
        maxOf(20.dp.scaled, (16 * WidgetScale.cappedFontScale(context.resources.configuration.fontScale) * scale.text).dp)
    }
    val outerPadding = 6.dp.scaled
    val widgetSize = LocalSize.current
    val widgetHeight = widgetSize.height
    val widgetWidth = widgetSize.width
    val showsDates = state.today != null && (widgetHeight >= 440.dp * scale.textBox || widgetWidth >= 480.dp * scale.textBox)
    val headerHeight = (if (showsDates) 36.dp else 22.dp) * scale.textBox
    val headerBottomPad = 4.dp.scaled
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

            WeekHeaderRow(state, colors, periodColWidth, headerHeight, headerBottomPad, showsDates)

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
                                state.isOngoingTile(course, weekday, state.activePeriodIds.rows(start + offset, span))
                            },
                            progressOf = { course, offset, span ->
                                state.ongoingTileProgress(course, weekday, state.activePeriodIds.rows(start + offset, span))
                            },
                            spokenOf = { course, offset, span ->
                                val periods = state.activePeriodIds.rows(start + offset, span)
                                val change = weekBlockChange(state, course, weekday, periods)
                                weekBlockSpoken(context, course, weekday, periods, change, weekBlockRoom(course, weekday, periods), online)
                            },
                            changeOf = { course, offset, span ->
                                weekBlockChange(state, course, weekday, state.activePeriodIds.rows(start + offset, span))
                            },
                            roomOf = { course, offset, span ->
                                weekBlockRoom(course, weekday, state.activePeriodIds.rows(start + offset, span))
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

/** How far through a tile's block in session is, from the same; null for one not in session. */
private typealias TileProgressOf = (course: Course, offset: Int, span: Int) -> Float?

/** What TalkBack reads for a tile, from the same; see [weekBlockSpoken]. */
private typealias TileSpokenOf = (course: Course, offset: Int, span: Int) -> String

/** A tile's block as the school changed it, from the same; see [weekBlockChange]. */
private typealias TileChange = (course: Course, offset: Int, span: Int) -> CourseBlock?

/** A tile's room as the timetable has it, from the same; see [weekBlockRoom]. */
private typealias TileRoom = (course: Course, offset: Int, span: Int) -> String

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
    progressOf: TileProgressOf,
    spokenOf: TileSpokenOf,
    changeOf: TileChange,
    roomOf: TileRoom,
    blockHeight: Dp,
    blockWidth: Dp,
    courseNameScale: Float,
) {
    when (cell) {
        is ScheduleCell.Empty -> EmptyCell(blockHeight, colors, style)
        // A 衝堂 cluster keeps the weekly look but for its assessments, as on iOS.
        is ScheduleCell.Solo -> SoloCell(
            course = cell.course,
            length = cell.length,
            progress = progressOf(cell.course, 0, cell.length),
            spoken = spokenOf(cell.course, 0, cell.length),
            change = changeOf(cell.course, 0, cell.length),
            room = roomOf(cell.course, 0, cell.length),
            colors = colors,
            style = style,
            courseColors = courseColors,
            blockHeight = blockHeight,
            blockWidth = blockWidth,
            courseNameScale = courseNameScale,
        )

        is ScheduleCell.Conflict -> ConflictCell(
            cell = cell,
            changeOf = changeOf,
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
            changeOf = changeOf,
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
    /** How far through the block is while it is in session, which outlines it; null otherwise. */
    progress: Float?,
    spoken: String,
    /** The block as the school changed it on its column's date; null for the timetable's. */
    change: CourseBlock?,
    /** The room the timetable has for this block's periods; empty where it names none. */
    room: String,
    colors: WidgetColors,
    style: VisualStylePolicy,
    courseColors: Map<String, Color>,
    blockHeight: Dp,
    blockWidth: Dp,
    courseNameScale: Float,
) {
    val cancelled = change?.isCancelled == true
    // In session, the fill is laid over the widget, so the outline behind it does not show through.
    val session = progress?.let { TileSession(weekCourseAccent(course, courseColors, style), it, blockWidth - 2.dp.scaled) }
    val cellBg = weekCourseFill(course, false, colors, style, courseColors, cancelled).let { if (session != null) it.compositeOver(colors.background) else it }
    // A cancelled class steps back, as on the iPhone; it is never in session.
    val textColor = if (cancelled) colors.onSurfaceVariant else weekCourseTextColor(false, colors, style)
    val fontScale = WidgetScale.cappedFontScale(LocalContext.current.resources.configuration.fontScale)
    val widgetScale = LocalWidgetScale.current
    val textHeight = if (session != null) blockHeight - 6.dp.scaled else blockHeight // Less the outline and the bar.
    val nameLinesBeside = { lineCount: Int -> blockNameLines(textHeight, lineCount, courseNameScale, fontScale, widgetScale) }
    val lines = weekBlockLinesFor(
        change = change,
        room = room,
        length = length,
        cancelledLabel = LocalContext.current.getString(R.string.course_card_cancelled),
        online = onlineRoomWord(),
        ink = weekChangeInk(false, colors, style),
        stepBack = colors.onSurfaceVariant,
        textColor = textColor,
        fits = { style.classTableGridStyle == ClassTableGridStyle.PeriodTiles || nameLinesBeside(it) >= 1 }, // Tiles gate theirs below.
    )
    when (style.classTableGridStyle) {
        ClassTableGridStyle.Blocks -> CourseTile(
            name = course.displayName, isAssessment = change?.isAssessment == true, isDark = colors.isDark,
            fill = cellBg, textColor = textColor,
            height = blockHeight, cornerRadius = 6.dp.scaled,
            maxLines = blockNameMaxLines(length, lines.count, nameLinesBeside),
            courseNameScale = courseNameScale,
            struckThrough = cancelled, topLine = lines.top, bottomLine = lines.bottom,
            session = session, spoken = spoken,
        )

        // One tile per period, each naming the course, as on the class table.
        // The change goes on the first tile and the last, where they fit it.
        ClassTableGridStyle.PeriodTiles -> {
            val tileHeight = blockHeight / length
            val nameLines = tileNameLines(textHeight / length, courseNameScale, fontScale, widgetScale)
            val fits = nameLines >= 2
            GlanceStack(
                items = List(length) { it },
                modifier = GlanceModifier.fillMaxWidth().height(blockHeight),
            ) { index ->
                val top = lines.top.takeIf { fits && index == 0 }
                val bottom = lines.bottom.takeIf { fits && index == length - 1 }
                CourseTile(
                    name = course.displayName, isAssessment = change?.isAssessment == true, isDark = colors.isDark,
                    fill = cellBg, textColor = textColor,
                    height = tileHeight,
                    // A period tile's corners, tighter than a block's.
                    cornerRadius = 4.dp.scaled,
                    maxLines = nameLines - listOfNotNull(top, bottom).size,
                    courseNameScale = courseNameScale,
                    struckThrough = cancelled, topLine = top, bottomLine = bottom,
                    // The run's last tile carries the bar, as its name is read once, on its first.
                    session = session?.let { if (index == length - 1) it else it.copy(progress = 0f) },
                    spoken = spoken.takeIf { index == 0 },
                )
            }
        }
    }
}

@Composable
private fun ConflictCell(
    cell: ScheduleCell.Conflict,
    /** Each course's block as the school changed it, of which a cluster shows only the assessment. */
    changeOf: TileChange,
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
        renderConflictLayer(widthPx, heightPx, densityFactor = shapeDensity, cell = cell, course = LayerCourse.A, fillColor = fillA)
    }
    val bitmapB = remember(cell, widthPx, heightPx, shapeDensity, fillB) {
        renderConflictLayer(widthPx, heightPx, densityFactor = shapeDensity, cell = cell, course = LayerCourse.B, fillColor = fillB)
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
                courseName = cell.courseA.displayName, isAssessment = changeOf(cell.courseA, cell.offsetA, cell.spanA)?.isAssessment == true,
                textColor = weekCourseTextColor(highlightA, colors, style), isDark = colors.isDark,
                alignment = Alignment.TopEnd,
                boxTopPadding = rowHeight * cell.offsetA,
                boxBottomPadding = rowHeight * (cell.combinedSpan - cell.offsetA - cell.spanA),
                barWidth = barWidth,
                courseNameScale = courseNameScale,
            )
            // Course B label — pinned inside B's own Box at bottom-left (mirror-L's bottom bar).
            ConflictLabel(
                courseName = cell.courseB.displayName, isAssessment = changeOf(cell.courseB, cell.offsetB, cell.spanB)?.isAssessment == true,
                textColor = weekCourseTextColor(highlightB, colors, style), isDark = colors.isDark,
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
    /** Each course's block as the school changed it, of which a cluster shows only the assessment. */
    changeOf: TileChange,
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
                                WidgetCourseName(
                                    text = member.course.displayName, isDark = colors.isDark,
                                    isAssessment = changeOf(member.course, member.offset, member.span)?.isAssessment == true,
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
    isAssessment: Boolean = false, isDark: Boolean = false,
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
            WidgetCourseName(
                text = courseName, isAssessment = isAssessment, isDark = isDark,
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
