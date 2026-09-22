package org.ntust.app.tigerduck.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The icon for what a swipe is about to *do*, given what the item is now.
 *
 * Shared by the mail list and the bulletin list because the two had drifted: Announcements paired
 * the "mark as unread" label with a Check, the opposite of what it announced. One function means
 * the icon and the label can only ever be chosen together.
 *
 * Envelopes rather than the previous curved Undo arrow, which read as "reply".
 */
fun readToggleIcon(isRead: Boolean): ImageVector =
    if (isRead) Icons.Filled.MarkEmailUnread else Icons.Filled.MarkEmailRead
