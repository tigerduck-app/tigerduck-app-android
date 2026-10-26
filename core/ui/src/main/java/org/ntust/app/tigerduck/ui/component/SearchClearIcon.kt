package org.ntust.app.tigerduck.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.ntust.app.tigerduck.core.strings.R

/**
 * A search field's trailing clear button, for its `trailingIcon`: there while [value] has text,
 * absent (null) otherwise. The same icon and label as the password and student-ID fields' own.
 */
fun searchClearIcon(value: String, onClear: () -> Unit): (@Composable () -> Unit)? =
    if (value.isEmpty()) null else {
        {
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Filled.Cancel,
                    contentDescription = stringResource(R.string.action_clear_text),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
