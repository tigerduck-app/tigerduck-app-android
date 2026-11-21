// How what Home arranges moves: a section by its drag handle, and a section or a shortcut card by
// TalkBack's Move up and Move down, since TalkBack cannot drag (tigerduck-app/tigerduck-app#990).

package org.ntust.app.tigerduck.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.util.replaceIosArg

/**
 * Move up and Move down as actions TalkBack can perform, for the item at [index] of the [count]
 * shown, leaving out a move past either end, as iOS offers them. [onStep] makes the move, one
 * place up (-1) or down (1), on the layout as it is when the action runs, so an action offered
 * before the order changed neither passes an end nor moves from a place the item has left; it
 * answers where the item landed, which is said ("Position 2 of 4"), or null when nothing moved.
 */
@Composable
internal fun rememberMoveActions(
    index: Int,
    count: Int,
    onStep: (by: Int) -> HomeSectionLayout.Step?,
): List<CustomAccessibilityAction> {
    val view = LocalView.current
    val moveUp = stringResource(R.string.a11y_home_edit_move_up)
    val moveDown = stringResource(R.string.a11y_home_edit_move_down)
    val position = stringResource(R.string.a11y_home_edit_position)
    val latestOnStep by rememberUpdatedState(onStep)
    return remember(index, count, view, moveUp, moveDown, position) {
        fun step(by: Int): Boolean {
            val landed = latestOnStep(by) ?: return false
            // The move shows only as a new order, which TalkBack does not read out by itself.
            @Suppress("DEPRECATION")
            view.announceForAccessibility(
                position.replaceIosArg(1, landed.position.toLong()).replaceIosArg(2, landed.count.toLong())
            )
            return true
        }
        buildList {
            if (index in 1 until count) add(CustomAccessibilityAction(moveUp) { step(-1) })
            if (index in 0 until count - 1) add(CustomAccessibilityAction(moveDown) { step(1) })
        }
    }
}

/**
 * A section's drag handle while Home is arranged. The drag starts on first touch-and-move, no long
 * press needed; the rest of the outline is left to scroll the page. To TalkBack the handle is one
 * element named [title], with [moves] in place of the drag, and the hint it shows is left out.
 */
@Composable
internal fun SectionDragHandle(
    sectionId: String,
    title: String,
    moves: List<CustomAccessibilityAction>,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The drag runs inside pointerInput, which is keyed by the section and
    // not restarted by the swaps it makes, so it reaches the callbacks
    // through these: the ones it began with hold the order from before.
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    // The pill is what shows; the 48dp around it is what takes the drag.
    Box(
        modifier = modifier
            .pointerInput(sectionId) {
                detectDragGestures(
                    onDragStart = { currentOnDragStart() },
                    onDragEnd = { currentOnDragEnd() },
                    onDragCancel = { currentOnDragEnd() },
                    onDrag = { change, drag ->
                        change.consume()
                        currentOnDrag(drag.y)
                    },
                )
            }
            .clearAndSetSemantics {
                contentDescription = title
                customActions = moves
            }
            .minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                stringResource(R.string.home_section_drag_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
