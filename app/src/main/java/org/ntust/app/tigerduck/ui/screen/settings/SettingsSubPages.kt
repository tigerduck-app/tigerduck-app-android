// The root's sub-pages (Sam's outline, 2026-11-29): a row of a section opens each, and each lists
// rows of its own, as About › Others always has: Appearance › Gradient usage, Notifications ›
// Notification switches and settings, Other settings › Other settings, and About › Others.

package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.PageScaffold
import org.ntust.app.tigerduck.ui.component.PageSettings

/** [subPage], opened from its row on the root or from a search result. */
@Composable
internal fun SubPageSettingsScreen(
    subPage: SettingsSubPage,
    onBack: () -> Unit,
    open: (SettingsLink) -> Unit,
    openPage: (PageSettings) -> Unit,
) {
    val state = settingsState()
    val viewModel: SettingsViewModel = hiltViewModel()
    PageScaffold(title = stringResource(subPage.title), onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        ) {
            item {
                Column {
                    when (subPage) {
                        SettingsSubPage.GRADIENT_USAGE -> GradientUsageRows(viewModel.appState, state)
                        SettingsSubPage.NOTIFICATION_SWITCHES -> NotificationSwitchRows(state, open, openPage)
                        SettingsSubPage.OTHER_SETTINGS -> OtherSettings(viewModel, open)
                        SettingsSubPage.ABOUT_OTHERS -> AboutOthersRows(viewModel.appState.browserPreference, open)
                    }
                }
            }
        }
    }
}

/** Where the accent's gradient is used, each surface a switch, dimmed while the accent is one color. */
@Composable
private fun GradientUsageRows(appState: AppState, state: SettingsState) {
    ContentCard {
        Column {
            AccentGradientSwitches(
                isGradient = appState.accent.isGradient,
                states = appState.accentGradientSwitches,
                onChange = { appState.accentGradientSwitches = it },
                showsWatch = SettingsRow.ACCENT_GRADIENT_ON_WATCH.isShown(state),
                showsLiveActivities = SettingsRow.ACCENT_GRADIENT_ON_LIVE_ACTIVITIES.isShown(state),
            )
        }
    }
}

/**
 * A row for each notification, leading where its page's own row leads: Live Updates, the
 * assignment and class reminders, the bulletins' page, whose settings are their push rules, and
 * School Mail's notifications. The bulletins' and the mail's are dimmed signed out, as on their
 * pages, and say what they need.
 */
@Composable
internal fun NotificationSwitchRows(state: SettingsState, open: (SettingsLink) -> Unit, openPage: (PageSettings) -> Unit) {
    val rows = listOf<Pair<SettingsRow, () -> Unit>>(
        SettingsRow.NOTIFICATION_LIVE_ACTIVITY to { open(SettingsLink.LIVE_ACTIVITY) },
        SettingsRow.NOTIFICATION_ASSIGNMENT_REMINDERS to { open(SettingsLink.ASSIGNMENT_REMINDERS) },
        SettingsRow.NOTIFICATION_CLASS_REMINDERS to { open(SettingsLink.CLASS_REMINDERS) },
        SettingsRow.NOTIFICATION_BULLETINS to { openPage(PageSettings.BULLETINS) },
        SettingsRow.NOTIFICATION_MAIL to { open(SettingsLink.MAIL_NOTIFICATIONS) },
    )
    ContentCard {
        Column {
            rows.forEachIndexed { index, (row, onClick) ->
                if (index > 0) HorizontalDivider()
                PageLinkRow(row, state, onClick)
            }
        }
    }
}

@Composable
private fun OtherSettings(viewModel: SettingsViewModel, open: (SettingsLink) -> Unit) {
    val appState = viewModel.appState
    var analyticsEnabled by remember { mutableStateOf(viewModel.prefs.analyticsEnabled) }
    OtherSettingsRows(
        rotationMode = appState.rotationMode,
        onRotationMode = { appState.rotationMode = it },
        analyticsEnabled = analyticsEnabled,
        onAnalyticsEnabled = {
            analyticsEnabled = it
            viewModel.setAnalyticsEnabled(it)
        },
        open = open,
    )
}

/**
 * The API endpoint, vibration, screen rotation and usage analytics, with what analytics shares.
 * iOS has the endpoint in Other settings; the other three are Android's own.
 */
@Composable
internal fun OtherSettingsRows(
    rotationMode: String,
    onRotationMode: (String) -> Unit,
    analyticsEnabled: Boolean,
    onAnalyticsEnabled: (Boolean) -> Unit,
    open: (SettingsLink) -> Unit,
) {
    ContentCard {
        Column {
            SettingsLinkRow(SettingsRow.API_ENDPOINT.label()) { open(SettingsLink.API_ENDPOINT) }
            HorizontalDivider()
            SettingsLinkRow(SettingsRow.VIBRATION.label()) { open(SettingsLink.VIBRATION) }
            HorizontalDivider()
            ScreenRotationRow(rotationMode, onRotationMode)
            HorizontalDivider()
            SettingsToggleRow(SettingsRow.ANALYTICS.label(), analyticsEnabled, onCheckedChange = onAnalyticsEnabled)
        }
    }
    PageFootnote(stringResource(R.string.settings_analytics_description))
}

@Composable
private fun ScreenRotationRow(rotationMode: String, onRotationMode: (String) -> Unit) {
    val modes = listOf(
        "auto" to stringResource(R.string.common_automatic),
        "enabled" to stringResource(R.string.common_enabled),
        "disabled" to stringResource(R.string.common_disabled),
    )
    SettingsPickerRow(
        label = SettingsRow.SCREEN_ROTATION.label(),
        // Anything else stored reads as automatic, as the app applies it.
        value = modes.firstOrNull { it.first == rotationMode }?.second ?: modes.first().second,
        options = modes,
        selectedKey = rotationMode,
        onSelect = onRotationMode,
    )
}
