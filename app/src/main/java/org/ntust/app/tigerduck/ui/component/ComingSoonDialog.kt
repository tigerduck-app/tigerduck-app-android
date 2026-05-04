package org.ntust.app.tigerduck.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.ntust.app.tigerduck.R

@Composable
fun ComingSoonDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.coming_soon_title)) },
        text = { Text(stringResource(R.string.coming_soon_message)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_got_it)) }
        }
    )
}
