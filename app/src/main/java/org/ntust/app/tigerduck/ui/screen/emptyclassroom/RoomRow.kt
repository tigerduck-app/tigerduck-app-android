package org.ntust.app.tigerduck.ui.screen.emptyclassroom

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.emptyclassroom.EmptyClassroomSchedule
import org.ntust.app.tigerduck.emptyclassroom.RoomUsage

/** Tabular figures, so times and room numbers line up down a list. */
internal val TabularFigures = "tnum"

/**
 * A room's day as a thin track over the 14 periods: its free runs as capsules, the run holding
 * the chosen period solid, and a needle at that period's start. Periods already over are not drawn
 * as free.
 */
@Composable
internal fun AvailabilityBar(room: RoomUsage, period: Int, ended: Int, modifier: Modifier = Modifier) {
    val runs = freeRuns(room, ended)
    val free = FinderColors.freeFill
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val needle = MaterialTheme.colorScheme.onSurface
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.height(14.dp)) {
        val count = EmptyClassroomSchedule.periodIds.size
        val unit = size.width / count
        val barHeight = 6.dp.toPx()
        val top = (size.height - barHeight) / 2
        val radius = CornerRadius(barHeight / 2)
        fun x(index: Int, width: Float = 0f) = if (rtl) size.width - unit * index - width else unit * index
        drawRoundRect(track, Offset(0f, top), Size(size.width, barHeight), radius)
        for (run in runs) {
            val width = unit * (run.last - run.first + 1)
            drawRoundRect(
                if (period in run) free else free.copy(alpha = 0.32f),
                Offset(x(run.first, width), top),
                Size(width, barHeight),
                radius,
            )
        }
        val needleWidth = 2.dp.toPx()
        drawRoundRect(
            needle,
            Offset(x(period) - needleWidth / 2, 0f),
            Size(needleWidth, size.height),
            CornerRadius(needleWidth / 2),
        )
    }
}

private fun freeRuns(room: RoomUsage, ended: Int): List<IntRange> {
    val runs = mutableListOf<IntRange>()
    var index = maxOf(ended, 0)
    while (index < room.slots.size) {
        val last = room.freeUntil(index)
        if (last == null) {
            index++
            continue
        }
        runs += index..last
        index = last + 1
    }
    return runs
}

/** A free room in a list: its name, then its day as a bar with the end of its free run. */
@Composable
internal fun RoomRow(room: RoomUsage, period: Int, ended: Int, onRoom: (RoomUsage) -> Unit) =
    CappedFontScale { RoomRowContent(room, period, ended, onRoom) }

@Composable
private fun RoomRowContent(room: RoomUsage, period: Int, ended: Int, onRoom: (RoomUsage) -> Unit) {
    val until = room.freeUntil(period)
    val label = joinLabel(room.room, until?.let { freeSpanLabel(period, it) }.orEmpty())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button) { onRoom(room) }
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick { onRoom(room); true }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RoomName(
            room = room.room,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = TabularFigures,
            ),
        )
        AvailabilityBar(room, period, ended, Modifier.weight(1f))
        Text(
            text = until?.let { EmptyClassroomSchedule.endTime(it) }.orEmpty(),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TabularFigures),
            color = FinderColors.freeText,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 44.dp),
        )
    }
}
