package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The app's single popup style. Every confirmation / informational dialog flows
 * through here so they share one look: a Material 3 dialog container with a
 * centered (optional) icon and title, and **vertically stacked, full-width
 * buttons** — the affirmative on top, the dismiss action below — rather than the
 * stock side-by-side `AlertDialog` button row.
 *
 * Three body options, used alone or together:
 *  - [message] — centered plain text, for simple confirmations.
 *  - [content] — a `ColumnScope` slot for anything richer (forms with a
 *    `TextField`, info rows, interactive lists). The caller controls its
 *    alignment.
 *
 * Single-button dialogs omit [dismissText]; non-dismissable ones (unrecoverable
 * states) set [dismissable] to false so back-press and outside-tap are ignored.
 */
@Composable
fun TigerDuckDialog(
    onDismissRequest: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    message: String? = null,
    dismissText: String? = null,
    onDismiss: () -> Unit = onDismissRequest,
    icon: (@Composable () -> Unit)? = null,
    confirmEnabled: Boolean = true,
    confirmColors: ButtonColors = ButtonDefaults.buttonColors(),
    dismissable: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = dismissable,
            dismissOnClickOutside = dismissable,
        ),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier = modifier,
        ) {
            Column(
                modifier = Modifier
                    // Cap height so a long body scrolls instead of pushing the
                    // buttons off-screen on short displays.
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                icon?.invoke()

                title?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                }

                message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                content?.invoke(this)

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onConfirm,
                        enabled = confirmEnabled,
                        colors = confirmColors,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(confirmText) }

                    if (dismissText != null) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(dismissText) }
                    }
                }
            }
        }
    }
}
