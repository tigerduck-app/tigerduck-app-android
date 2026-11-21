package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.PinMyselfPreference

// The rows of Settings' Moodle page, where a Moodle page's ⋮ Settings leads.

/**
 * The Open Moodle in setting: the Moodle app or the TigerDuck browser for "Open in Moodle". Shown
 * only while the Moodle app is [installed]; without it every Moodle page opens in the browser,
 * whatever [selected] says (`openMoodlePage`).
 */
@Composable
internal fun MoodleOpenInSetting(installed: Boolean, selected: String, onSelect: (String) -> Unit) {
    if (!installed) return
    val app = stringResource(R.string.settings_moodle_open_in_app)
    val browser = stringResource(R.string.settings_browser_in_app)
    SettingsPickerRow(
        label = stringResource(R.string.settings_moodle_open_in),
        value = if (selected == AppPreferences.MOODLE_OPEN_IN_BROWSER) browser else app,
        options = listOf(
            AppPreferences.MOODLE_OPEN_IN_APP to app,
            AppPreferences.MOODLE_OPEN_IN_BROWSER to browser,
        ),
        selectedKey = selected,
        onSelect = onSelect,
    )
}

/**
 * "Pin myself" for a course's members list (tigerduck-app#1032), after where Moodle opens, as iOS's
 * Moodle page settings have it. The list's ⋮ has the same switch (`PinMyselfMenuSwitch`).
 */
@Composable
internal fun PinMyselfSetting(pin: PinMyselfPreference) {
    val on by pin.flow.collectAsStateWithLifecycle()
    SettingsToggleRow(stringResource(R.string.course_members_pin_myself), on) { pin.value = it }
}
