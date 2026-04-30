package org.ntust.app.tigerduck.announcements

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionSettingsScreen(
    onBack: () -> Unit,
    viewModel: SubscriptionSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<EditingTarget?>(null) }

    LaunchedEffect(state.saveState) {
        if (state.saveState is SubscriptionSettingsViewModel.SaveState.Saved) {
            // Auto-dismiss the "saved" badge after a beat so it doesn't
            // linger on the toolbar forever.
            kotlinx.coroutines.delay(1500)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bulletin_notifications_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    val saving = state.saveState is SubscriptionSettingsViewModel.SaveState.Saving
                    TextButton(
                        onClick = viewModel::save,
                        enabled = state.isDirty && !saving,
                    ) {
                        if (saving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.action_done))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            val maxRules = 32
            if (state.rules.size < maxRules) {
                FloatingActionButton(onClick = {
                    editing = EditingTarget(SubscriptionRule(), replacingIndex = null)
                }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.bulletin_rule_add_action))
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val ls = state.loadState) {
                SubscriptionSettingsViewModel.LoadState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.bulletin_rules_load_loading))
                    }
                }
                is SubscriptionSettingsViewModel.LoadState.Failed -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.bulletin_rules_load_failed))
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = viewModel::load) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
                SubscriptionSettingsViewModel.LoadState.Loaded -> {
                    RuleList(
                        state = state,
                        onToggle = viewModel::toggleEnabled,
                        onDelete = viewModel::deleteRule,
                        onEdit = { idx ->
                            editing = EditingTarget(state.rules[idx], replacingIndex = idx)
                        },
                    )
                }
            }
            (state.saveState as? SubscriptionSettingsViewModel.SaveState.Failed)?.let {
                Snackbar(modifier = Modifier.padding(16.dp).align(Alignment.BottomCenter)) {
                    Text(it.message)
                }
            }
        }
    }

    editing?.let { target ->
        RuleEditorDialog(
            initial = target.rule,
            taxonomy = state.taxonomy,
            onDismiss = { editing = null },
            onSave = { rule ->
                viewModel.upsertRule(rule, target.replacingIndex)
                editing = null
            },
        )
    }
}

private data class EditingTarget(val rule: SubscriptionRule, val replacingIndex: Int?)

@Composable
private fun RuleList(
    state: SubscriptionSettingsViewModel.State,
    onToggle: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onEdit: (Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.bulletin_rules_header),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (state.rules.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.bulletin_rules_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        items(state.rules.size) { idx ->
            val rule = state.rules[idx]
            RuleRow(
                rule = rule,
                taxonomy = state.taxonomy,
                onClick = { onEdit(idx) },
                onToggle = { onToggle(idx) },
                onDelete = { onDelete(idx) },
            )
        }
        item {
            Text(
                text = stringResource(R.string.bulletin_rules_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun RuleRow(
    rule: SubscriptionRule,
    taxonomy: TaxonomyResponse?,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.title(taxonomy),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                rule.subtitle(taxonomy)?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (!rule.enabled) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.bulletin_rule_disabled_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.bulletin_rule_delete_action),
                )
            }
        }
    }
}

@Composable
private fun SubscriptionRule.title(taxonomy: TaxonomyResponse?): String {
    name?.takeIf { it.isNotBlank() }?.let { return it }
    val orgsLabel = orgs.joinedLabels(taxonomy?.orgs)
    val tagsLabel = tags.joinedLabels(taxonomy?.tags)
    val joiner = stringResource(
        if (mode == "AND") R.string.bulletin_rule_join_and else R.string.bulletin_rule_join_or
    )
    return when {
        orgs.isNotEmpty() && tags.isNotEmpty() -> "$orgsLabel$joiner$tagsLabel"
        orgs.isNotEmpty() -> orgsLabel
        tags.isNotEmpty() -> tagsLabel
        else -> stringResource(R.string.bulletin_rule_all_title)
    }
}

@Composable
private fun SubscriptionRule.subtitle(taxonomy: TaxonomyResponse?): String? {
    if (name.isNullOrBlank()) return null
    val orgsLabel = orgs.joinedLabels(taxonomy?.orgs)
    val tagsLabel = tags.joinedLabels(taxonomy?.tags)
    val joiner = stringResource(
        if (mode == "AND") R.string.bulletin_rule_join_and else R.string.bulletin_rule_join_or
    )
    return when {
        orgs.isNotEmpty() && tags.isNotEmpty() -> "$orgsLabel$joiner$tagsLabel"
        orgs.isNotEmpty() -> orgsLabel
        tags.isNotEmpty() -> tagsLabel
        else -> stringResource(R.string.bulletin_rule_all_title)
    }
}

private fun List<String>.joinedLabels(catalog: List<OrgLabel>?): String =
    joinToString(", ") { id -> catalog?.firstOrNull { it.id == id }?.label ?: id }

@JvmName("joinedLabelsTag")
private fun List<String>.joinedLabels(catalog: List<TagLabel>?): String =
    joinToString(", ") { id -> catalog?.firstOrNull { it.id == id }?.label ?: id }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditorDialog(
    initial: SubscriptionRule,
    taxonomy: TaxonomyResponse?,
    onDismiss: () -> Unit,
    onSave: (SubscriptionRule) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name.orEmpty()) }
    var orgs by remember { mutableStateOf(initial.orgs.toSet()) }
    var tags by remember { mutableStateOf(initial.tags.toSet()) }
    var mode by remember { mutableStateOf(initial.mode) }
    var enabled by remember { mutableStateOf(initial.enabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.bulletin_rule_edit_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.bulletin_rule_editor_name_section)) },
                    placeholder = { Text(stringResource(R.string.bulletin_rule_editor_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(R.string.bulletin_rule_editor_dept_section),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowChips(
                    items = taxonomy?.orgs?.map { it.id to it.label }.orEmpty(),
                    selected = orgs,
                    onToggle = { id ->
                        orgs = if (id in orgs) orgs - id else orgs + id
                    },
                )

                Text(
                    text = stringResource(R.string.bulletin_rule_editor_tag_section),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowChips(
                    items = taxonomy?.tags?.map { it.id to it.label }.orEmpty(),
                    selected = tags,
                    onToggle = { id ->
                        tags = if (id in tags) tags - id else tags + id
                    },
                )

                Text(
                    text = stringResource(R.string.bulletin_rule_editor_mode_section),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = mode == "AND",
                        onClick = { mode = "AND" },
                        label = { Text("AND") },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = mode == "OR",
                        onClick = { mode = "OR" },
                        label = { Text("OR") },
                    )
                }
                Text(
                    text = stringResource(
                        if (mode == "AND") R.string.bulletin_rule_editor_mode_and_footer
                        else R.string.bulletin_rule_editor_mode_or_footer
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.bulletin_rule_editor_enable_toggle))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
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
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun FlowChips(
    items: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    if (items.isEmpty()) {
        Text(
            text = stringResource(R.string.bulletin_taxonomy_picker_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        return
    }
    // Compose Material3 doesn't ship FlowRow in stable; use a hand-wrap by
    // chunking. Five-per-row is enough for our taxonomy size and avoids an
    // additional dep on accompanist-flowlayout.
    val rowSize = 3
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.chunked(rowSize).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (id, label) ->
                    FilterChip(
                        selected = id in selected,
                        onClick = { onToggle(id) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
}
