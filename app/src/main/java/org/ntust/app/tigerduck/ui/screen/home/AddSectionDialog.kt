package org.ntust.app.tigerduck.ui.screen.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.HomeSection

/**
 * Lets the user add a new home section. Mirrors the iOS AddSectionSheet:
 *  - surface any built-in section types not already present
 *  - allow naming a custom section
 *
 * Rendered as an AlertDialog so it matches the rest of the app (class detail,
 * login, etc.).
 */
@Composable
fun AddSectionDialog(
    existingSections: List<HomeSection>,
    onAddBuiltin: (HomeSection.HomeSectionType) -> Unit,
    onAddCustom: (title: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var customTitle by remember { mutableStateOf("") }

    val existingTypes = remember(existingSections) { existingSections.map { it.type }.toSet() }
    val availableBuiltin = remember(existingTypes) {
        listOf(
            HomeSection.HomeSectionType.TODAY_COURSES,
            HomeSection.HomeSectionType.UPCOMING_ASSIGNMENTS,
        ).filter { it !in existingTypes }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_add_section_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (availableBuiltin.isNotEmpty()) {
                    Text(
                        stringResource(R.string.home_builtin_sections),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        availableBuiltin.forEach { type ->
                            BuiltinSectionRow(
                                title = stringResource(type.defaultTitleRes),
                                icon = iconFor(type),
                                onClick = { onAddBuiltin(type) },
                            )
                        }
                    }
                }

                Text(
                    stringResource(R.string.home_custom_section),
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = customTitle,
                        onValueChange = { customTitle = it },
                        label = { Text(stringResource(R.string.home_section_name)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            val trimmed = customTitle.trim()
                            if (trimmed.isNotEmpty()) onAddCustom(trimmed)
                        },
                        enabled = customTitle.isNotBlank(),
                    ) {
                        Icon(
                            Icons.Filled.AddCircle,
                            contentDescription = stringResource(R.string.action_add),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun BuiltinSectionRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Filled.AddCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Suppress("DEPRECATION")
private fun iconFor(type: HomeSection.HomeSectionType): ImageVector = when (type) {
    HomeSection.HomeSectionType.TODAY_COURSES -> Icons.Filled.HourglassBottom
    HomeSection.HomeSectionType.UPCOMING_ASSIGNMENTS -> Icons.Filled.CheckCircle
    HomeSection.HomeSectionType.QUICK_WIDGETS -> Icons.Filled.DashboardCustomize
    HomeSection.HomeSectionType.CUSTOM -> Icons.Filled.DashboardCustomize
}
