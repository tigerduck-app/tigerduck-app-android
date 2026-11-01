package org.ntust.app.tigerduck.ui.screen.home

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
