// 已訂閱: the subscribed clubs in priority order. A long press on a row's handle drags it to a new
// place, as the tab editor's rows move (iPhone #971 shows the handle); a swipe to the start shows
// Unsubscribe as a red icon and takes the club off once it passes the threshold, as Home's
// assignment rows swipe (iPhone #972); the color square opens the club's color.

package org.ntust.app.tigerduck.ui.screen.classtable.clubs

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.clubs.ClubCard
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import kotlin.math.abs
import kotlin.math.roundToInt

/** The red of the Unsubscribe swipe, the iPhone's system red, as Home's swipes take the iPhone's colors. */
private val UNSUBSCRIBE_RED = Color(0xFFFF3B30)

/** [clubs] in priority order; [onReorder] gets their ids in the new order once a drag ends. */
@Composable
internal fun SubscribedClubs(
    clubs: List<ClubCard>,
    name: (ClubCard) -> String,
    color: (Int) -> Int,
    onReorder: (List<Int>) -> Unit,
    onUnsubscribe: (ClubCard) -> Unit,
    onPickColor: (ClubCard) -> Unit,
) {
    // The order while a drag is under way, which the store takes once it ends.
    var dragged by remember { mutableStateOf<List<Int>?>(null) }
    var draggingId by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(0f) }
    val byId = clubs.associateBy { it.id }
    val order = dragged ?: clubs.map { it.id }

    fun moveBy(step: Int, from: List<Int>, id: Int): List<Int> {
        val at = from.indexOf(id)
        val to = at + step
        if (at < 0 || to !in from.indices) return from
        return from.toMutableList().apply { add(to, removeAt(at)) }
    }

    Column {
        order.forEachIndexed { index, id ->
            val club = byId[id] ?: return@forEachIndexed
            key(id) {
                val dragging = draggingId == id
                Box(
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                        .onSizeChanged { rowHeight = it.height.toFloat() },
                ) {
                    SubscribedClubRow(
                        club = club,
                        name = name(club),
                        color = Color(0xFF000000.toInt() or color(club.id)),
                        canMoveUp = index > 0,
                        canMoveDown = index < order.lastIndex,
                        onMove = { step -> onReorder(moveBy(step, order, id)) },
                        onUnsubscribe = { onUnsubscribe(club) },
                        onPickColor = { onPickColor(club) },
                        onDragStart = { draggingId = id; dragOffset = 0f; dragged = order },
                        onDrag = { delta ->
                            dragOffset += delta
                            val now = dragged ?: order
                            if (rowHeight > 0f && dragOffset > rowHeight / 2 && now.indexOf(id) < now.lastIndex) {
                                dragged = moveBy(1, now, id)
                                dragOffset -= rowHeight
                            } else if (rowHeight > 0f && dragOffset < -rowHeight / 2 && now.indexOf(id) > 0) {
                                dragged = moveBy(-1, now, id)
                                dragOffset += rowHeight
                            }
                        },
                        onDragEnd = {
                            dragged?.takeIf { it != clubs.map(ClubCard::id) }?.let(onReorder)
                            dragged = null
                            draggingId = null
                            dragOffset = 0f
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscribedClubRow(
    club: ClubCard,
    name: String,
    color: Color,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onUnsubscribe: () -> Unit,
    onPickColor: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val latestUnsubscribe by rememberUpdatedState(onUnsubscribe)
    val threshold = with(LocalDensity.current) { 100.dp.toPx() }
    val swipe = remember(club.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val unsubscribeLabel = stringResource(R.string.class_table_club_unsubscribe_action)
    val moveUp = stringResource(R.string.desktop_settings_sidebar_move_up)
    val moveDown = stringResource(R.string.desktop_settings_sidebar_move_down)
    // The swipe and the drag are gestures TalkBack and a keyboard cannot make, so they are offered as actions.
    val actions = buildList {
        add(CustomAccessibilityAction(unsubscribeLabel) { latestUnsubscribe(); true })
        if (canMoveUp) add(CustomAccessibilityAction(moveUp) { onMove(-1); true })
        if (canMoveDown) add(CustomAccessibilityAction(moveDown) { onMove(1); true })
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        if (swipe.value < 0f) UnsubscribeIcon(progress = (abs(swipe.value) / threshold).coerceIn(0f, 1f), unsubscribeLabel)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(swipe.value.roundToInt(), 0) }
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .pointerInput(club.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (swipe.value <= -threshold) {
                                    swipe.animateTo(-2000f, tween(durationMillis = 200))
                                    latestUnsubscribe()
                                    swipe.snapTo(0f)
                                } else {
                                    swipe.animateTo(0f, spring())
                                }
                            }
                        },
                        onDragCancel = { scope.launch { swipe.animateTo(0f, spring()) } },
                        onHorizontalDrag = { _, delta -> scope.launch { swipe.snapTo(minOf(0f, swipe.value + delta * 0.6f)) } },
                    )
                }
                .semantics(mergeDescendants = true) { customActions = actions }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ClubColorSquare(color, stringResource(R.string.a11y_class_table_club_color, name), onPickColor)
            ClubNameLabel(name, club.tagline, Modifier.weight(1f))
            DragHandle(club.id, onDragStart, onDrag, onDragEnd)
        }
    }
}

/** The red Unsubscribe icon a swipe uncovers at the row's end, growing in as it nears the threshold. */
@Composable
private fun BoxScope.UnsubscribeIcon(progress: Float, label: String) {
    Box(Modifier.matchParentSize().padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
        Icon(
            Icons.Filled.RemoveCircleOutline,
            contentDescription = label,
            tint = UNSUBSCRIBE_RED,
            modifier = Modifier.size(26.dp).alpha(progress).scale(0.5f + 0.5f * progress),
        )
    }
}

/** The row's drag handle, which a long press picks up. */
@Composable
private fun DragHandle(key: Int, onDragStart: () -> Unit, onDrag: (Float) -> Unit, onDragEnd: () -> Unit) {
    // The gesture outlives the composition it started in, and the list it moves changes under it.
    val start by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    Icon(
        Icons.Filled.DragHandle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        modifier = Modifier
            .size(40.dp)
            .padding(8.dp)
            .pointerInput(key) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { start() },
                    onDrag = { change, offset -> change.consume(); drag(offset.y) },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                )
            },
    )
}

/** The club's color, a 22dp square, which opens its color sheet. */
@Composable
private fun ClubColorSquare(color: Color, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(color))
    }
}
