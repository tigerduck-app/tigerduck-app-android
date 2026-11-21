package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import org.ntust.app.tigerduck.data.sharing.ImportedPerson.Placement

/**
 * One row of the class table switcher, which the Class Table tab's menu and the class table's
 * credits label both list, as on the iPhone: "Add class table"; the user's own class table under
 * their student ID, then the people in the double tap's rotation; the people of the long-press
 * menu; then an Other submenu. A check marks the one shown, and a group with no one in it is left
 * out.
 */
sealed interface ClassTableSwitcherEntry {
    /** A row that picks something when chosen. */
    sealed interface Choice : ClassTableSwitcherEntry

    data object ImportClassTable : Choice

    data class Person(val id: String, val nickname: String, val isShown: Boolean) : Choice

    data class User(val name: String, val isShown: Boolean) : Choice

    /** The Other submenu, which opens to list [people]. */
    data class Others(val people: List<Person>) : ClassTableSwitcherEntry

    companion object {
        /** The sections in the order a menu opening down the screen lists them. */
        fun sections(tables: ImportedClassTables, userName: String): List<List<ClassTableSwitcherEntry>> {
            fun people(placement: Placement) = tables.people.filter { it.placement == placement }
                .map { Person(it.id, it.nickname, it.id == tables.shownPersonId) }
            val others = people(Placement.OTHER)
            return listOf(
                listOf(ImportClassTable),
                listOf(User(userName, tables.shownPersonId == null)) + people(Placement.ROTATION),
                people(Placement.LONG_PRESS_MENU),
                if (others.isEmpty()) emptyList() else listOf(Others(others)),
            ).filter { it.isNotEmpty() }
        }
    }
}

/** The switcher's rows as the items of a [androidx.compose.material3.DropdownMenu]. */
@Composable
fun ColumnScope.ClassTableSwitcherMenuItems(
    sections: List<List<ClassTableSwitcherEntry>>,
    onChoose: (ClassTableSwitcherEntry.Choice) -> Unit,
) {
    sections.forEachIndexed { index, section ->
        if (index > 0) HorizontalDivider()
        section.forEach { entry ->
            when (entry) {
                ClassTableSwitcherEntry.ImportClassTable -> DropdownMenuItem(
                    text = { Text(stringResource(R.string.class_table_import)) },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = { onChoose(ClassTableSwitcherEntry.ImportClassTable) },
                )
                is ClassTableSwitcherEntry.Person -> SwitcherRow(entry.nickname, entry.isShown) { onChoose(entry) }
                is ClassTableSwitcherEntry.User -> SwitcherRow(entry.name, entry.isShown) { onChoose(entry) }
                is ClassTableSwitcherEntry.Others -> OthersRow(entry, onChoose)
            }
        }
    }
}

/** "Other", which opens a menu of its own beside it with the people placed there. */
@Composable
private fun OthersRow(others: ClassTableSwitcherEntry.Others, onChoose: (ClassTableSwitcherEntry.Choice) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.class_table_list_section_other)) },
            // No check of its own: the one shown is checked inside.
            leadingIcon = { Spacer(Modifier.size(24.dp)) },
            trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(12.dp)) {
            others.people.forEach { person ->
                SwitcherRow(person.nickname, person.isShown) {
                    open = false
                    onChoose(person)
                }
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
                Spacer(Modifier.size(24.dp))
            }
        },
        onClick = onClick,
        modifier = Modifier.semantics {
            role = Role.RadioButton
            selected = isShown
        },
    )
}
