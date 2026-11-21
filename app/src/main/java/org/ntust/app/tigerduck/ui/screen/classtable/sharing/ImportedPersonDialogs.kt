package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.sharing.ImportedPerson
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog

/**
 * The nickname of someone else's class table, edited from [person]'s own, for the class table's ⋮
 * and the Class tables list alike.
 */
@Composable
internal fun ImportedPersonRenameDialog(person: ImportedPerson, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var draft by rememberSaveable(person.id) { mutableStateOf(person.nickname) }
    TigerDuckDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.class_table_rename_title),
        confirmText = stringResource(R.string.action_save),
        // The store keeps no blank nickname.
        confirmEnabled = draft.isNotBlank(),
        onConfirm = { onRename(draft) },
        dismissText = stringResource(R.string.action_cancel),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(stringResource(R.string.class_table_nickname)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Asks before [person]'s class table is deleted, for the class table's ⋮ and the Class tables list alike. */
@Composable
internal fun ImportedPersonDeleteDialog(person: ImportedPerson, onDelete: () -> Unit, onDismiss: () -> Unit) {
    TigerDuckDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.class_table_delete_user_confirm, person.nickname),
        confirmText = stringResource(R.string.class_table_delete),
        confirmColors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        onConfirm = onDelete,
        dismissText = stringResource(R.string.action_cancel),
    )
}
