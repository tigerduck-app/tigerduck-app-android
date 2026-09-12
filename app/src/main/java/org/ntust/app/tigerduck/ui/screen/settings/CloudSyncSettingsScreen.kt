package org.ntust.app.tigerduck.ui.screen.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.push.PushDiagnostic
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * F-Droid ships without Google Play Services and so cannot run TigerSync's
 * course-sync or server-push pipeline — see `effectiveCloudSyncEnabled` /
 * `effectiveServerPushOptedOut`, which force those two off at their source
 * regardless of what is stored. This screen additionally greys out and
 * relabels the two toggles that would otherwise claim to be interactive, and
 * hides the status rows that can never report anything real there (no FCM
 * token ever arrives, so device registration never completes).
 */
private val isFdroidFlavor: Boolean
    get() = BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)

/**
 * Whether any 同步內容 switch that gives TigerSync something to do for this
 * device is still on. When none is, the TigerSync screen switches TigerSync
 * itself off (`refreshSyncStates` in [CloudSyncSettingsScreen]).
 *
 * `syncLiveActivity` and `syncAssignmentReminders` both count, for the same
 * reason: each gates this device's own settings sync through the shared
 * `notification` document (`NotificationSettingsSync`), which needs
 * TigerSync (`cloudSyncEnabled`) on regardless of whether `syncAssignments`
 * (the assignment *data* category) or `syncCourses`/`syncCourseColors`/
 * `syncCourseNames` are. A user who keeps only 作業到期提醒 or only 即時更新
 * must be able to keep TigerSync on for it — `syncAssignmentReminders` used
 * to be excluded here because nothing on this device read it (Android
 * scheduled its own reminders locally and the backend only consulted it for
 * iPhone/iPad delivery); it now also carries this device's `enabled` +
 * offsets to and from the shared document, so the same reasoning
 * `syncLiveActivity` already had applies to it too.
 */
internal fun hasSyncContentLeft(
    syncCourses: Boolean,
    syncCourseColors: Boolean,
    syncCourseNames: Boolean,
    syncAssignments: Boolean,
    syncAssignmentReminders: Boolean,
    syncLiveActivity: Boolean,
): Boolean =
    syncCourses || syncCourseColors || syncCourseNames || syncAssignments ||
        syncAssignmentReminders || syncLiveActivity

/**
 * TigerSync settings — spec §6. Root-level rows, each a [ToggleWithFooterRow]:
 * an always-on "essential info" indicator (no persisted setting — it is
 * bound to a constant, see its call site below), the course-sync master
 * toggle (nested "同步內容" entry when on), the server-push opt-out (moved
 * here from the now-removed ServerPushScreen — see
 * [SubscriptionSettingsScreen][org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionSettingsScreen]'s
 * comment for that history), and "TigerSync 狀態": a plain title
 * ([TigerSyncStatusSummary] — registration status, then the latest error
 * when there is one) followed by [SyncStatusCard] for the rest (push
 * permission, device ID). On fdroid the essential-info row is
 * unchanged, but the course-sync and server-push rows are greyed out and
 * off — see [isFdroidFlavor].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSyncSettingsScreen(
    onBack: () -> Unit,
    onNavigateToSyncContent: () -> Unit,
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
    val reenableConflict by viewModel.reenableConflict.collectAsState()
    val serverPushOn by viewModel.serverPushOn.collectAsState()
    val isTogglingServerPush by viewModel.isTogglingServerPush.collectAsState()

    fun refreshSyncStates() {
        syncCourses = viewModel.prefs.syncCourses
        syncCourseColors = viewModel.prefs.syncCourseColors
        syncCourseNames = viewModel.prefs.syncCourseNames
        syncAssignments = viewModel.prefs.syncAssignments
        val anythingLeftToSync = hasSyncContentLeft(
            syncCourses = syncCourses,
            syncCourseColors = syncCourseColors,
            syncCourseNames = syncCourseNames,
            syncAssignments = syncAssignments,
            syncAssignmentReminders = viewModel.prefs.syncAssignmentReminders,
            syncLiveActivity = viewModel.prefs.syncLiveActivity,
        )
        if (syncEnabled && !anythingLeftToSync) {
            syncEnabled = false
            viewModel.appState.cloudSyncEnabled = false
            viewModel.pushCloudSyncEnabled(false)
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                refreshSyncStates()
                viewModel.checkPendingConflicts()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The server-push opt-out PATCH can be rejected by the backend; setServerPushOn
    // reverts the switch itself in that case, and this just surfaces why.
    val serverPushFailedMessage = stringResource(R.string.settings_server_push_update_failed)
    LaunchedEffect(Unit) {
        viewModel.serverPushUpdateFailed.collect {
            Toast.makeText(context, serverPushFailedMessage, Toast.LENGTH_SHORT).show()
        }
    }

    if (reenableConflict != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.sync_conflict_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.sync_conflict_reenable_message),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        reenableConflict!!.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.resolveReenableConflict(keepLocal = false) }
                ) { Text(stringResource(R.string.sync_conflict_use_server)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.resolveReenableConflict(keepLocal = true) }
                ) { Text(stringResource(R.string.sync_conflict_use_local)) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = { Text(stringResource(R.string.cloud_sync_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.checkPendingConflicts()
                        onBack()
                    }) {
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
            // ── 取得必要資訊 (always on, not a real setting) ──────────────
            item {
                ContentCard {
                    ToggleWithFooterRow(
                        label = stringResource(R.string.sync_essential_toggle),
                        footer = stringResource(R.string.sync_essential_footer),
                        checked = true,
                        enabled = false,
                        onCheckedChange = {},
                    )
                }
            }

            // ── 同步課程資訊 (the pre-existing cloudSyncEnabled master) ───
            // `checked` needs no fdroid check of its own: `syncEnabled` is
            // seeded from `viewModel.appState.cloudSyncEnabled`, which reads
            // false on fdroid at its source (AppPreferences.kt). `enabled`
            // and the footer are the only fdroid-specific pieces here.
            item {
                ContentCard {
                    ToggleWithFooterRow(
                        label = stringResource(R.string.sync_courses_toggle),
                        footer = if (isFdroidFlavor) {
                            stringResource(R.string.sync_fdroid_unavailable_title)
                        } else {
                            stringResource(R.string.sync_courses_footer)
                        },
                        checked = syncEnabled,
                        enabled = !isFdroidFlavor,
                        onCheckedChange = {
                            if (it && !syncEnabled) {
                                if (viewModel.prefs.syncCourses) viewModel.markCategoryReenabled("courses")
                                if (viewModel.prefs.syncCourseColors) viewModel.markCategoryReenabled("course_colors")
                                if (viewModel.prefs.syncCourseNames) viewModel.markCategoryReenabled("course_names")
                                if (viewModel.prefs.syncAssignments) viewModel.markCategoryReenabled("assignments")
                                viewModel.checkPendingConflicts()
                            }
                            syncEnabled = it
                            viewModel.appState.cloudSyncEnabled = it
                            viewModel.pushCloudSyncEnabled(it)
                        },
                    )
                }
            }
            if (syncEnabled) {
                item {
                    ContentCard {
                        SettingsLinkRow(stringResource(R.string.sync_content_nav_label)) {
                            onNavigateToSyncContent()
                        }
                    }
                }
            }
            // `syncEnabled` (this same directly user-togglable switch, above)
            // being off is reachable exactly as it was pre-refactor and is a
            // common, intentional state (essential info + server push, no
            // course/assignment sync) — not something the restructure
            // removed. The disclosure that local-only data still applies in
            // that state is still true, so it stays. Re-added after being
            // dropped on the false premise that no all-off state remained
            // reachable.
            //
            // Excluded on fdroid: `syncEnabled` is always false there, so
            // the note would be permanent, and it would be wrong regardless
            // — 取得必要資訊 stays checked and active just above it, and a
            // signed-in device still syncs its locale to the backend (see
            // PushRegistrationService.syncLocalePreference).
            if (!syncEnabled && !isFdroidFlavor) {
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

            // ── 接收額外伺服器推播 (moved from the removed ServerPushScreen) ─
            // `checked` needs no fdroid check of its own, matching the
            // course-sync row above: `serverPushOn` mirrors
            // PushRegistrationService.isServerPushOptedOut, which reads
            // fdroid as opted out at its source regardless of what is
            // stored.
            item {
                ContentCard {
                    ToggleWithFooterRow(
                        label = stringResource(R.string.settings_server_push_label),
                        footer = if (isFdroidFlavor) {
                            stringResource(R.string.sync_fdroid_unavailable_title)
                        } else {
                            stringResource(R.string.settings_server_push_footer)
                        },
                        checked = serverPushOn,
                        enabled = !isFdroidFlavor && !isTogglingServerPush,
                        onCheckedChange = viewModel::setServerPushOn,
                    )
                }
            }

            // ── TigerSync 狀態 ─────────────────────────────────────────
            // A title, not a row that goes somewhere (spec §6): the
            // registration status and, when there is one, the error sit
            // directly under the heading rather than behind a nav target.
            item { SectionHeader(stringResource(R.string.sync_status_nav_label)) }
            item { TigerSyncStatusSummary(diagnostic = diagnostic) }
            item { SyncStatusCard(deviceId = deviceId) }

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

// ── Helpers ─────────────────────────────────────────────────────────────

/**
 * A settings row shaped like the pre-restructure master TigerSync card: a
 * two-line label (title + footer) next to a [Switch]. Used for every
 * root-level TigerSync toggle so the essential-info, course-sync, and
 * server-push rows read as one consistent family.
 */
@Composable
private fun ToggleWithFooterRow(
    label: String,
    footer: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            )
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

/**
 * "TigerSync 狀態" itself (spec §6): a title, not a row that goes anywhere —
 * just the device-registration status directly under the [SectionHeader],
 * and the latest error under that when there is one. Hidden on fdroid:
 * without an FCM token, registration never completes, so this would only
 * ever be able to show "pending" forever.
 */
@Composable
private fun TigerSyncStatusSummary(diagnostic: PushDiagnostic) {
    if (isFdroidFlavor) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
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
        diagnostic.lastError?.let { msg ->
            Spacer(Modifier.height(4.dp))
            LabeledText(
                label = stringResource(R.string.push_server_latest_error),
                value = msg,
                valueColor = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SyncStatusCard(
    deviceId: String,
) {
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

    // Re-check on ON_RESUME so revoking POST_NOTIFICATIONS in system Settings
    // and returning here reflects the current grant, not the stale value
    // captured on first composition. Mirrors the removed ServerPushScreen's
    // PushStatusCard.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                permissionGranted = checkNotificationGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ContentCard {
        Column {
            Column(modifier = Modifier.padding(12.dp)) {
                StatusRow(
                    label = stringResource(R.string.bulletin_push_status_label),
                    ok = permissionGranted,
                    okText = stringResource(R.string.permission_granted),
                    badText = stringResource(R.string.bulletin_push_status_denied),
                )
                if (!permissionGranted) {
                    Spacer(Modifier.height(8.dp))
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
            // Device ID no longer forms its own "push_server_ids_section" —
            // it stays visible as part of the status area (spec §6), directly
            // under the permission controls.
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            DeviceIdRow(deviceId = deviceId)
        }
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    runCatching { context.startActivity(intent) }
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

/**
 * A toggle row with an optional dependency: when [enabled] is false (a
 * parent toggle in the same "同步內容" screen is off), the row is greyed
 * out and non-interactive rather than hidden — see
 * `cloud_sync_course_colours`'s `enabled = syncCourses` dependency, the one
 * piece of the old ClassTableSyncScreen this rule specifically preserves.
 */
@Composable
internal fun SyncToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
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
