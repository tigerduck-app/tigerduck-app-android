package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.notification.AssignmentReminderOffset
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import javax.inject.Inject

@HiltViewModel
class AssignmentReminderSettingsViewModel @Inject constructor(
    val appState: AppState,
    private val scheduler: AssignmentNotificationScheduler,
    private val dataCache: DataCache,
) : ViewModel() {

    fun setEnabled(value: Boolean) {
        appState.notifyAssignments = value
        if (value) {
            rescheduleFromCache()
        } else {
            scheduler.cancelAllTracked()
        }
    }

    fun setOffsetEnabled(offset: AssignmentReminderOffset, enabled: Boolean) {
        val current = appState.notifyAssignmentOffsets
        appState.notifyAssignmentOffsets = if (enabled) current + offset else current - offset
        if (appState.notifyAssignments) rescheduleFromCache()
    }

    /**
     * Rebuild every pending reminder against the on-disk assignment cache.
     * Called whenever the master toggle or any offset toggle changes so the
     * user sees immediate effect instead of waiting for the next sync to
     * re-arm alarms with the new preference set.
     */
    private fun rescheduleFromCache() {
        viewModelScope.launch {
            val assignments = dataCache.loadAssignments().filter { !it.isCompleted }
            val safetyNetIds =
                dataCache.loadIgnoredAssignments() + dataCache.loadMarkedCompletedAssignments()
            scheduler.scheduleAll(
                assignments,
                safetyNetIds,
                appState.notifyAssignmentOffsets,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignmentReminderSettingsScreen(
    onBack: () -> Unit,
    viewModel: AssignmentReminderSettingsViewModel = hiltViewModel(),
) {
    val masterEnabled = viewModel.appState.notifyAssignments
    val selected = viewModel.appState.notifyAssignmentOffsets

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_assignment_due_reminder)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
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
                        label = stringResource(R.string.settings_assignment_due_reminder),
                        checked = masterEnabled,
                    ) { enabled -> viewModel.setEnabled(enabled) }
                }
            }
            item {
                SectionHeader(
                    stringResource(R.string.live_activity_settings_assignment_notification_header)
                )
            }
            item {
                ContentCard {
                    Column {
                        AssignmentReminderOffset.entries.forEachIndexed { index, offset ->
                            if (index > 0) HorizontalDivider()
                            SettingsToggleRow(
                                label = stringResource(offset.labelRes),
                                checked = offset in selected,
                                enabled = masterEnabled,
                            ) { enabled ->
                                viewModel.setOffsetEnabled(offset, enabled)
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.live_activity_settings_assignment_notification_footer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                        .copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}
