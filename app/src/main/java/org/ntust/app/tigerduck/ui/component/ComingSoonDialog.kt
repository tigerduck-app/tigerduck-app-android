package org.ntust.app.tigerduck.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.ntust.app.tigerduck.R

@Composable
fun ComingSoonDialog(onDismiss: () -> Unit) {
    TigerDuckDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.coming_soon_title),
        message = stringResource(R.string.coming_soon_message),
        confirmText = stringResource(R.string.action_got_it),
        onConfirm = onDismiss,
    )
}
