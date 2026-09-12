// Live Activity settings: one LazyColumn of sections, each a ContentCard of
// rows, with a hint paragraph under the ones whose effect is not obvious from
// the label. Everything the user can change here funnels through
// LiveActivitySettingsViewModel, which refreshes the live notification on
// each write — so the screen itself holds no state beyond which dialog is
// open.
//
// The widget living next door is LiveActivityLeadTimeRow (slider + duration
// label). The plain on/off rows reuse SettingsToggleRow from
// SettingsRows.kt.
//
// v2.1.0 removed the 自訂 ("custom") lead-time dialogs to match iOS, which
// only ever offered a slider — see LiveActivityLeadTimeRow and
// LiveActivityPreferences for the slider-range and clamping side of that.
// The same version moved the system-permissions section (and the
// permission-refresh/request plumbing behind it) out to its own
// 通知權限設定 screen — see NotificationPermissionSettingsScreen and
// NotificationPermissionRow. This screen shows no permissions anymore.

package org.ntust.app.tigerduck.ui.screen.settings

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveActivitySettingsScreen(
    onBack: () -> Unit,
    viewModel: LiveActivitySettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showResetConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
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
                            // 1h..8h, half-hour steps to match iOS. Compose's `steps`
                            // is the number of dividers BETWEEN the two endpoints, not
                            // the number of segments: (480-60)/30 = 14 segments, so
                            // steps = 14 - 1 = 13. Re-derive this if the range or step
                            // size ever changes — don't just eyeball a new number.
                            range = 60f..480f,
                            steps = 13,
                            enabled = state.enabled,
                            onValueChange = { viewModel.setAssignmentLeadMinutes(it.toInt()) },
                        )
                        HorizontalDivider()
                        LeadTimeRow(
                            label = stringResource(R.string.live_activity_status_class_preparing),
                            valueLabel = stringResource(
                                R.string.live_activity_settings_minutes_label,
                                state.classLeadMinutes
                            ),
                            value = state.classLeadMinutes.toFloat(),
                            // 5min..4h, 5-minute steps to match iOS
                            // (maximumClassPreparingLeadTime): (240-5)/5 = 47
                            // segments, so steps = 47 - 1 = 46.
                            range = 5f..240f,
                            steps = 46,
                            enabled = state.enabled,
                            onValueChange = { viewModel.setClassLeadMinutes(it.toInt()) },
                        )
                    }
                }
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
}
