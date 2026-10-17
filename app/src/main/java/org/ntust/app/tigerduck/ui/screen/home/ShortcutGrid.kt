// The body of a Home section that holds shortcuts (Quick widgets, or a
// custom section): a two-column grid of cards that open a feature, the
// iPhone's WidgetGridView. In edit mode each card loses its tap, gains a
// remove button and can be dragged to another slot.

package org.ntust.app.tigerduck.ui.screen.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.WidgetItem
import org.ntust.app.tigerduck.ui.navigation.icon
import kotlin.math.roundToInt

private const val COLUMNS = 2
private val CardGap = 12.dp
private val CardHeight = 88.dp

/**
 * @param onOpen a card tapped outside edit mode; the caller decides between
 *   the feature's page and "Coming soon", as the More page does.
 * @param onMove one step of a drag, or an accessibility action: the card
 *   [WidgetItem.id] now belongs at the given slot. Called only when the slot
 *   changed.
 * @param onAdd the empty state's button: opens the Add dialog, so a section
 *   created with nothing in it shows how to fill it instead of a bare title.
 */
@Composable
internal fun ShortcutGrid(
    widgets: List<WidgetItem>,
    isEditing: Boolean,
    onOpen: (AppFeature) -> Unit,
    onRemove: (WidgetItem) -> Unit,
    onMove: (widgetId: String, toIndex: Int) -> Unit,
    onAdd: () -> Unit,
) {
    if (widgets.isEmpty()) {
        // 4dp outside plus the button's own 12dp lines its label up with the
        // section title's 16dp gutter.
        TextButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 4.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.home_section_add_quick_features))
        }
        return
    }

    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    // Every card is the same size (equal widths, fixed height), so the last
    // one measured stands for all of them. Needed to turn a drag distance
    // into a slot; until a card reports, a drag moves nothing.
    var cellSize by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(LocalDensity.current) { CardGap.toPx() }
    // The drag runs inside pointerInput, which is keyed per card and does
    // not restart when the list reorders under it, so it reads the list
    // and the callback through these rather than the captures it began with.
    val currentWidgets by rememberUpdatedState(widgets)
    val currentOnMove by rememberUpdatedState(onMove)

    // Leaving edit mode mid-drag (back gesture, Done) must not leave a
    // card hanging where the finger was.
    LaunchedEffect(isEditing) {
        if (!isEditing) {
            draggingId = null
            dragOffset = Offset.Zero
        }
    }

    fun settle(delta: Offset) {
        val id = draggingId ?: return
        dragOffset += delta
        if (cellSize == IntSize.Zero) return
        val list = currentWidgets
        val from = list.indexOfFirst { it.id == id }
        val pitchX = cellSize.width + gapPx
        val pitchY = cellSize.height + gapPx
        val to = ShortcutGridGeometry.targetIndex(
            from = from, count = list.size, columns = COLUMNS,
            dx = dragOffset.x, dy = dragOffset.y, pitchX = pitchX, pitchY = pitchY,
        )
        if (to == from) return
        currentOnMove(id, to)
        // The card now has a new resting slot; take that slot's distance
        // out of the offset so it stays under the finger instead of jumping.
        dragOffset -= Offset(
            x = (to % COLUMNS - from % COLUMNS) * pitchX,
            y = (to / COLUMNS - from / COLUMNS) * pitchY,
        )
    }

    // One parent for every card, not a Row per line: `key` keeps a card's
    // composition — and the pointerInput carrying its drag — only while it
    // stays under the same parent, so with rows a drag died the moment it
    // crossed into the next one.
    TwoColumnLayout(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        widgets.forEachIndexed { index, widget ->
            key(widget.id) {
                val isDragging = draggingId == widget.id
                ShortcutCard(
                    widget = widget,
                    index = index,
                    count = widgets.size,
                    isEditing = isEditing,
                    isDragging = isDragging,
                    onOpen = { onOpen(widget.feature) },
                    onRemove = { onRemove(widget) },
                    onMoveTo = { onMove(widget.id, it) },
                    modifier = Modifier
                        // Over its neighbours while lifted; the layout
                        // honours zIndex like any other.
                        .zIndex(if (isDragging) 1f else 0f)
                        .offset {
                            if (isDragging) IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt())
                            else IntOffset.Zero
                        }
                        .onSizeChanged { cellSize = it }
                        .then(
                            if (isEditing) {
                                // No long press: the section's own handle
                                // starts on first touch-and-move too, and a
                                // card has nothing else to do with a drag
                                // while editing.
                                Modifier.pointerInput(widget.id) {
                                    detectDragGestures(
                                        onDragStart = {
                                            draggingId = widget.id
                                            dragOffset = Offset.Zero
                                        },
                                        onDragEnd = { draggingId = null; dragOffset = Offset.Zero },
                                        onDragCancel = { draggingId = null; dragOffset = Offset.Zero },
                                        onDrag = { change, delta ->
                                            change.consume()
                                            settle(delta)
                                        },
                                    )
                                }
                            } else {
                                Modifier
                            }
                        ),
                )
            }
        }
    }
}

/**
 * Its children in [COLUMNS] equal columns, [CardGap] apart, row after row.
 * A child's own offset (the drag) moves it within the slot it is placed
 * in, as it would in a Row.
 */
@Composable
private fun TwoColumnLayout(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = CardGap.roundToPx()
        val cellWidth = ((constraints.maxWidth - gap * (COLUMNS - 1)) / COLUMNS).coerceAtLeast(0)
        val placeables = measurables.map { it.measure(Constraints.fixedWidth(cellWidth)) }
        val cellHeight = placeables.maxOfOrNull { it.height } ?: 0
        val rows = (placeables.size + COLUMNS - 1) / COLUMNS
        val height = if (rows == 0) 0 else rows * cellHeight + (rows - 1) * gap
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { i, placeable ->
                placeable.placeRelative(
                    x = (i % COLUMNS) * (cellWidth + gap),
                    y = (i / COLUMNS) * (cellHeight + gap),
                )
            }
        }
    }
}

@Composable
private fun ShortcutCard(
    widget: WidgetItem,
    index: Int,
    count: Int,
    isEditing: Boolean,
    isDragging: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onMoveTo: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The same ramp as a dragged section: elevation says "lifted", no wiggle.
    val elevation by animateFloatAsState(
        targetValue = if (isDragging) 12f else 0f,
        label = "shortcut-drag-elevation",
    )
    val shape = RoundedCornerShape(12.dp)
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val cardModifier = Modifier
        .fillMaxSize()
        .then(if (isDragging) Modifier.shadow(elevation.dp, shape) else Modifier)
    val featureName = stringResource(widget.feature.displayNameRes)
    val removeLabel = stringResource(R.string.action_remove)
    // The drag and the cross, as actions TalkBack can perform since it
    // cannot drag: before and after in reading order, which is what the Mac
    // sidebar's move labels say too, and removal, named for the card.
    val moveUpLabel = stringResource(R.string.desktop_settings_sidebar_move_up)
    val moveDownLabel = stringResource(R.string.desktop_settings_sidebar_move_down)
    val latestOnMoveTo by rememberUpdatedState(onMoveTo)
    val latestOnRemove by rememberUpdatedState(onRemove)
    val editActions = remember(index, count, moveUpLabel, moveDownLabel, removeLabel) {
        buildList {
            if (index > 0) add(CustomAccessibilityAction(moveUpLabel) { latestOnMoveTo(index - 1); true })
            if (index < count - 1) add(CustomAccessibilityAction(moveDownLabel) { latestOnMoveTo(index + 1); true })
            add(CustomAccessibilityAction(removeLabel) { latestOnRemove(); true })
        }
    }

    Box(modifier = modifier.height(CardHeight)) {
        if (isEditing) {
            // Not a button while editing: a double tap would do nothing, so
            // TalkBack is not told to try; the card reads as one element,
            // its name, with the actions above.
            Card(
                shape = shape,
                colors = colors,
                modifier = cardModifier.semantics(mergeDescendants = true) { customActions = editActions },
            ) { ShortcutCardContent(widget.feature, featureName) }
        } else {
            Card(onClick = onOpen, shape = shape, colors = colors, modifier = cardModifier) {
                ShortcutCardContent(widget.feature, featureName)
            }
        }
        if (isEditing) {
            // Top-start, as on the iPhone: the section's own remove button
            // overlays the top-end corner of the first row, and the two must
            // not sit under one another.
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-6).dp, y = (-6).dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
                    .clickable(enabled = !isDragging) { onRemove() }
                    // Three crosses that each said only "Remove" could not be
                    // told apart; named as a recipient chip's cross is.
                    .semantics { contentDescription = "$removeLabel $featureName" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(16.dp),
                )
            }
            // Decorative: the whole card drags, this only says so.
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(20.dp),
            )
        }
    }
}

@Composable
private fun ShortcutCardContent(feature: AppFeature, featureName: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(
            imageVector = feature.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = featureName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
