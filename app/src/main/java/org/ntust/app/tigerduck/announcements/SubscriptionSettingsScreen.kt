package org.ntust.app.tigerduck.announcements

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.notification.AppPermission
import org.ntust.app.tigerduck.notification.SystemPermissions
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.R
import java.text.DateFormat
import java.util.Date

private data class EditingTarget(
    val rule: SubscriptionRule,
    val replacingIndex: Int?,
)

private val isFdroidFlavor: Boolean
    get() = BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionSettingsScreen(
    onBack: () -> Unit,
    viewModel: SubscriptionSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<EditingTarget?>(null) }

    // Inline editor route — same Composable hosts both the list and the
    // editor so the editor never has to round-trip through the nav graph or
    // re-fetch the rule snapshot. Keeps the "Edit rule" page on a real
    // Scaffold (with its own top bar) without the AlertDialog's overflow
    // bug that produced the original "toggle overlapping selector" report.
    editing?.let { target ->
        // System back must collapse the editor back to the settings page,
        // not pop the whole route up to the announcements list.
        BackHandler(enabled = true) { editing = null }
        SubscriptionRuleEditorScreen(
            initial = target.rule,
            isNew = target.replacingIndex == null,
            taxonomy = state.taxonomy,
            onCancel = { editing = null },
            onDone = { rule ->
                viewModel.upsertRule(rule, target.replacingIndex)
                editing = null
            },
            onDelete = target.replacingIndex?.let { idx ->
                {
                    viewModel.deleteRule(idx)
                    editing = null
                }
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bulletin_notifications_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    if (isFdroidFlavor) {
                        FdroidNoticeCard()
                    } else {
                        PushStatusCard(state.diagnostic)
                    }
                }

                if (!isFdroidFlavor) {
                    item {
                        NotificationPermissionCard(viewModel.systemPermissions)
                    }
                }

                if (!isFdroidFlavor) {
                    item {
                        Text(
                            text = stringResource(R.string.bulletin_rules_header),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }

                    when (val ls = state.loadState) {
                        SubscriptionSettingsViewModel.LoadState.Loading -> {
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.bulletin_rules_load_loading))
                                }
                            }
                        }
                        is SubscriptionSettingsViewModel.LoadState.Failed -> {
                            item {
                                Column {
                                    Text(stringResource(R.string.bulletin_rules_load_failed))
                                    Spacer(Modifier.height(8.dp))
                                    TextButton(onClick = viewModel::load) {
                                        Text(stringResource(R.string.action_retry))
                                    }
                                }
                            }
                        }
                        SubscriptionSettingsViewModel.LoadState.Loaded -> {
                            items(state.rules.size) { idx ->
                                val rule = state.rules[idx]
                                RuleRow(
                                    rule = rule,
                                    taxonomy = state.taxonomy,
                                    onClick = {
                                        editing = EditingTarget(rule, replacingIndex = idx)
                                    },
                                    onToggle = { viewModel.toggleEnabled(idx) },
                                    onDelete = { viewModel.deleteRule(idx) },
                                )
                            }
                            // Inline "Add rule" entry, mirrors iOS layout.
                            // Capped at 32 rules to match server-side ceiling.
                            val maxRules = 32
                            if (state.rules.size < maxRules) {
                                item {
                                    AddRuleRow(onClick = {
                                        editing = EditingTarget(SubscriptionRule(), replacingIndex = null)
                                    })
                                }
                            }
                            item {
                                Text(
                                    text = stringResource(R.string.bulletin_rules_footer),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                            if (state.rules.isEmpty()) {
                                item { DefaultRulesBanner(state.taxonomy, viewModel::applyDefaultRules) }
                            }
                        }
                    }
                }
            }

            (state.saveState as? SubscriptionSettingsViewModel.SaveState.Failed)?.let {
                Snackbar(
                    modifier = Modifier.padding(16.dp).align(Alignment.BottomCenter),
                    action = {
                        TextButton(onClick = viewModel::clearSaveState) {
                            Text(stringResource(R.string.settings_acknowledged))
                        }
                    },
                ) {
                    Text(it.message)
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Push status card (Play flavor only)
// -----------------------------------------------------------------------------

@Composable
private fun PushStatusCard(diagnostic: org.ntust.app.tigerduck.push.PushDiagnostic) {
    val context = LocalContext.current
    fun checkNotificationGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

    var permissionGranted by remember { mutableStateOf(checkNotificationGranted()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> permissionGranted = granted }

    // Re-check on ON_RESUME so revoking POST_NOTIFICATIONS in system settings
    // and returning to this screen reflects the current grant, not the stale
    // value captured on first composition.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = checkNotificationGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ContentCard {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.push_server_status_section),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(8.dp))
            StatusRow(
                label = stringResource(R.string.bulletin_push_status_label),
                ok = permissionGranted,
                okText = stringResource(R.string.permission_granted),
                badText = stringResource(R.string.bulletin_push_status_denied),
            )
            StatusRow(
                label = stringResource(R.string.push_server_status_device_registration),
                ok = diagnostic.isRegistered,
                okText = stringResource(R.string.bulletin_push_status_registration_done),
                badText = if (diagnostic.hasFcmToken) {
                    stringResource(R.string.push_server_status_waiting_token)
                } else {
                    stringResource(R.string.bulletin_push_status_registration_pending)
                },
            )
            diagnostic.lastRegistrationAt?.let { ts ->
                Spacer(Modifier.height(4.dp))
                LabeledText(
                    label = stringResource(R.string.push_server_last_registration),
                    value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ts)),
                )
            }
            diagnostic.lastError?.let { msg ->
                Spacer(Modifier.height(4.dp))
                LabeledText(
                    label = stringResource(R.string.push_server_latest_error),
                    value = msg,
                    valueColor = MaterialTheme.colorScheme.error,
                )
            }

            if (!permissionGranted) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            openAppSettings(context)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.bulletin_push_reopen_settings))
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, okText: String, badText: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = if (ok) Color(0xFF34C759) else Color(0xFFFF9500),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = if (ok) okText else badText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun LabeledText(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = valueColor)
    }
}

private fun openAppSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    runCatching { context.startActivity(intent) }
}

// -----------------------------------------------------------------------------
// F-Droid notice — replaces the entire push UI on the FOSS flavor
// -----------------------------------------------------------------------------

@Composable
private fun FdroidNoticeCard() {
    ContentCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = Color(0xFFFF9500),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.bulletin_fdroid_no_push_title),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.bulletin_fdroid_no_push_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Default-rules opt-in
// -----------------------------------------------------------------------------

@Composable
private fun DefaultRulesBanner(
    taxonomy: TaxonomyResponse?,
    onApply: () -> Unit,
) {
    val canApply = (taxonomy?.defaultTags?.isNotEmpty() == true)
    ContentCard {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.bulletin_default_rules_footer),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (canApply) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onApply,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.AutoFixHigh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.bulletin_apply_default_rules_action))
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Rule list row
// -----------------------------------------------------------------------------

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
                    text = ruleTitle(rule, taxonomy),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                ruleSubtitle(rule, taxonomy)?.let { subtitle ->
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
private fun ruleTitle(rule: SubscriptionRule, taxonomy: TaxonomyResponse?): String {
    rule.name?.takeIf { it.isNotBlank() }?.let { return it }
    val orgsLabel = rule.orgs.joinToString(", ") { id -> taxonomy?.orgLabel(id) ?: id }
    val tagsLabel = rule.tags.joinToString(", ") { id -> taxonomy?.tagLabel(id) ?: id }
    val joiner = stringResource(
        if (rule.mode == "AND") R.string.bulletin_rule_join_and else R.string.bulletin_rule_join_or
    )
    return when {
        rule.orgs.isNotEmpty() && rule.tags.isNotEmpty() -> "$orgsLabel$joiner$tagsLabel"
        rule.orgs.isNotEmpty() -> orgsLabel
        rule.tags.isNotEmpty() -> tagsLabel
        else -> stringResource(R.string.bulletin_rule_all_title)
    }
}

@Composable
private fun ruleSubtitle(rule: SubscriptionRule, taxonomy: TaxonomyResponse?): String? {
    if (rule.name.isNullOrBlank()) return null
    val orgsLabel = rule.orgs.joinToString(", ") { id -> taxonomy?.orgLabel(id) ?: id }
    val tagsLabel = rule.tags.joinToString(", ") { id -> taxonomy?.tagLabel(id) ?: id }
    val joiner = stringResource(
        if (rule.mode == "AND") R.string.bulletin_rule_join_and else R.string.bulletin_rule_join_or
    )
    return when {
        rule.orgs.isNotEmpty() && rule.tags.isNotEmpty() -> "$orgsLabel$joiner$tagsLabel"
        rule.orgs.isNotEmpty() -> orgsLabel
        rule.tags.isNotEmpty() -> tagsLabel
        else -> stringResource(R.string.bulletin_rule_all_title)
    }
}

@Composable
private fun NotificationPermissionCard(systemPermissions: SystemPermissions) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(systemPermissions.state(AppPermission.NOTIFICATIONS)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) systemPermissions.recordCurrentGrants()
        state = systemPermissions.state(AppPermission.NOTIFICATIONS)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                systemPermissions.recordCurrentGrants()
                state = systemPermissions.state(AppPermission.NOTIFICATIONS)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ContentCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        when {
                            !state.applicable -> Color(0xFFB0B0B0)
                            state.granted -> Color(0xFF34C759)
                            else -> Color(0xFFFF3B30)
                        }
                    )
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(SystemPermissions.displayNameResId(AppPermission.NOTIFICATIONS)),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    text = stringResource(SystemPermissions.descriptionResId(AppPermission.NOTIFICATIONS)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            when {
                !state.applicable -> Text(
                    "N/A",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                state.granted -> Icon(
                    Icons.Filled.Check,
                    contentDescription = stringResource(R.string.permission_granted),
                    tint = Color(0xFF34C759),
                )
                else -> Button(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        systemPermissions.openSettings(AppPermission.NOTIFICATIONS)
                    }
                }) {
                    Text(stringResource(R.string.action_allow))
                }
            }
        }
    }
}

@Composable
private fun AddRuleRow(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.bulletin_rule_add_action),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ContentCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}
