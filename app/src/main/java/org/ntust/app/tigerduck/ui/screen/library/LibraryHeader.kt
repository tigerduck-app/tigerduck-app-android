package org.ntust.app.tigerduck.ui.screen.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.ServerStatus
import org.ntust.app.tigerduck.ui.component.SyncStatusDot

/**
 * The state used to be a hand-drawn circle with the word beside it. The word is what the dot's own
 * popup says when you tap it, and every other page's header spends that space on nothing, so this
 * now draws the shared mark, which also means it can be tapped, dims when idle and carries the pull
 * ring like the rest.
 *
 * A failed refresh outranks being signed in: the session can be valid while the pass request is the
 * thing that broke, and the error banner below says which.
 */
@Composable
internal fun LibrarySyncDot(failed: Boolean, isLoggedIn: Boolean, isLoading: Boolean, syncedAtMs: Long?) {
    SyncStatusDot(
        status = when {
            failed -> ServerStatus.FAILED
            isLoggedIn -> ServerStatus.OK
            else -> ServerStatus.UNKNOWN
        },
        label = stringResource(R.string.feature_library),
        icon = Icons.Outlined.LocalLibrary,
        text = if (isLoggedIn) stringResource(R.string.library_status_signed_in)
        else stringResource(R.string.library_status_not_signed_in),
        isLoading = isLoading,
        syncedAtMs = syncedAtMs,
    )
}
