package org.ntust.app.tigerduck.ui.screen.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.PageHeaderPosition
import org.ntust.app.tigerduck.ui.component.PageMenu
import org.ntust.app.tigerduck.ui.component.PageScaffold
import org.ntust.app.tigerduck.ui.component.PageScope
import org.ntust.app.tigerduck.ui.component.ServerKind
import org.ntust.app.tigerduck.ui.component.SyncStatusDot

/** What Home's header offers in place of the sync dot while the page is being arranged. */
@Composable
internal fun HomeEditActions(onAdd: () -> Unit, onDone: () -> Unit) {
    IconButton(onClick = onAdd) {
        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_add_section_title))
    }
    TextButton(onClick = onDone) {
        Text(stringResource(R.string.action_done), fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Home's page: the greeting for a title, the sync dot and [menu] after it, and Add and Done in their
 * place while the sections are arranged. Plain values in, so the page and its test both go through
 * this wiring and not a copy of it.
 */
@Composable
internal fun HomeShell(
    greeting: String,
    isEditing: Boolean,
    isLoading: Boolean,
    syncedAtMs: Long?,
    onAdd: () -> Unit,
    onDone: () -> Unit,
    snackbarHost: @Composable () -> Unit,
    menu: PageMenu? = null,
    content: @Composable PageScope.(PaddingValues) -> Unit,
) {
    PageScaffold(
        title = greeting,
        headerPosition = PageHeaderPosition.InContent,
        syncDot = {
            if (!isEditing) {
                SyncStatusDot(
                    servers = listOf(ServerKind.MOODLE, ServerKind.BACKEND),
                    isLoading = isLoading,
                    syncedAtMs = syncedAtMs,
                )
            }
        },
        actions = { if (isEditing) HomeEditActions(onAdd = onAdd, onDone = onDone) },
        menu = menu.takeIf { !isEditing },
        snackbarHost = snackbarHost,
        content = content,
    )
}
