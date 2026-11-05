package org.ntust.app.tigerduck.ui.screen.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R

@Composable
internal fun SyncConflictDialog(
    conflicts: List<AssignmentSyncConflict>,
    onKeepLocal: () -> Unit,
    onKeepServer: () -> Unit,
) {
    @Composable
    fun statusLabel(s: String) = when (s) {
        "ignored", "archived" -> stringResource(R.string.sync_conflict_status_ignored)
        "locally_completed" -> stringResource(R.string.sync_conflict_status_completed)
        "none" -> stringResource(R.string.sync_conflict_status_none)
        else -> s
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.sync_conflict_title)) },
        text = {
            Column {
                Text(stringResource(R.string.sync_conflict_message), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                conflicts.forEach { c ->
                    Text(
                        stringResource(R.string.sync_conflict_item_header, c.kind, c.label),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.sync_conflict_item_detail, statusLabel(c.localStatus), statusLabel(c.serverStatus)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onKeepServer) {
                Text(stringResource(R.string.sync_conflict_use_server))
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepLocal) {
                Text(stringResource(R.string.sync_conflict_use_local))
            }
        },
    )
}
