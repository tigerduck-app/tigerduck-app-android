package org.ntust.app.tigerduck.announcements

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R

/**
 * Full-screen rule editor. Replaces the prior `AlertDialog`-based editor
 * whose tall content (chip flow rows + segmented mode + bottom Switch) caused
 * the enable toggle to overlap the chip selectors at certain screen sizes —
 * the original "Edit rule page is broken" report.
 *
 * Lays out as a stack of clearly-separated sections in a `verticalScroll`,
 * matching iOS `SubscriptionRuleEditorView`'s `Form` layout. The Save action
 * lives in the top app bar so it's always reachable regardless of how tall
 * the picker grids grow.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SubscriptionRuleEditorScreen(
    initial: SubscriptionRule,
    isNew: Boolean,
    taxonomy: TaxonomyResponse?,
    onDone: (SubscriptionRule) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name.orEmpty()) }
    var orgs by remember { mutableStateOf(initial.orgs.toSet()) }
    var tags by remember { mutableStateOf(initial.tags.toSet()) }
    var mode by remember { mutableStateOf(initial.mode) }
    var enabled by remember { mutableStateOf(initial.enabled) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(
                        if (isNew) R.string.bulletin_rule_add_title
                        else R.string.bulletin_rule_edit_title
                    ))
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(onClick = {
                        onDone(
                            initial.copy(
                                name = name.trim().takeIf { it.isNotEmpty() },
                                orgs = orgs.toList(),
                                tags = tags.toList(),
                                mode = mode,
                                enabled = enabled,
                            )
                        )
                    }) {
                        Text(stringResource(R.string.action_done))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // --- Name
            SectionHeader(stringResource(R.string.bulletin_rule_editor_name_section))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(R.string.bulletin_rule_editor_name_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // --- Department picker
            SectionHeader(stringResource(R.string.bulletin_rule_editor_dept_section))
            ChipFlow(
                items = taxonomy?.orgs?.map { it.id to it.label }.orEmpty(),
                selected = orgs,
                onToggle = { id -> orgs = if (id in orgs) orgs - id else orgs + id },
                emptyHint = stringResource(R.string.bulletin_taxonomy_picker_hint),
            )

            // --- Tag picker
            SectionHeader(stringResource(R.string.bulletin_rule_editor_tag_section))
            ChipFlow(
                items = taxonomy?.tags?.map { it.id to it.label }.orEmpty(),
                selected = tags,
                onToggle = { id -> tags = if (id in tags) tags - id else tags + id },
                emptyHint = stringResource(R.string.bulletin_taxonomy_picker_hint),
            )

            // --- Mode (AND / OR)
            SectionHeader(stringResource(R.string.bulletin_rule_editor_mode_section))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == "AND",
                    onClick = { mode = "AND" },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("AND") }
                SegmentedButton(
                    selected = mode == "OR",
                    onClick = { mode = "OR" },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("OR") }
            }
            Text(
                text = stringResource(
                    if (mode == "AND") R.string.bulletin_rule_editor_mode_and_footer
                    else R.string.bulletin_rule_editor_mode_or_footer
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            // --- Enable toggle, isolated in its own row well below the mode
            // selector so the two never compete for the same vertical space.
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.bulletin_rule_editor_enable_toggle),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            // --- Delete (existing rules only)
            if (onDelete != null) {
                HorizontalDivider()
                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.bulletin_rule_delete_action))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(
    items: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    emptyHint: String,
) {
    if (items.isEmpty()) {
        Text(
            text = emptyHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        return
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { (id, label) ->
            FilterChip(
                selected = id in selected,
                onClick = { onToggle(id) },
                label = { Text(label) },
            )
        }
    }
}
