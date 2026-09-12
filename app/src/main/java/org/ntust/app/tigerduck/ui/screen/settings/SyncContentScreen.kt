package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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

/**
 * "同步內容" (Synced content) — spec §6's second-level menu nested under
 * TigerSync's "同步課程資訊" toggle. Six [SyncToggleRow]s plus a navigation
 * row into Live Activity/Live Updates settings.
 *
 * Two deliberate Android deviations from iOS, both from the task-4 brief:
 * there is no platform-limitation footnote here (`sync_courses_footer_platform_note`
 * is an `apple`-group-only key Android cannot resolve), and the live-activity
 * row renders as "即時更新" (`sync_content_live_activity`'s `android` value),
 * not iOS's "即時動態".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncContentScreen(
    onBack: () -> Unit,
    onNavigateToLiveActivitySettings: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var syncAssignments by remember { mutableStateOf(viewModel.prefs.syncAssignments) }
    var syncAssignmentReminders by remember { mutableStateOf(viewModel.prefs.syncAssignmentReminders) }
    var syncLiveActivity by remember { mutableStateOf(viewModel.prefs.syncLiveActivity) }
    var syncCourses by remember { mutableStateOf(viewModel.prefs.syncCourses) }
    var syncCourseColors by remember { mutableStateOf(viewModel.prefs.syncCourseColors) }
    var syncCourseNames by remember { mutableStateOf(viewModel.prefs.syncCourseNames) }
    val reenableConflict by viewModel.reenableConflict.collectAsState()

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
                title = { Text(stringResource(R.string.sync_content_nav_label)) },
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
                    Column {
                        // 作業狀態
                        SyncToggleRow(stringResource(R.string.cloud_sync_assignments), syncAssignments) {
                            if (it && !syncAssignments) {
                                viewModel.markCategoryReenabled("assignments")
                                viewModel.checkPendingConflicts()
                            }
                            syncAssignments = it
                            viewModel.prefs.syncAssignments = it
                            viewModel.pushSyncPreferences()
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                        // 作業到期提醒 — does nothing on Android today. Android
                        // schedules its assignment reminders locally, and
                        // nothing on this device reads this flag: the PATCH
                        // below only stores it on this device's backend row,
                        // and the backend reads the flag solely to deliver
                        // reminders to iPhone and iPad. It is not content with
                        // a server-vs-local conflict either, so no
                        // markCategoryReenabled/checkPendingConflicts here.
                        SyncToggleRow(
                            stringResource(R.string.sync_content_assignment_reminders),
                            syncAssignmentReminders,
                        ) {
                            syncAssignmentReminders = it
                            viewModel.prefs.syncAssignmentReminders = it
                            viewModel.pushSyncPreferences()
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                        // 即時更新 (renders "即時動態" on iOS; Android uses the
                        // sync_content_live_activity android-group value.)
                        SyncToggleRow(
                            stringResource(R.string.sync_content_live_activity),
                            syncLiveActivity,
                        ) {
                            syncLiveActivity = it
                            viewModel.prefs.syncLiveActivity = it
                            viewModel.pushSyncPreferences()
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                        // 課表 – 所有課程
                        SyncToggleRow(
                            stringResource(R.string.sync_content_class_table_all),
                            syncCourses,
                        ) {
                            if (it && !syncCourses) {
                                viewModel.markCategoryReenabled("courses")
                                viewModel.checkPendingConflicts()
                            }
                            syncCourses = it
                            viewModel.prefs.syncCourses = it
                            if (!it) {
                                syncCourseColors = false
                                viewModel.prefs.syncCourseColors = false
                            }
                            viewModel.pushSyncPreferences()
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                        // 課表 – 課程顏色 (depends on "所有課程" being on)
                        SyncToggleRow(
                            stringResource(R.string.sync_content_class_table_colors),
                            syncCourseColors,
                            enabled = syncCourses,
                        ) {
                            if (it && !syncCourseColors) {
                                viewModel.markCategoryReenabled("course_colors")
                                viewModel.checkPendingConflicts()
                            }
                            syncCourseColors = it
                            viewModel.prefs.syncCourseColors = it
                            viewModel.pushSyncPreferences()
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                        // 課表 – 自定義課程名稱
                        SyncToggleRow(
                            stringResource(R.string.sync_content_class_table_names),
                            syncCourseNames,
                        ) {
                            if (it && !syncCourseNames) {
                                viewModel.markCategoryReenabled("course_names")
                                viewModel.checkPendingConflicts()
                            }
                            syncCourseNames = it
                            viewModel.prefs.syncCourseNames = it
                            viewModel.pushSyncPreferences()
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                        // 跳轉：即時更新設定
                        SettingsLinkRow(stringResource(R.string.sync_content_live_activity_settings_nav)) {
                            onNavigateToLiveActivitySettings()
                        }
                    }
                }
            }
        }
    }
}
