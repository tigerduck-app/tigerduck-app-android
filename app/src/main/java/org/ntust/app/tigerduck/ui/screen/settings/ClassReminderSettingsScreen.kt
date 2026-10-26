// Class reminders: a switch and a lead time, laid out as iOS's
// ClassReminderSettingsView is — the switch with its footer, then the lead
// time, greyed out while the switch is off. Both are this phone's own
// reminders, armed from the course list by LiveActivityManager, and both
// sync through the notification document's `courses` section.

package org.ntust.app.tigerduck.ui.screen.settings

import android.Manifest
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.notification.AppPermission
import org.ntust.app.tigerduck.notification.AssignmentReminderOffset
import org.ntust.app.tigerduck.notification.ClassReminders
import org.ntust.app.tigerduck.notification.SystemPermissions
import org.ntust.app.tigerduck.push.NotificationSettingsSync
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject

@HiltViewModel
class ClassReminderSettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val notificationSettingsSync: NotificationSettingsSync,
    val systemPermissions: SystemPermissions,
) : ViewModel() {

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        // Whatever changes the settings — this screen, or a pull bringing
        // another device's choice — shows here.
        viewModelScope.launch {
            prefs.classReminderSettingsChanged.collect { _state.value = snapshot() }
        }
        // Show what another device last saved, as iOS does on opening the
        // screen. A failure must not reach viewModelScope, which has no
        // exception handler; pullNow catches transport failures itself.
        viewModelScope.launch {
            runCatching { notificationSettingsSync.pullNow() }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "pulling class reminder settings failed: ${e.logSummary()}")
                }
            _state.value = snapshot()
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled == prefs.classReminderEnabled) return
        prefs.classReminderEnabled = enabled
        _state.value = snapshot()
        notificationSettingsSync.markClassReminderUnconfirmedAndPush()
    }

    fun setLeadMinutes(minutes: Int) {
        if (minutes == prefs.classReminderLeadMinutes) return
        prefs.classReminderLeadMinutes = minutes
        prefs.hasPickedClassReminderLead = true
        _state.value = snapshot()
        notificationSettingsSync.markClassReminderUnconfirmedAndPush()
    }

    private fun snapshot(): State {
        val lead = prefs.classReminderLeadMinutes
        return State(
            enabled = prefs.classReminderEnabled,
            leadMinutes = lead,
            leadOptions = ClassReminders.leadOptions(lead),
        )
    }

    data class State(
        val enabled: Boolean,
        val leadMinutes: Int,
        /** The choices, and a lead time carried over from the Live Update where it is none of them. */
        val leadOptions: List<Int>,
    )

    private companion object {
        const val TAG = "ClassReminderSettings"
    }
}

/**
 * How a lead time reads: as the assignment offsets do ("10 minutes before"),
 * the labels iOS uses here too, or as a plain duration for one carried over
 * from the Live Update that has none.
 */
@Composable
private fun leadLabel(minutes: Int): String =
    AssignmentReminderOffset.fromMinutes(minutes)?.let { stringResource(it.labelRes) }
        ?: formatLeadDuration(minutes)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassReminderSettingsScreen(
    onBack: () -> Unit,
    viewModel: ClassReminderSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val systemPermissions = viewModel.systemPermissions

    // As iOS asks on opening this screen. Once per visit, saved across the
    // Activity being rebuilt (a dark-mode switch does that, and HyperOS
    // raises the prompt again on every request), and only while the OS will
    // still show it — see NotificationPermissionSettingsScreen.
    var askedOnArrival by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            systemPermissions.recordCurrentGrants()
        } else {
            systemPermissions.recordDeclined(AppPermission.NOTIFICATIONS)
        }
    }
    LaunchedEffect(Unit) {
        if (!askedOnArrival && systemPermissions.canPromptForNotifications()) {
            askedOnArrival = true
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = { Text(stringResource(R.string.settings_class_reminder)) },
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
                        label = stringResource(R.string.settings_class_reminder),
                        checked = state.enabled,
                    ) { enabled -> viewModel.setEnabled(enabled) }
                }
            }
            item {
                Text(
                    stringResource(R.string.settings_class_reminder_footer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            item {
                ContentCard(modifier = Modifier.padding(top = 8.dp)) {
                    SettingsPickerRow(
                        label = stringResource(R.string.settings_class_reminder_lead_time),
                        value = leadLabel(state.leadMinutes),
                        options = state.leadOptions.map { it.toString() to leadLabel(it) },
                        selectedKey = state.leadMinutes.toString(),
                        enabled = state.enabled,
                    ) { key -> key.toIntOrNull()?.let(viewModel::setLeadMinutes) }
                }
            }
        }
    }
}
