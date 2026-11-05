package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp

/**
 * A list's unread filter, which Bulletins and School Mail share, as iOS's `UnreadFilterButton`: a
 * tap shows unread only or everything again, and a long press offers Mark all as read for what the
 * list shows. TalkBack reads [label] with whether the filter is on, and offers Mark all as read as
 * an action of its own.
 *
 * @param onMarkAllRead null while nothing shown is unread: the item is greyed out and the action
 *   left out.
 */
@Composable
fun UnreadFilterButton(
    unreadOnly: Boolean,
    label: String,
    markAllReadLabel: String,
    onUnreadOnlyChange: (Boolean) -> Unit,
    onMarkAllRead: (() -> Unit)?,
) {
    var offering by remember { mutableStateOf(false) }
    Box {
        Box(
            // An icon button's size and ripple, with a long press of its own.
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(40.dp)
                .clip(CircleShape)
                .combinedClickable(
                    role = Role.Switch,
                    onLongClickLabel = markAllReadLabel,
                    onLongClick = { offering = true },
                    onClick = { onUnreadOnlyChange(!unreadOnly) },
                )
                .semantics {
                    contentDescription = label
                    toggleableState = ToggleableState(unreadOnly)
                    if (onMarkAllRead != null) {
                        customActions = listOf(CustomAccessibilityAction(markAllReadLabel) { onMarkAllRead(); true })
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (unreadOnly) Icons.Filled.FilterAlt else Icons.Filled.FilterAltOff, contentDescription = null)
        }
        DropdownMenu(expanded = offering, onDismissRequest = { offering = false }, shape = PageDefaults.MenuShape) {
            DropdownMenuItem(
                text = { Text(markAllReadLabel) },
                leadingIcon = { Icon(Icons.Filled.DoneAll, contentDescription = null) },
                enabled = onMarkAllRead != null,
                onClick = {
                    offering = false
                    onMarkAllRead?.invoke()
                },
            )
        }
    }
}
