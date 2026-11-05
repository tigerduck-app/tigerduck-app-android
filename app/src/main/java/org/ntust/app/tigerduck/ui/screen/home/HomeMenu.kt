package org.ntust.app.tigerduck.ui.screen.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.PageMenu
import org.ntust.app.tigerduck.ui.component.PageMenuSwitch
import org.ntust.app.tigerduck.ui.component.PageSettings
import org.ntust.app.tigerduck.ui.component.PageSettingsItem
import org.ntust.app.tigerduck.ui.screen.settings.AssignmentReminderSwitch
import org.ntust.app.tigerduck.ui.screen.settings.LiveUpdatesSwitch
import javax.inject.Inject

/** Home's four switches as the ⋮ shows them, read where their settings rows read them. */
internal data class HomeMenuSwitches(
    val fullDueDate: Boolean,
    val invertSlider: Boolean,
    val assignmentReminders: Boolean,
    val liveUpdates: Boolean,
)

/**
 * What Home's ⋮ reads and turns. Each switch writes what its row in Settings writes, through the
 * same code: the full due date and the slider's direction on [AppState], as Settings and Other
 * settings set them; reminders and Live Updates through the switches their own pages turn.
 *
 * None of the four waits for TigerSync on this phone, unlike iOS, where the backend sends the
 * reminders and the Live Activity: Android arms its own reminders and posts its own Live Update,
 * both pages work with TigerSync off, and fdroid has no TigerSync to turn on. So none is dimmed.
 */
@HiltViewModel
class HomeMenuViewModel @Inject constructor(
    private val appState: AppState,
    private val assignmentReminders: AssignmentReminderSwitch,
    private val liveUpdates: LiveUpdatesSwitch,
) : ViewModel() {
    internal fun switches() = HomeMenuSwitches(
        fullDueDate = appState.showAbsoluteAssignmentTime,
        invertSlider = appState.invertSliderDirection,
        assignmentReminders = assignmentReminders.isOn,
        liveUpdates = liveUpdates.isOn,
    )

    fun setFullDueDate(on: Boolean) {
        appState.showAbsoluteAssignmentTime = on
    }

    fun setInvertSlider(on: Boolean) {
        appState.invertSliderDirection = on
    }

    fun setAssignmentReminders(on: Boolean) = assignmentReminders.setEnabled(on, viewModelScope)

    fun setLiveUpdates(on: Boolean) = liveUpdates.setEnabled(on)
}

/** Home's ⋮, as iOS's: Edit Home, then four of its settings, then Settings. */
@Composable
internal fun rememberHomeMenu(onEdit: () -> Unit, viewModel: HomeMenuViewModel = hiltViewModel()): PageMenu =
    homeMenu(
        switches = viewModel::switches,
        onEdit = onEdit,
        onFullDueDate = viewModel::setFullDueDate,
        onInvertSlider = viewModel::setInvertSlider,
        onAssignmentReminders = viewModel::setAssignmentReminders,
        onLiveUpdates = viewModel::setLiveUpdates,
    )

/** [switches] is read as the menu opens: it shows them as they are then, and a pick closes it. */
@Composable
internal fun homeMenu(
    switches: () -> HomeMenuSwitches,
    onEdit: () -> Unit,
    onFullDueDate: (Boolean) -> Unit,
    onInvertSlider: (Boolean) -> Unit,
    onAssignmentReminders: (Boolean) -> Unit,
    onLiveUpdates: (Boolean) -> Unit,
) = PageMenu(label = stringResource(R.string.home_more_actions)) { close ->
    val on = switches()
    val closing: ((Boolean) -> Unit) -> (Boolean) -> Unit = { write -> { close(); write(it) } }
    DropdownMenuItem(
        text = { Text(stringResource(R.string.home_edit)) },
        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
        onClick = {
            close()
            onEdit()
        },
    )
    HorizontalDivider()
    PageMenuSwitch(stringResource(R.string.settings_home_full_due_date), on.fullDueDate, closing(onFullDueDate))
    PageMenuSwitch(stringResource(R.string.settings_invert_slider_direction), on.invertSlider, closing(onInvertSlider))
    PageMenuSwitch(
        stringResource(R.string.settings_assignment_due_reminder), on.assignmentReminders, closing(onAssignmentReminders),
    )
    PageMenuSwitch(stringResource(R.string.live_activity_channel_name), on.liveUpdates, closing(onLiveUpdates))
    HorizontalDivider()
    PageSettingsItem(PageSettings.HOME, close)
}
