package org.ntust.app.tigerduck.ui.screen.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.data.model.WhatsNewContent

/**
 * Shown on the first launch after an upgrade. Content comes from
 * `assets/whatsnew.json` via `WhatsNewRepository`. Uses the shared
 * [TigerDuckDialog] so it matches every other popup in the app.
 */
@Composable
fun WhatsNewDialog(
    content: WhatsNewContent,
    onDismiss: () -> Unit,
) {
    TigerDuckDialog(
        onDismissRequest = onDismiss,
        title = content.title.orEmpty(),
        // Framework string — localized by the platform in every locale.
        confirmText = stringResource(android.R.string.ok),
        onConfirm = onDismiss,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content.highlights.orEmpty().forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium)
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
    )
}
