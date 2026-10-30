// The parts of the class table that the interface style changes beyond a
// colour: the period column, the row bands behind the grid, empty slots, and
// a course run drawn as one tile per period. See VisualStylePolicy.

package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ntust.app.tigerduck.data.model.TimetablePeriod
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy

/** Whether a course run is drawn as one tile per period rather than one block. */
internal val VisualStylePolicy.drawsPeriodTiles: Boolean
    get() = classTableGridStyle == VisualStylePolicy.ClassTableGridStyle.PeriodTiles

/** A course's fill on the class table, for the theme in effect. */
internal fun VisualStylePolicy.courseFill(courseNo: String): Color = classTableCourseFill(
    courseColor = TigerDuckTheme.courseColorVibrant(courseNo),
    themeColor = TigerDuckTheme.courseColor(courseNo),
    isDark = TigerDuckTheme.isDarkMode,
)

/** A required course's outline on [courseFill], for the theme in effect. */
internal fun VisualStylePolicy.courseOutline(courseNo: String): Color =
    classTableCourseOutline(TigerDuckTheme.courseColorVibrant(courseNo))

/**
 * The 1dp outline a required course's cell draws just inside its [shape], or
 * nothing: null where the course is not required or the user has the
 * outlines off. Thin, as on the iPhone: a mark that the course stands out by,
 * not a frame.
 */
internal fun Modifier.requiredCourseOutline(outline: Color?, shape: Shape): Modifier =
    if (outline == null) this else border(REQUIRED_OUTLINE_WIDTH, outline, shape)

private val REQUIRED_OUTLINE_WIDTH = 1.dp

/** A course's name on [courseFill], for the theme in effect. */
internal val VisualStylePolicy.courseTextColor: Color
    get() = classTableCourseTextColor(TigerDuckTheme.isDarkMode)

/** The room hint and the corner mark on [courseFill], for the theme in effect. */
internal val VisualStylePolicy.courseSecondaryTextColor: Color
    get() = classTableCourseSecondaryTextColor(TigerDuckTheme.isDarkMode)

/**
 * The space between two period tiles of a run: the 1dp each cell keeps on
 * either side, so tiles stand apart exactly as neighbouring cells do.
 */
private val PERIOD_TILE_GAP = 2.dp

/** One row's label in the period column, one row high. */
@Composable
internal fun ClassTablePeriodLabel(
    period: TimetablePeriod,
    cellHeight: Dp,
    modifier: Modifier = Modifier,
) {
    // The times are left out where the style shows only the period; the
    // course detail still has them.
    val showsTimes = LocalVisualStylePolicy.current.classTableShowsPeriodTimes
    Column(
        modifier = modifier.height(cellHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Start above, period in the middle, end below, so the
        // row reads as the span it occupies rather than as a
        // number with one timestamp that could be either end.
        //
        // Every line is pinned to one: the day cells are placed
        // at absolute `cellHeight * index` offsets, so a label
        // that wraps grows past its row and overlaps the next
        // one instead of pushing it down. Clipping a wide time
        // at a large font scale is the better failure — and
        // ellipsis is not an option here, since "08:…" tells
        // the reader nothing.
        if (showsTimes) {
            Text(
                text = period.startTime,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                maxLines = 1,
                softWrap = false,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
            )
        }
        Text(
            text = period.id,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            fontSize = 12.sp,
            lineHeight = 14.sp,
            maxLines = 1,
            softWrap = false,
        )
        if (showsTimes) {
            Text(
                text = period.endTime,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                maxLines = 1,
                softWrap = false,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
            )
        }
    }
}

/**
 * The period column's width. Narrower where the column holds only the
 * period, its times left out; either way it grows with a large font.
 */
internal fun VisualStylePolicy.classTablePeriodColumnWidth(fontScale: Float): Dp =
    if (classTableShowsPeriodTimes) {
        maxOf(36.dp, (28 * fontScale).dp)
    } else {
        maxOf(20.dp, (16 * fontScale).dp)
    }

/**
 * The bands behind the grid's [rowCount] period rows, where the style has
 * them, given the theme's `onSurface`. The rows are the last
 * `rowCount * cellHeight` of the element this is applied to, below the
 * weekday header, and each band spans its whole width: applied outside the
 * grid's side padding, the bands run from edge to edge, as on the iPhone.
 * Drawn behind the content, so a course run that reaches down over the rows
 * below its first stays on top of their bands.
 */
internal fun Modifier.classTableRowBands(
    style: VisualStylePolicy,
    onSurface: Color,
    rowCount: Int,
    cellHeight: Dp,
): Modifier = drawBehind {
    val rowHeight = cellHeight.toPx()
    val top = size.height - rowHeight * rowCount
    repeat(rowCount) { index ->
        val band = style.classTableRowBand(index, onSurface) ?: return@repeat
        drawRect(
            color = band,
            topLeft = Offset(0f, top + rowHeight * index),
            size = Size(size.width, rowHeight),
        )
    }
}

/**
 * A slot no course takes, in the cell's place: a faint tile, or nothing
 * where the style leaves empty slots bare. Every grid pass that draws empty
 * slots draws them with this, so none of them misses the style.
 */
@Composable
internal fun ClassTableEmptySlot(modifier: Modifier = Modifier) {
    val color = LocalVisualStylePolicy.current
        .classTableEmptySlotColor(MaterialTheme.colorScheme.surfaceVariant)
    Box(
        modifier = modifier
            .padding(1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
    )
}

/**
 * A course run drawn as one tile per period, each naming the course, in the
 * cell the run takes. The run stays one cell around it: one tap target, one
 * menu and one TalkBack element. The room hint and the corner mark sit in the
 * run's bottom corners, over the last tile, which leaves [lastTileBottomSpace]
 * free for them: a tile's third line would otherwise run under them.
 */
@Composable
internal fun ClassTablePeriodTiles(
    name: String,
    spanCount: Int,
    cellHeight: Dp,
    fill: Color,
    textColor: Color,
    lastTileBottomSpace: Dp,
    /** A required course's outline, on each tile of the run; see [requiredCourseOutline]. */
    outline: Color? = null,
    /** A line through the name, as for a cancelled class. */
    nameDecoration: TextDecoration? = null,
) {
    val tileShape = RoundedCornerShape(PERIOD_TILE_CORNER)
    Column(verticalArrangement = Arrangement.spacedBy(PERIOD_TILE_GAP)) {
        repeat(spanCount) { index ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cellHeight - PERIOD_TILE_GAP)
                    .clip(tileShape)
                    .background(fill)
                    .requiredCourseOutline(outline, tileShape),
            ) {
                ClassTableCourseNameText(
                    text = name,
                    color = textColor,
                    textDecoration = nameDecoration,
                    maxLines = 3,
                    modifier = Modifier
                        .padding(2.dp)
                        .padding(bottom = if (index == spanCount - 1) lastTileBottomSpace else 0.dp)
                        .align(Alignment.Center),
                )
            }
        }
    }
}

/** A period tile's corners: tighter than a block's, as tiles sit in a denser grid. */
internal val PERIOD_TILE_CORNER = 4.dp
