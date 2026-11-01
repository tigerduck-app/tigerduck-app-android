package org.ntust.app.tigerduck.ui.screen.announcements

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.ntust.app.tigerduck.R

/** After the title and dot: mark all read while filtering to unread, the filter, and the push settings signed in. */
@Composable
internal fun AnnouncementsHeaderActions(
    unreadOnly: Boolean,
    hasUnread: Boolean,
    signedIn: Boolean,
    onMarkAllRead: () -> Unit,
    onUnreadOnlyChange: (Boolean) -> Unit,
    onOpenSubscriptions: () -> Unit,
) {
    if (unreadOnly && hasUnread) {
        IconButton(onClick = onMarkAllRead) {
            Icon(
                Icons.Filled.DoneAll,
                contentDescription = stringResource(R.string.bulletin_mark_all_read_action),
            )
        }
    }
    val unreadOnlyLabel = stringResource(R.string.bulletin_show_unread_only_action)
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .toggleable(
                value = unreadOnly,
                role = Role.Switch,
                onValueChange = onUnreadOnlyChange,
            )
            .semantics {
                contentDescription = unreadOnlyLabel
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (unreadOnly) Icons.Filled.FilterAlt else Icons.Filled.FilterAltOff,
            contentDescription = null,
        )
    }
    if (signedIn) {
        IconButton(onClick = onOpenSubscriptions) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = stringResource(R.string.bulletin_notifications_title),
            )
        }
    }
}
