package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import org.ntust.app.tigerduck.R

/**
 * Trailing icons cluster for a password `OutlinedTextField`: a clear button
 * (only when the field has content) plus a visibility toggle that swaps the
 * field's `visualTransformation` between `PasswordVisualTransformation` and
 * `VisualTransformation.None`.
 *
 * The eye `IconButton` carries a state-aware `contentDescription` ("Show
 * password" / "Hide password") so TalkBack announces what the toggle will do
 * on the next tap.
 */
@Composable
fun PasswordTrailingIcons(
    password: String,
    passwordVisible: Boolean,
    onClear: () -> Unit,
    onToggleVisibility: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        if (password.isNotEmpty()) {
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Filled.Cancel,
                    contentDescription = stringResource(R.string.action_clear_text),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(
            onClick = onToggleVisibility,
            enabled = password.isNotEmpty(),
        ) {
            Icon(
                imageVector = if (passwordVisible) Icons.Filled.Visibility
                else Icons.Filled.VisibilityOff,
                contentDescription = stringResource(
                    if (passwordVisible) R.string.password_hide
                    else R.string.password_show
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
