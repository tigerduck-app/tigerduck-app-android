package org.ntust.app.tigerduck.ui.screen.emptyclassroom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.emptyclassroom.EmptyClassroomSchedule
import org.ntust.app.tigerduck.emptyclassroom.RoomSlotUse
import org.ntust.app.tigerduck.emptyclassroom.RoomUsage
import org.ntust.app.tigerduck.ui.component.DetentSheet
import org.ntust.app.tigerduck.ui.component.rememberDetentSheetState
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * A room's day, period by period: a lane green while free and orange while booked, joined across
 * neighbouring periods, and each class with its teacher. A booking shows no name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoomDaySheet(
    room: RoomUsage,
    /** The building's name and the room's floor. */
    place: String,
    day: String,
    period: Int,
    ended: Int,
    /** The period in progress, or the next in a break, which today's now line marks. */
    nowPeriod: Int?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberDetentSheetState()
    val scope = rememberCoroutineScope()
    DetentSheet(
        onDismissed = onDismiss,
        state = sheetState,
        halfHeight = true,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ShrinkingText(
                        room.room,
                        style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = TabularFigures),
                        minScale = 0.7f,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Business,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                        )
                        Text(
                            "$place · $day",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                IconButton(onClick = {
                    scope.launch { sheetState.hide() }
                }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                }
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                CappedFontScale {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        EmptyClassroomSchedule.periodIds.indices.forEach { index ->
                            PeriodRow(room, index, isSelected = index == period, isEnded = index < ended, isNow = index == nowPeriod)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
            ) {
                LegendItem(FinderColors.freeFill, stringResource(R.string.empty_classroom_free))
                LegendItem(FinderColors.bookedFill, stringResource(R.string.empty_classroom_booked))
            }
        }
    }
}

private enum class Lane { FREE, BOOKED }

private fun laneOf(room: RoomUsage, index: Int): Lane? = when (room.slots.getOrNull(index)) {
    RoomSlotUse.Free -> Lane.FREE
    RoomSlotUse.Booked -> Lane.BOOKED
    else -> null
}

@Composable
private fun PeriodRow(room: RoomUsage, index: Int, isSelected: Boolean, isEnded: Boolean, isNow: Boolean) {
    val id = EmptyClassroomSchedule.periodIds[index]
    val times = EmptyClassroomSchedule.timeRangeText(index)
    val use = room.slots.getOrNull(index) ?: RoomSlotUse.Free
    val free = stringResource(R.string.empty_classroom_free)
    val booked = stringResource(R.string.empty_classroom_booked)
    val useText = when (use) {
        RoomSlotUse.Free -> free
        RoomSlotUse.Booked -> booked
        is RoomSlotUse.Course -> joinLabel(use.name, use.teacher)
    }
    val label = joinLabel(stringResource(R.string.a11y_empty_classroom_period, id), times, useText)
    val lane = laneOf(room, index)
    val opensRun = laneOf(room, index - 1) != lane
    val closesRun = laneOf(room, index + 1) != lane
    val laneColor = when (lane) {
        Lane.FREE -> FinderColors.freeFill
        Lane.BOOKED -> FinderColors.bookedFill
        null -> Color.Transparent
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clearAndSetSemantics {
                contentDescription = label
                selected = isSelected
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .heightIn(min = 44.dp)
                .alpha(if (isEnded) 0.4f else 1f)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                id,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.widthIn(min = 24.dp),
                maxLines = 1,
            )
            Text(
                times,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TabularFigures),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                modifier = Modifier.widthIn(min = 92.dp),
                maxLines = 1,
            )
            // The lane spans the whole row and meets the next row's.
            Box(
                Modifier
                    .width(8.dp)
                    .fillMaxHeight()
                    .padding(top = if (opensRun) 6.dp else 0.dp, bottom = if (closesRun) 6.dp else 0.dp)
                    .background(
                        laneColor,
                        RoundedCornerShape(
                            topStart = if (opensRun) 4.dp else 0.dp,
                            topEnd = if (opensRun) 4.dp else 0.dp,
                            bottomStart = if (closesRun) 4.dp else 0.dp,
                            bottomEnd = if (closesRun) 4.dp else 0.dp,
                        ),
                    ),
            )
            when (use) {
                RoomSlotUse.Free -> Spacer(Modifier.weight(1f))
                RoomSlotUse.Booked -> Text(
                    booked,
                    style = MaterialTheme.typography.bodyMedium,
                    color = FinderColors.bookedText,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                is RoomSlotUse.Course -> Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        use.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (use.teacher.isNotEmpty()) {
                        Text(
                            use.teacher,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (isNow) NowLine(Modifier.align(Alignment.TopStart))
    }
}

/** Today's now line, at the top of the period in progress or the next one. */
@Composable
private fun NowLine(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Box(modifier.fillMaxWidth().padding(top = 0.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(2.dp)
                .background(color, RoundedCornerShape(1.dp)),
        )
        Box(Modifier.padding(start = 4.dp).size(8.dp).background(color, CircleShape))
    }
}

@Composable
private fun LegendItem(color: Color, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(8.dp).height(14.dp).background(color, RoundedCornerShape(4.dp)))
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        )
    }
}
