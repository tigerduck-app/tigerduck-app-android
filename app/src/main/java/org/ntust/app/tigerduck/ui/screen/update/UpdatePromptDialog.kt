package org.ntust.app.tigerduck.ui.screen.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.PendingUpdate
import org.ntust.app.tigerduck.util.replaceIosArg

/**
 * Three-action "an update is ready" prompt, mounted at the app root by
 * `MainActivity`'s [UpdatePromptHost]. Mirrors the iOS `UpdatePromptView`
 * triad (Update now / Later / Skip this version) so the two platforms share
 * the same UX and the same per-version-skip + 7-day cooldown semantics.
 *
 * Built as a standalone dialog rather than going through `TigerDuckDialog`:
 * that component is two-button only, and bolting a "neutral" slot onto it
 * just for this surface would muddy the shared dialog API for every other
 * caller.
 */
@Composable
fun UpdatePromptDialog(
    pending: PendingUpdate,
    onUpdateNow: () -> Unit,
    onLater: () -> Unit,
    onSkipThisVersion: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp),
                )

                Text(
                    text = stringResource(R.string.update_available_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )

                Text(
                    // Auto-generated copy carries iOS's `%1$@` placeholder;
                    // see IosPlaceholder.kt for the centralized shim.
                    text = stringResource(R.string.update_available_message)
                        .replaceIosArg(1, pending.displayVersion),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onUpdateNow,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.update_action_update_now)) }

                    OutlinedButton(
                        onClick = onLater,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.update_action_later)) }

                    // Skip reads as the destructive tail option without
                    // looking like the primary action — error-tinted text
                    // on a borderless TextButton matches the iOS sheet's
                    // "destructive" placement at the bottom of the stack.
                    TextButton(
                        onClick = onSkipThisVersion,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.update_action_skip_version),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
