// Parts of the timetable grid that stand apart from its cells: the weekday header, the period
// labels, the empty slots and the current-time line. TimetableGrid places them.

package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.data.model.TimetablePeriod

/** The period column's labels, one row each. */
@Composable
internal fun ClassTablePeriodLabels(periods: List<TimetablePeriod>, periodColWidth: Dp, cellHeight: Dp) {
    periods.forEachIndexed { periodIndex, period ->
        ClassTablePeriodLabel(
            period = period,
            cellHeight = cellHeight,
            modifier = Modifier
                .width(periodColWidth)
                .absoluteOffset(x = 0.dp, y = cellHeight * periodIndex),
        )
    }
}

/** An empty slot wherever [roles], by day column and row, has no course. */
@Composable
internal fun ClassTableEmptySlots(roles: List<List<CellRole>>, periodColWidth: Dp, dayColWidth: Dp, cellHeight: Dp) {
    roles.forEachIndexed { dayIndex, day ->
        day.forEachIndexed { periodIndex, role ->
            if (role is CellRole.Empty) {
                ClassTableEmptySlot(
                    modifier = Modifier
                        .width(dayColWidth)
                        .height(cellHeight)
                        .absoluteOffset(x = periodColWidth + dayColWidth * dayIndex, y = cellHeight * periodIndex)
                )
            }
        }
    }
}

/**
 * The weekday labels over the grid's day columns, [dayLabels] indexed by weekday. Placed the way
 * the cells are, at absolute offsets from the left: a `Row` mirrors in a right-to-left language,
 * and put each label over another day's courses.
 */
@Composable
internal fun ClassTableWeekdayHeader(
    weekdays: List<Int>,
    dayLabels: List<String>,
    periodColWidth: Dp,
    dayColWidth: Dp,
    /** Today's weekday in a week from today, whose label alone takes the accent; null for a plain timetable. */
    today: Int? = null,
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = AbsoluteAlignment.TopLeft) {
        weekdays.forEachIndexed { dayIndex, day ->
            Box(
                modifier = Modifier
                    .width(dayColWidth)
                    .absoluteOffset(x = periodColWidth + dayColWidth * dayIndex),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = dayLabels.getOrElse(day) { "$day" },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (today == null || day == today) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * The current-time line over the day columns, with a dot at its leading end, as on iOS: inside a
 * period by the share of it gone, at the top of the next between two; none before the first or
 * after the last.
 */
@Composable
internal fun ClassTableNowLine(
    minute: Int,
    periods: List<org.ntust.app.tigerduck.data.model.TimetablePeriod>,
    cellHeight: Dp,
    x: Dp,
    width: Dp,
) {
    val ranges = periods.map { minuteOf(it.startTime)..minuteOf(it.endTime) }
    val offset = ClassTableThisWeek.nowLineOffset(minute, ranges, cellHeight.value, cellHeight.value) ?: return
    val color = MaterialTheme.colorScheme.primary
    val dot = 7.dp
    val line = 1.5.dp
    Box(
        modifier = Modifier
            .absoluteOffset(x = x - dot / 2, y = offset.dp - dot / 2)
            .width(width + dot / 2)
            .height(dot)
            .testTag(NOW_LINE_TAG)
            .clearAndSetSemantics {},
    ) {
        Box(
            Modifier
                .absoluteOffset(y = (dot - line) / 2)
                .fillMaxWidth()
                .height(line)
                .background(color),
        )
        Box(Modifier.size(dot).background(color, CircleShape))
    }
}

private fun minuteOf(hm: String): Int = hm.split(":").let { (h, m) -> h.toInt() * 60 + m.toInt() }

