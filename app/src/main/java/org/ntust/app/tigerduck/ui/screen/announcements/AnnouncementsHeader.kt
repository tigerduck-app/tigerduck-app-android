package org.ntust.app.tigerduck.ui.screen.announcements

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.PageMenu
import org.ntust.app.tigerduck.ui.component.PageSettings
import org.ntust.app.tigerduck.ui.component.PageSettingsItem
import org.ntust.app.tigerduck.ui.component.UnreadFilterButton

/**
 * After the title and dot: the unread filter, whose long press marks every bulletin shown read
 * while one of them is unread. It took the place of the mark-all-read button and of the push
 * settings' gear, which are the ⋮'s Settings now ([announcementsMenu]).
 */
@Composable
internal fun AnnouncementsFilter(
    unreadOnly: Boolean,
    hasUnread: Boolean,
    onMarkAllRead: () -> Unit,
    onUnreadOnlyChange: (Boolean) -> Unit,
) {
    UnreadFilterButton(
        unreadOnly = unreadOnly,
        label = stringResource(R.string.bulletin_show_unread_only_action),
        markAllReadLabel = stringResource(R.string.bulletin_mark_all_read_action),
        onUnreadOnlyChange = onUnreadOnlyChange,
        onMarkAllRead = onMarkAllRead.takeIf { hasUnread },
    )
}

/**
 * The bulletins' ⋮: Settings, which opens the push settings. Signed out there is none, as the
 * backend keeps bulletin subscriptions only for an account.
 */
@Composable
internal fun announcementsMenu(signedIn: Boolean): PageMenu? {
    if (!signedIn) return null
    return PageMenu(label = stringResource(R.string.action_more_actions)) { close ->
        PageSettingsItem(PageSettings.BULLETINS, close)
    }
}
