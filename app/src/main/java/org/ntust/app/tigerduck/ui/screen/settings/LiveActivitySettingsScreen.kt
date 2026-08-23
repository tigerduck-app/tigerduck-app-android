// Live Activity settings: one LazyColumn of sections, each a ContentCard of
// rows, with a hint paragraph under the ones whose effect is not obvious from
// the label. Everything the user can change here funnels through
// LiveActivitySettingsViewModel, which refreshes the live notification on
// each write — so the screen itself holds no state beyond which dialog is
// open.
//
// The widgets live next door: LiveActivityLeadTimeRow (slider + duration
// label), LiveActivityCustomTimeDialogs (the 自訂 entry sheets) and
// LiveActivityPermissionRow (status dots + tap routing). The plain on/off
// rows reuse SettingsToggleRow from SettingsRows.kt.

package org.ntust.app.tigerduck.ui.screen.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveActivitySettingsScreen(
    onBack: () -> Unit,
    viewModel: LiveActivitySettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showResetConfirm by remember { mutableStateOf(false) }
    var assignmentCustomOpen by remember { mutableStateOf(false) }
    var classCustomOpen by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.systemPermissions.recordCurrentGrants()
        viewModel.refreshPermissions()
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Refresh permission rows each time the user returns to this screen, e.g.
    // after flipping a toggle in the system settings page we deep-linked to.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.systemPermissions.recordCurrentGrants()
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.live_activity_channel_name)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                expandedHeight = SubSettingsBarHeight,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        ) {
            item {
                ContentCard {
                    SettingsToggleRow(
                        stringResource(R.string.live_activity_settings_enable),
                        state.enabled
                    ) { viewModel.setEnabled(it) }
                }
            }
            item {
                Text(
                    stringResource(R.string.live_activity_settings_description),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            item { SectionHeader(stringResource(R.string.live_activity_settings_section_display_scenarios)) }
            item {
                ContentCard {
                    Column {
                        SettingsToggleRow(
                            stringResource(R.string.live_activity_status_in_class),
                            state.showInClass,
                            enabled = state.enabled,
                        ) { viewModel.setShowInClass(it) }
                        HorizontalDivider()
                        SettingsToggleRow(
                            stringResource(R.string.live_activity_status_class_preparing),
                            state.showClassPreparing,
                            enabled = state.enabled,
                        ) { viewModel.setShowClassPreparing(it) }
                        HorizontalDivider()
                        SettingsToggleRow(
                            stringResource(R.string.home_section_upcoming_assignments),
                            state.showAssignment,
                            enabled = state.enabled,
                        ) { viewModel.setShowAssignment(it) }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.live_activity_settings_section_sound)) }
            item {
                ContentCard {
                    Column {
                        SettingsToggleRow(
                            stringResource(R.string.live_activity_settings_sound_in_class),
                            state.soundInClass,
                            enabled = state.enabled && state.showInClass,
                        ) { viewModel.setSoundInClass(it) }
                        HorizontalDivider()
                        SettingsToggleRow(
                            stringResource(R.string.live_activity_settings_sound_class_preparing),
                            state.soundClassPreparing,
                            enabled = state.enabled && state.showClassPreparing,
                        ) { viewModel.setSoundClassPreparing(it) }
                        HorizontalDivider()
                        SettingsToggleRow(
                            stringResource(R.string.live_activity_settings_sound_assignment),
                            state.soundAssignment,
                            enabled = state.enabled && state.showAssignment,
                        ) { viewModel.setSoundAssignment(it) }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.live_activity_settings_sound_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            item { SectionHeader(stringResource(R.string.live_activity_settings_section_lock_screen)) }
            item {
                ContentCard {
                    SettingsToggleRow(
                        stringResource(R.string.live_activity_settings_show_on_lock_screen),
                        state.showOnLockScreen,
                        enabled = state.enabled,
                    ) { viewModel.setShowOnLockScreen(it) }
                }
            }
            item {
                Text(
                    stringResource(R.string.live_activity_settings_lock_screen_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            item { SectionHeader(stringResource(R.string.live_activity_settings_section_timing)) }
            item {
                ContentCard {
                    Column {
                        LeadTimeRow(
                            label = stringResource(R.string.live_activity_settings_assignment_warning),
                            valueLabel = formatLeadDuration(state.assignmentLeadMinutes),
                            value = state.assignmentLeadMinutes.toFloat(),
                            range = 60f..(8f * 60f),
                            steps = 0,
                            enabled = state.enabled,
                            onValueChange = { viewModel.setAssignmentLeadMinutes(it.toInt()) },
                            onCustomClick = { assignmentCustomOpen = true },
                        )
                        HorizontalDivider()
                        LeadTimeRow(
                            label = stringResource(R.string.live_activity_status_class_preparing),
                            valueLabel = stringResource(
                                R.string.live_activity_settings_minutes_label,
                                state.classLeadMinutes
                            ),
                            value = state.classLeadMinutes.toFloat(),
                            range = 5f..60f,
                            steps = 0,
                            enabled = state.enabled,
                            onValueChange = { viewModel.setClassLeadMinutes(it.toInt()) },
                            onCustomClick = { classCustomOpen = true },
                        )
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.live_activity_settings_timing_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            item { SectionHeader(stringResource(R.string.live_activity_settings_section_system_permissions)) }
            item {
                ContentCard {
                    Column {
                        state.permissions.forEachIndexed { idx, ps ->
                            if (idx > 0) HorizontalDivider()
                            PermissionRow(
                                state = ps,
                                onClick = {
                                    openPermissionPrompt(
                                        context = context,
                                        permission = ps.permission,
                                        systemPermissions = viewModel.systemPermissions,
                                        askNotification = {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.live_activity_settings_permissions_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    OutlinedButton(
                        onClick = { showResetConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.live_activity_settings_reset_defaults)) }
                }
            }
        }
    }

    if (showResetConfirm) {
        TigerDuckDialog(
            onDismissRequest = { showResetConfirm = false },
            title = stringResource(R.string.live_activity_settings_reset_confirm_title),
            message = stringResource(R.string.live_activity_settings_reset_confirm_message),
            confirmText = stringResource(R.string.live_activity_settings_reset),
            onConfirm = {
                viewModel.resetDefaults()
                showResetConfirm = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showResetConfirm = false },
        )
    }

    if (assignmentCustomOpen) {
        CustomHoursMinutesDialog(
            title = stringResource(R.string.live_activity_settings_custom_assignment_title),
            description = stringResource(R.string.live_activity_settings_custom_assignment_description),
            initialMinutes = state.assignmentLeadMinutes,
            minMinutes = (LiveActivityPreferences.MIN_ASSIGNMENT_LEAD_SEC / 60).toInt(),
            maxMinutes = (LiveActivityPreferences.MAX_ASSIGNMENT_LEAD_SEC / 60).toInt(),
            onConfirm = {
                viewModel.setAssignmentLeadMinutes(it)
                assignmentCustomOpen = false
            },
            onDismiss = { assignmentCustomOpen = false },
        )
    }
    if (classCustomOpen) {
        CustomMinutesDialog(
            title = stringResource(R.string.live_activity_settings_custom_class_title),
            description = stringResource(R.string.live_activity_settings_custom_class_description),
            initialMinutes = state.classLeadMinutes,
            minMinutes = (LiveActivityPreferences.MIN_CLASS_LEAD_SEC / 60).toInt().coerceAtLeast(1),
            maxMinutes = (LiveActivityPreferences.MAX_CLASS_LEAD_SEC / 60).toInt(),
            unitHint = stringResource(
                R.string.live_activity_settings_custom_class_unit_hint,
                (LiveActivityPreferences.MAX_CLASS_LEAD_SEC / 60).toInt()
            ),
            onConfirm = {
                viewModel.setClassLeadMinutes(it)
                classCustomOpen = false
            },
            onDismiss = { classCustomOpen = false },
        )
    }
}
