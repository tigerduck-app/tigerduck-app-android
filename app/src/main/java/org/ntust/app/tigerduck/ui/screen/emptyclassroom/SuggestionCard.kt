package org.ntust.app.tigerduck.ui.screen.emptyclassroom

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Stairs
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.emptyclassroom.ClassStop
import org.ntust.app.tigerduck.emptyclassroom.EmptyClassroomSchedule
import org.ntust.app.tigerduck.emptyclassroom.RoomMove
import org.ntust.app.tigerduck.emptyclassroom.RoomPlace
import org.ntust.app.tigerduck.emptyclassroom.RoomSuggestion
import org.ntust.app.tigerduck.emptyclassroom.RoomUsage
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * The suggested room, its free time and the way there from the class before and on to the class
 * after, and the next two rooms in a card under it. No header: its place on the screen says it.
 */
@Composable
internal fun SuggestionSection(
    suggestion: RoomSuggestion,
    ended: Int,
    onRoom: (RoomUsage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        suggestion.candidates.firstOrNull()?.let { best ->
            val span = suggestion.freeSpan(best)
            val spanLabel = freeSpanLabel(span.first, span.last)
            Card(
                onClick = { onRoom(best.room) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        ShrinkingText(
                            text = best.room.room,
                            style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = TabularFigures),
                            minScale = 0.7f,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${EmptyClassroomSchedule.startTime(span.first)}–${EmptyClassroomSchedule.endTime(span.last)}",
                            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TabularFigures),
                            color = FinderColors.freeText,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(start = 12.dp, bottom = 4.dp)
                                .clearAndSetSemantics { contentDescription = spanLabel },
                        )
                    }
                    CappedFontScale { RouteLine(suggestion.previous, best.place, suggestion.next) }
                }
            }
        }
        val others = suggestion.candidates.drop(1)
        if (others.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                others.forEachIndexed { index, candidate ->
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp))
                    RoomRow(candidate.room, suggestion.period, ended, onRoom)
                }
            }
        }
    }
}

/**
 * The class before as a ring, the room as the green dot and the class after as a ring, with the
 * classes' rooms under the ends. A leg shows stairs or a walk only when it takes one: staying in
 * the room or crossing a floor is a bare line, and TalkBack still names the move.
 */
@Composable
private fun RouteLine(previous: ClassStop?, room: RoomPlace?, next: ClassStop?) {
    val into = previous?.let { RoomMove.between(it.place, room) }
    val onward = next?.let { RoomMove.between(room, it.place) }
    val description = joinLabel(*listOfNotNull(previous?.room, into?.let { moveLabel(it) }, onward?.let { moveLabel(it) }, next?.room).toTypedArray())
    Column(
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            if (previous != null) {
                ClassRing()
                Leg(into)
            }
            RoomDot()
            if (next != null) {
                Leg(onward)
                ClassRing()
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = previous?.room.orEmpty(),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TabularFigures),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = next?.room.orEmpty(),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TabularFigures),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ClassRing() {
    Box(
        Modifier
            .size(12.dp)
            .border(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY), CircleShape),
    )
}

@Composable
private fun RoomDot() {
    val green = FinderColors.freeFill
    Box(
        Modifier
            .size(24.dp)
            .background(green.copy(alpha = 0.2f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(14.dp).background(green, CircleShape))
    }
}

@Composable
private fun RowScope.Leg(move: RoomMove?) {
    val walk = move is RoomMove.Walk
    Row(
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Track(walk, Modifier.weight(1f))
        val icon = when (move) {
            is RoomMove.Floors -> Icons.Filled.Stairs
            is RoomMove.Walk -> Icons.AutoMirrored.Filled.DirectionsWalk
            else -> null
        }
        if (icon != null && move != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = moveText(move),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = TabularFigures,
                ),
                maxLines = 1,
            )
            Track(walk, Modifier.weight(1f))
        }
    }
}

/** Dotted for a walk, as maps draw one. */
@Composable
private fun Track(dotted: Boolean, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dotted) 0.6f else 0.35f)
    Canvas(modifier.height(4.dp)) {
        val width = if (dotted) 3.dp.toPx() else 2.dp.toPx()
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = width,
            cap = StrokeCap.Round,
            pathEffect = if (dotted) PathEffect.dashPathEffect(floatArrayOf(0f, 6.dp.toPx())) else null,
        )
    }
}

@Composable
private fun moveText(move: RoomMove): String = when (move) {
    RoomMove.SameRoom -> ""
    is RoomMove.Floors -> "${if (move.floors > 0) "↑" else "↓"}${kotlin.math.abs(move.floors)}"
    is RoomMove.Walk -> stringResource(R.string.empty_classroom_walk_minutes, move.minutes)
}

@Composable
private fun moveLabel(move: RoomMove): String = when (move) {
    RoomMove.SameRoom -> stringResource(R.string.a11y_empty_classroom_same_room)
    is RoomMove.Floors -> if (move.floors > 0) {
        stringResource(R.string.a11y_empty_classroom_floors_up, move.floors)
    } else {
        stringResource(R.string.a11y_empty_classroom_floors_down, -move.floors)
    }
    is RoomMove.Walk -> stringResource(R.string.a11y_empty_classroom_walk_minutes, move.minutes)
}
