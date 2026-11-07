package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.Periods
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import kotlin.math.abs
import kotlin.math.floor

/**
 * Where the periods grid's cells sit: from [origin], past the label column and the header row, in
 * equal cells of [cell], weekdays across and periods down. As the iPhone's `PeriodGridGeometry`.
 */
data class PeriodGridGeometry(val origin: Offset, val cell: Size) {

    /**
     * The cells the line from [start] to [end] crosses, in order, leaving out what falls outside
     * the grid. A line through a corner steps diagonally instead of taking a neighbor.
     */
    fun slots(start: Offset, end: Offset): List<CourseSlot> {
        if (cell.width <= 0f || cell.height <= 0f) return emptyList()
        val fromX = (start.x - origin.x).toDouble() / cell.width
        val fromY = (start.y - origin.y).toDouble() / cell.height
        val dx = (end.x - origin.x).toDouble() / cell.width - fromX
        val dy = (end.y - origin.y).toDouble() / cell.height - fromY
        var column = floor(fromX).toInt()
        var row = floor(fromY).toInt()
        val lastColumn = floor(fromX + dx).toInt()
        val lastRow = floor(fromY + dy).toInt()
        // The fraction of the line at which it next crosses a column or a row boundary.
        var nextX = if (dx == 0.0) Double.POSITIVE_INFINITY else (column + (if (dx > 0) 1 else 0) - fromX) / dx
        var nextY = if (dy == 0.0) Double.POSITIVE_INFINITY else (row + (if (dy > 0) 1 else 0) - fromY) / dy
        val deltaX = if (dx == 0.0) Double.POSITIVE_INFINITY else 1 / abs(dx)
        val deltaY = if (dy == 0.0) Double.POSITIVE_INFINITY else 1 / abs(dy)

        val crossed = mutableListOf(slot(column, row))
        repeat(abs(lastColumn - column) + abs(lastRow - row)) {
            if (column == lastColumn && row == lastRow) return crossed.filterNotNull()
            val crossesColumn = nextX <= nextY
            val crossesRow = nextY <= nextX
            if (crossesColumn) {
                column += if (dx > 0) 1 else -1
                nextX += deltaX
            }
            if (crossesRow) {
                row += if (dy > 0) 1 else -1
                nextY += deltaY
            }
            crossed += slot(column, row)
        }
        return crossed.filterNotNull()
    }

    private fun slot(column: Int, row: Int): CourseSlot? {
        val day = CourseSearch.gridDays.getOrNull(column) ?: return null
        return CourseSlot(day, Periods.chronologicalOrder.getOrNull(row) ?: return null)
    }
}

/**
 * One drag across the periods grid. The first cell decides whether the drag selects or clears,
 * and every cell it crosses follows. As the iPhone's `PeriodDrag`.
 */
class PeriodDrag {
    private var selects: Boolean? = null
    private var last: Offset? = null

    /** Applies the drag to every cell from the previous point to [point], since a fast drag reports points several cells apart. */
    fun move(point: Offset, grid: PeriodGridGeometry, picked: MutableSet<CourseSlot>) {
        for (slot in grid.slots(last ?: point, point)) touch(slot, picked)
        last = point
    }

    fun touch(slot: CourseSlot, picked: MutableSet<CourseSlot>) {
        val selects = selects ?: (slot !in picked)
        this.selects = selects
        if (selects) picked += slot else picked -= slot
    }
}

private val shortDayLabels = listOf(
    R.string.weekday_mon_short, R.string.weekday_tue_short, R.string.weekday_wed_short,
    R.string.weekday_thu_short, R.string.weekday_fri_short, R.string.weekday_sat_short,
)

// Caption keys for TalkBack: in zh-Hant a short name is a bare numeral, which alone does not read as a day.
private val dayCaptions = listOf(
    R.string.weekday_mon_caption, R.string.weekday_tue_caption, R.string.weekday_wed_caption,
    R.string.weekday_thu_caption, R.string.weekday_fri_caption, R.string.weekday_sat_caption,
)

/**
 * Weekdays across, periods down. A drag selects or clears each cell it crosses, as [PeriodDrag]
 * decides, and the periods the class table already uses are shaded. As the iPhone's grid.
 */
@Composable
internal fun CoursePeriodGrid(
    selection: Set<CourseSlot>,
    busy: Set<CourseSlot>,
    onSelection: (Set<CourseSlot>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labelWidth = 28.dp
    val headerHeight = 24.dp
    BoxWithConstraints(modifier) {
        val cellWidth = (maxWidth - labelWidth) / CourseSearch.gridDays.size
        val cellHeight = min(38.dp, (maxHeight - headerHeight) / Periods.chronologicalOrder.size)
        val geometry = with(LocalDensity.current) {
            PeriodGridGeometry(Offset(labelWidth.toPx(), headerHeight.toPx()), Size(cellWidth.toPx(), cellHeight.toPx()))
        }
        val currentGeometry by rememberUpdatedState(geometry)
        val currentSelection by rememberUpdatedState(selection)
        val currentOnSelection by rememberUpdatedState(onSelection)
        val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
        Column(
            Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val drag = PeriodDrag()
                    val picked = currentSelection.toMutableSet()
                    var position = down.position
                    do {
                        drag.move(position, currentGeometry, picked)
                        if (picked != currentSelection) currentOnSelection(picked.toSet())
                        // Consumed, so the sheet under the grid does not move with the drag.
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        position = change.position
                    } while (change.pressed)
                }
            }
        ) {
            Row(Modifier.height(headerHeight).clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(labelWidth))
                shortDayLabels.forEach { label ->
                    GridLabel(stringResource(label), labelColor, Modifier.width(cellWidth))
                }
            }
            Periods.chronologicalOrder.forEach { period ->
                Row(Modifier.height(cellHeight), verticalAlignment = Alignment.CenterVertically) {
                    GridLabel(period, labelColor, Modifier.width(labelWidth).clearAndSetSemantics {})
                    CourseSearch.gridDays.forEach { day ->
                        val slot = CourseSlot(day, period)
                        PeriodCell(slot, slot in selection, slot in busy, Modifier.width(cellWidth).height(cellHeight)) {
                            onSelection(if (slot in selection) selection - slot else selection + slot)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GridLabel(text: String, color: Color, modifier: Modifier) {
    Text(
        text,
        modifier = modifier,
        color = color,
        maxLines = 1,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
private fun PeriodCell(slot: CourseSlot, selected: Boolean, busy: Boolean, modifier: Modifier, toggle: () -> Unit) {
    val label = stringResource(dayCaptions[slot.day - 1]) + " " + slot.period
    val busyText = stringResource(R.string.a11y_course_search_period_busy)
    val colors = MaterialTheme.colorScheme
    val fill = when {
        selected -> colors.primary
        busy -> colors.onSurface.copy(alpha = 0.35f)
        else -> colors.surfaceVariant
    }
    Box(
        modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .clearAndSetSemantics {
                contentDescription = label
                if (busy) stateDescription = busyText
                this.selected = selected
                role = Role.Button
                onClick { toggle(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(14.dp))
        }
    }
}
