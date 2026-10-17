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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.HomeSection
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.navigation.icon

/**
 * Lets the user add to Home. Mirrors the iOS AddSectionSheet, section for
 * section:
 *  - the built-in section types not already present
 *  - a custom section, named here
 *  - the quick features (shortcut cards) not yet on Home; tapping one puts
 *    it in the first section that holds shortcuts, creating Quick widgets
 *    when there is none
 *
 * Rendered as a dialog so it matches the rest of the app (class detail,
 * login, etc.).
 */
@Composable
fun AddSectionDialog(
    existingSections: List<HomeSection>,
    onAddBuiltin: (HomeSection.HomeSectionType) -> Unit,
    onAddCustom: (title: String) -> Unit,
    onAddQuickFeature: (AppFeature) -> Unit,
    onDismiss: () -> Unit,
) {
    var customTitle by remember { mutableStateOf("") }

    val existingTypes = remember(existingSections) { existingSections.map { it.type }.toSet() }
    val availableBuiltin = remember(existingTypes) {
        listOf(
            HomeSection.HomeSectionType.TODAY_COURSES,
            HomeSection.HomeSectionType.UPCOMING_ASSIGNMENTS,
            HomeSection.HomeSectionType.QUICK_WIDGETS,
        ).filter { it !in existingTypes }
    }
    val addableFeatures = remember(existingSections) {
        HomeSectionLayout.addableQuickFeatures(existingSections)
    }

    TigerDuckDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.home_add_section_title),
        // No primary button: commits happen via the row taps below
        // (onAddBuiltin / onAddCustom / onAddQuickFeature). Cancel is the
        // only explicit action and lives in the secondary slot so it doesn't
        // visually dominate the dialog with a destructive label.
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (availableBuiltin.isNotEmpty()) {
                    Text(
                        stringResource(R.string.home_builtin_sections),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        availableBuiltin.forEach { type ->
                            AddRow(
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

                Text(
                    stringResource(R.string.home_section_add_quick_features),
                    style = MaterialTheme.typography.labelMedium
                )
                if (addableFeatures.isEmpty()) {
                    Text(
                        stringResource(R.string.home_section_all_widgets_added),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        addableFeatures.forEach { feature ->
                            AddRow(
                                title = stringResource(feature.displayNameRes),
                                icon = feature.icon,
                                onClick = { onAddQuickFeature(feature) },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun AddRow(
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

private fun iconFor(type: HomeSection.HomeSectionType): ImageVector = when (type) {
    HomeSection.HomeSectionType.TODAY_COURSES -> Icons.Filled.HourglassBottom
    HomeSection.HomeSectionType.UPCOMING_ASSIGNMENTS -> Icons.Filled.CheckCircle
    HomeSection.HomeSectionType.QUICK_WIDGETS -> Icons.Filled.GridView
    HomeSection.HomeSectionType.CUSTOM -> Icons.Filled.DashboardCustomize
}
