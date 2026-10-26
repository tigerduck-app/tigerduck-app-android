package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.ntust.app.tigerduck.R

/**
 * The ⋮ menu's items on the user's own class table, in the iPhone's order:
 * adding to it, sharing it and taking in someone else's, exporting it, then
 * resetting it, in red. Each action closes the menu itself before it acts.
 */
@Composable
internal fun ColumnScope.OwnClassTableMenuItems(
    onAddCourse: () -> Unit,
    shareEnabled: Boolean,
    onShare: () -> Unit,
    onImport: () -> Unit,
    exportEnabled: Boolean,
    onExport: () -> Unit,
    onReset: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.add_course_title)) },
        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
        onClick = onAddCourse,
    )
    // A place kept for club subscriptions, shown greyed out as the iPhone does.
    DropdownMenuItem(
        text = { Text(stringResource(R.string.class_table_subscribe_clubs)) },
        leadingIcon = { Icon(Icons.Filled.Groups, contentDescription = null) },
        onClick = {},
        enabled = false,
    )
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text(stringResource(R.string.class_table_share)) },
        leadingIcon = { Icon(Icons.Filled.QrCode, contentDescription = null) },
        onClick = onShare,
        enabled = shareEnabled,
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.class_table_import)) },
        leadingIcon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
        onClick = onImport,
    )
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text(stringResource(R.string.class_table_export_screenshot)) },
        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
        onClick = onExport,
        enabled = exportEnabled,
    )
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text(stringResource(R.string.class_table_reset_title), color = MaterialTheme.colorScheme.error) },
        leadingIcon = { Icon(Icons.Filled.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        onClick = onReset,
    )
}
