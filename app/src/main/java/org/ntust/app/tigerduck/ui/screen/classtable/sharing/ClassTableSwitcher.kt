package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.sharing.ImportedClassTables

/**
 * One row of the class table switcher, which the Class Table tab's menu and the class table's
 * credits label both list: "Add class table", the people in import order, then the user's own
 * class table under their student ID, a check on the one shown.
 */
sealed interface ClassTableSwitcherEntry {
    data object ImportClassTable : ClassTableSwitcherEntry

    data class Person(val id: String, val nickname: String, val isShown: Boolean) : ClassTableSwitcherEntry

    data class User(val name: String, val isShown: Boolean) : ClassTableSwitcherEntry

    companion object {
        /** The sections in the order a menu opening down the screen lists them. */
        fun sections(tables: ImportedClassTables, userName: String): List<List<ClassTableSwitcherEntry>> {
            val people = tables.people.map { Person(it.id, it.nickname, it.id == tables.shownPersonId) }
            return listOf(listOf(ImportClassTable), people, listOf(User(userName, tables.shownPersonId == null)))
                .filter { it.isNotEmpty() }
        }
    }
}

/** The switcher's rows as the items of a [androidx.compose.material3.DropdownMenu]. */
@Composable
fun ColumnScope.ClassTableSwitcherMenuItems(
    sections: List<List<ClassTableSwitcherEntry>>,
    onChoose: (ClassTableSwitcherEntry) -> Unit,
) {
    sections.forEachIndexed { index, section ->
        if (index > 0) HorizontalDivider()
        section.forEach { entry ->
            when (entry) {
                ClassTableSwitcherEntry.ImportClassTable -> DropdownMenuItem(
                    text = { Text(stringResource(R.string.class_table_import)) },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = { onChoose(entry) },
                )
                is ClassTableSwitcherEntry.Person -> SwitcherRow(entry.nickname, entry.isShown) { onChoose(entry) }
                is ClassTableSwitcherEntry.User -> SwitcherRow(entry.name, entry.isShown) { onChoose(entry) }
            }
        }
    }
}

@Composable
private fun SwitcherRow(title: String, isShown: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        // The check takes its place in every row, so the names line up.
        leadingIcon = {
            if (isShown) {
                Icon(Icons.Filled.Check, contentDescription = null)
            } else {
                androidx.compose.foundation.layout.Spacer(Modifier.size(24.dp))
            }
        },
        onClick = onClick,
        modifier = Modifier.semantics {
            role = Role.RadioButton
            selected = isShown
        },
    )
}
