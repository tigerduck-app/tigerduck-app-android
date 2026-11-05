package org.ntust.app.tigerduck.ui.screen.mail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.mail.index.MailIndexProgress
import org.ntust.app.tigerduck.ui.component.PageMenu
import org.ntust.app.tigerduck.ui.component.PageMenuSwitch
import org.ntust.app.tigerduck.ui.component.PageSettings
import org.ntust.app.tigerduck.ui.component.PageSettingsItem
import org.ntust.app.tigerduck.ui.component.ServerStatus
import org.ntust.app.tigerduck.ui.component.SyncStatusDot
import org.ntust.app.tigerduck.ui.component.UnreadFilterButton
import org.ntust.app.tigerduck.ui.component.statusText

@Composable
internal fun SchoolMailSyncDot(
    loadState: SchoolMailListViewModel.LoadState,
    authFailed: Boolean,
    indexProgress: MailIndexProgress?,
    isLoading: Boolean,
    syncedAtMs: Long?,
) {
    val failed = loadState as? SchoolMailListViewModel.LoadState.Failed
    val mailStatus = when {
        failed != null || authFailed -> ServerStatus.FAILED
        loadState is SchoolMailListViewModel.LoadState.Loaded -> ServerStatus.OK
        else -> ServerStatus.UNKNOWN
    }
    SyncStatusDot(
        status = mailStatus,
        label = stringResource(R.string.feature_school_mail),
        icon = Icons.Filled.Mail,
        // The state of the mail server, in the same words every other row of
        // this dot uses -- not the student ID, which is an identity and says
        // nothing about whether anything is reaching the server. The specific
        // failure still has a home: the list itself shows the error.
        //
        // While every mail downloads for search, which the first time takes a
        // while, the row says how far it has come -- or that it is syncing while
        // it still counts, rather than an "OK" that says nothing has started.
        // One cut short keeps saying so, stopped, until one gets through: mail is
        // still missing from search (#587). The dot turns only while mail
        // downloads, not while it counts.
        text = indexProgress?.let { indexProgressText(it) ?: stringResource(R.string.sync_status_syncing) }
            ?: statusText(mailStatus),
        isLoading = isLoading || indexProgress?.downloading() == true,
        syncedAtMs = syncedAtMs,
    )
}

/**
 * After the title and dot, signed in: the unread filter, whose long press marks every mail shown
 * read while one of them is unread, then the guide to using the mailbox in another app.
 */
@Composable
internal fun SchoolMailHeaderActions(
    state: SchoolMailListViewModel.UiState,
    viewModel: SchoolMailListViewModel,
    onOpenGuide: () -> Unit,
) {
    UnreadFilterButton(
        unreadOnly = state.unreadOnly,
        label = stringResource(R.string.school_mail_unread_only),
        markAllReadLabel = stringResource(R.string.school_mail_mark_all_read),
        onUnreadOnlyChange = viewModel::setUnreadOnly,
        onMarkAllRead = if (state.displayed.any { !it.summary.flags.seen }) viewModel::markAllRead else null,
    )
    IconButton(onClick = onOpenGuide) {
        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = stringResource(R.string.school_mail_use_other_app))
    }
}

/**
 * School Mail's ⋮, signed in, as iOS's: New mail notifications, the switch its notification page
 * holds, written through that page's own view model, then Settings, School Mail's settings page.
 * The switch is greyed out where that page greys it, after the server rejected the password.
 *
 * Signed in means School Mail's own sign-in ([SchoolMailListViewModel.signedIn]), which
 * [SchoolMailScreen] draws the menu on, and not the school's. On Android School Mail is an account
 * of its own: a school sign-out leaves it signed in and usable, and its menu with it.
 */
@Composable
internal fun schoolMailMenu(settings: SchoolMailSettingsViewModel): PageMenu =
    PageMenu(label = stringResource(R.string.action_more_actions), onOpen = settings::refresh) { close ->
        val ui by settings.ui.collectAsStateWithLifecycle()
        val enabled by settings.notificationsToggleEnabled.collectAsStateWithLifecycle()
        PageMenuSwitch(
            label = stringResource(R.string.school_mail_settings_notifications),
            checked = ui.notificationsEnabled,
            enabled = enabled,
            onCheckedChange = {
                close()
                settings.setNotifications(it)
            },
        )
        HorizontalDivider()
        PageSettingsItem(PageSettings.MAIL, close)
    }
