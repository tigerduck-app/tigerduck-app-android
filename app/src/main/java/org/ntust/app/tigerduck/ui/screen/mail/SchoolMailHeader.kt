package org.ntust.app.tigerduck.ui.screen.mail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mail
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.mail.index.MailIndexProgress
import org.ntust.app.tigerduck.ui.component.ServerStatus
import org.ntust.app.tigerduck.ui.component.SyncStatusDot
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
