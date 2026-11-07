package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.AccentGradient
import org.ntust.app.tigerduck.ui.component.ColorCodeState
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.util.replaceIosArg

/** The iPhone's system red and green, which its editor's remove and add buttons wear. */
private val RemoveRed = Color(0xFFFF3B30)
private val AddGreen = Color(0xFF34C759)

/**
 * The [draft]'s stops, one row each: a button that removes it (while there are two or more), its
 * color, which picks it for the color controls below, its code, and a handle a long press drags to
 * reorder it, which TalkBack offers as moving it up or down. Then a row that adds one (up to five),
 * and, for TalkBack, the count of colors once a stop is added or removed.
 */
@Composable
internal fun AccentGradientStops(draft: AccentDraft, isDark: Boolean) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(0f) }
    val stops = draft.accent.stops
    Column {
        stops.forEachIndexed { index, hex ->
            Box(
                modifier = Modifier
                    .zIndex(if (dragging == index) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging == index) dragOffset else 0f }
                    .onSizeChanged { rowHeight = it.height.toFloat() },
            ) {
                StopRow(
                    index = index,
                    hex = hex,
                    draft = draft,
                    isDark = isDark,
                    onDragStart = { dragging = index; dragOffset = 0f },
                    onDrag = { delta ->
                        val at = dragging ?: return@StopRow
                        dragOffset += delta
                        val step = when {
                            rowHeight > 0f && dragOffset > rowHeight / 2 && at < draft.accent.stops.lastIndex -> 1
                            rowHeight > 0f && dragOffset < -rowHeight / 2 && at > 0 -> -1
                            else -> 0
                        }
                        if (step != 0) {
                            draft.move(at, at + step)
                            dragging = at + step
                            dragOffset -= step * rowHeight
                        }
                    },
                    onDragEnd = { dragging = null; dragOffset = 0f },
                )
            }
        }
        if (draft.accent.canAddStop) AddStopRow(onAdd = draft::add)
        CountAnnouncement(draft.announcedCount)
    }
}

@Composable
private fun StopRow(
    index: Int,
    hex: Int,
    draft: AccentDraft,
    isDark: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val name = stringResource(R.string.a11y_accent_gradient_stop).replaceIosArg(1, index + 1L)
    val many = draft.accent.isGradient
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (many) {
            val remove = listOf(stringResource(R.string.action_remove), name).joinToString(", ")
            IconButton(onClick = { draft.remove(index) }) {
                Icon(Icons.Filled.RemoveCircle, contentDescription = remove, tint = RemoveRed)
            }
        }
        StopColor(index, hex, name, draft, isDark, Modifier.weight(1f))
        if (many) DragHandle(onDragStart, onDrag, onDragEnd)
    }
}

/** The stop's color and code, one item that picks it for the color controls, named by its number. */
@Composable
private fun StopColor(index: Int, hex: Int, name: String, draft: AccentDraft, isDark: Boolean, modifier: Modifier) {
    val moveUp = stringResource(R.string.desktop_settings_sidebar_move_up)
    val moveDown = stringResource(R.string.desktop_settings_sidebar_move_down)
    // The drag is a gesture TalkBack and a keyboard cannot make, so it is offered as actions.
    val actions = buildList {
        if (index > 0) add(CustomAccessibilityAction(moveUp) { draft.move(index, index - 1); true })
        if (index < draft.accent.stops.lastIndex) add(CustomAccessibilityAction(moveDown) { draft.move(index, index + 1); true })
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = draft.editing == index, role = Role.RadioButton, onClick = { draft.edit(index) })
            .semantics(mergeDescendants = true) {
                contentDescription = name
                customActions = actions
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val ring = if (draft.editing == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(drawnAccent(AccentGradient.color(hex), isDark))
                .border(if (draft.editing == index) 3.dp else 1.dp, ring, CircleShape),
        )
        Text(ColorCodeState.code(hex), style = MaterialTheme.typography.bodyMedium)
    }
}

/** The row's drag handle, which a long press picks up. */
@Composable
private fun DragHandle(onDragStart: () -> Unit, onDrag: (Float) -> Unit, onDragEnd: () -> Unit) {
    // The gesture outlives the composition it started in, and the list it moves changes under it.
    val start by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    Icon(
        Icons.Filled.DragHandle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        modifier = Modifier
            .size(48.dp)
            .padding(12.dp)
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { start() },
                    onDrag = { change, offset -> change.consume(); drag(offset.y) },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                )
            },
    )
}

@Composable
private fun AddStopRow(onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onAdd)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Filled.AddCircle, contentDescription = null, tint = AddGreen)
        Text(stringResource(R.string.action_add), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Says the count of colors, politely, each time a stop is added or removed, as the iPhone's
 * editor announces it; nothing before the first. There from the start, so its first change is
 * one TalkBack hears, as a node that appears with it might not be.
 */
@Composable
private fun CountAnnouncement(count: Int?) {
    val template = stringResource(R.string.a11y_accent_gradient_stop_count)
    val said = count?.let { template.replaceIosArg(1, it.toLong()) }
    Box(
        Modifier
            .size(1.dp)
            .semantics {
                if (said != null) contentDescription = said
                liveRegion = LiveRegionMode.Polite
            },
    )
}
