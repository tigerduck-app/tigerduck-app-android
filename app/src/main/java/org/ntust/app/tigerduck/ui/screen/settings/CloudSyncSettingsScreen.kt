package org.ntust.app.tigerduck.ui.screen.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import android.text.format.DateUtils
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.push.PushDiagnostic
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSyncSettingsScreen(
    onBack: () -> Unit,
    onNavigateToClassTableSync: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var syncEnabled by remember { mutableStateOf(viewModel.appState.cloudSyncEnabled) }
    var syncAssignments by remember { mutableStateOf(viewModel.prefs.syncAssignments) }
    var syncCourses by remember { mutableStateOf(viewModel.prefs.syncCourses) }
    var syncCourseColors by remember { mutableStateOf(viewModel.prefs.syncCourseColors) }
    var syncCourseNames by remember { mutableStateOf(viewModel.prefs.syncCourseNames) }
    val context = LocalContext.current
    val deviceId = remember { viewModel.identity.uuid() }
    val diagnostic by viewModel.syncDiagnostic.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()

    fun refreshSyncStates() {
        syncCourses = viewModel.prefs.syncCourses
        syncCourseColors = viewModel.prefs.syncCourseColors
        syncCourseNames = viewModel.prefs.syncCourseNames
        syncAssignments = viewModel.prefs.syncAssignments
        if (syncEnabled && !syncCourses && !syncCourseColors && !syncCourseNames && !syncAssignments) {
            syncEnabled = false
            viewModel.appState.cloudSyncEnabled = false
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshSyncStates()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cloud_sync_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                expandedHeight = SubSettingsBarHeight,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                ContentCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.cloud_sync_title),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.settings_sync_brief_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                            )
                        }
                        Switch(
                            checked = syncEnabled,
                            onCheckedChange = {
                                syncEnabled = it
                                viewModel.appState.cloudSyncEnabled = it
                            },
                        )
                    }
                }
            }

            if (syncEnabled) {
                item { SectionHeader(stringResource(R.string.cloud_sync_sync_options)) }
                item {
                    ContentCard {
                        Column {
                            SyncToggleRow(stringResource(R.string.cloud_sync_assignments), syncAssignments) {
                                syncAssignments = it
                                viewModel.prefs.syncAssignments = it
                                viewModel.pushSyncPreferences()
                                refreshSyncStates()
                            }
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            ClassTableNavRow(
                                summary = classTableSyncSummary(syncCourses, syncCourseColors, syncCourseNames),
                                onClick = {
                                    onNavigateToClassTableSync()
                                },
                            )
                        }
                    }
                }
            }

            if (!syncEnabled) {
                item {
                    ContentCard {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                                tint = Color(0xFFFF9500),
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                stringResource(R.string.settings_sync_disabled_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFF9500),
                            )
                        }
                    }
                }
            }

            if (syncEnabled) {
                item { SectionHeader(stringResource(R.string.push_server_status_section)) }
                item {
                    SyncStatusCard(
                        diagnostic = diagnostic,
                        isSyncing = isSyncing,
                        onSyncNow = viewModel::syncNow,
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.push_server_ids_section)) }
            item {
                ContentCard {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        DeviceIdRow(deviceId = deviceId)
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
            item {
                ContentCard {
                    Column {
                        LinkRow(
                            label = stringResource(R.string.settings_learn_more_backend),
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, "https://tigerduck.app/learn-more-about-backend".toUri())
                                context.startActivity(intent)
                            },
                        )
                        LinkRow(
                            label = stringResource(R.string.onboarding_privacy_policy_label),
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, "https://tigerduck.app/privacy-policy".toUri())
                                context.startActivity(intent)
                            },
                        )
                        LinkRow(
                            label = stringResource(R.string.onboarding_privacy_delete_account_label),
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, "https://tigerduck.app/delete-account".toUri())
                                context.startActivity(intent)
                            },
                        )
                    }
                }
            }
        }
    }
}

// ── ClassTableSyncScreen ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassTableSyncScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var syncCourses by remember { mutableStateOf(viewModel.prefs.syncCourses) }
    var syncCourseColors by remember { mutableStateOf(viewModel.prefs.syncCourseColors) }
    var syncCourseNames by remember { mutableStateOf(viewModel.prefs.syncCourseNames) }
    val masterOn = syncCourses || syncCourseColors || syncCourseNames

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cloud_sync_class_table_sync)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                expandedHeight = SubSettingsBarHeight,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                ContentCard {
                    SyncToggleRow(stringResource(R.string.cloud_sync_class_table), masterOn) { on ->
                        syncCourses = on
                        syncCourseColors = on
                        syncCourseNames = on
                        viewModel.prefs.syncCourses = on
                        viewModel.prefs.syncCourseColors = on
                        viewModel.prefs.syncCourseNames = on
                        viewModel.pushSyncPreferences()
                    }
                }
                Text(
                    stringResource(R.string.cloud_sync_class_table_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }

            if (masterOn) {
                item {
                    ContentCard {
                        Column {
                            SyncToggleRow(stringResource(R.string.cloud_sync_courses), syncCourses) {
                                syncCourses = it
                                viewModel.prefs.syncCourses = it
                                if (!it) {
                                    syncCourseColors = false
                                    syncCourseNames = false
                                    viewModel.prefs.syncCourseColors = false
                                    viewModel.prefs.syncCourseNames = false
                                }
                                viewModel.pushSyncPreferences()
                            }
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            SyncToggleRow(stringResource(R.string.cloud_sync_course_colours), syncCourseColors, enabled = syncCourses) {
                                syncCourseColors = it
                                viewModel.prefs.syncCourseColors = it
                                viewModel.pushSyncPreferences()
                            }
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            SyncToggleRow(stringResource(R.string.cloud_sync_custom_course_names), syncCourseNames, enabled = syncCourses) {
                                syncCourseNames = it
                                viewModel.prefs.syncCourseNames = it
                                viewModel.pushSyncPreferences()
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────

private fun classTableSyncSummary(
    syncCourses: Boolean,
    syncCourseColors: Boolean,
    syncCourseNames: Boolean,
): String {
    val on = listOf(syncCourses, syncCourseColors, syncCourseNames).count { it }
    return "$on/3"
}

@Composable
private fun ClassTableNavRow(summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingRowHeight)
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.cloud_sync_class_table),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SyncStatusCard(
    diagnostic: PushDiagnostic,
    isSyncing: Boolean,
    onSyncNow: () -> Unit,
) {
    ContentCard {
        Column(modifier = Modifier.padding(12.dp)) {
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
                Spacer(Modifier.height(10.dp))
                LabeledText(
                    label = stringResource(R.string.push_server_last_registration),
                    value = DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS).toString(),
                )
            }
            diagnostic.lastSyncAt?.let { ts ->
                Spacer(Modifier.height(10.dp))
                LabeledText(
                    label = stringResource(R.string.push_server_last_sync),
                    value = DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS).toString(),
                )
            }
            diagnostic.lastError?.let { msg ->
                Spacer(Modifier.height(10.dp))
                LabeledText(
                    label = stringResource(R.string.push_server_latest_error),
                    value = msg,
                    valueColor = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onSyncNow,
                enabled = !isSyncing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.cloud_sync_sync_now))
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, okText: String, badText: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
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
private fun LabeledText(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = valueColor)
    }
}

@Composable
private fun DeviceIdRow(deviceId: String) {
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.device_id_copied)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingRowHeight)
            .clickable {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("device_id", deviceId))
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.cloud_sync_device_id),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = deviceId.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Filled.ContentCopy,
            contentDescription = stringResource(R.string.device_id_copy_action),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingRowHeight)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClick, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun SyncToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingRowHeight)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
