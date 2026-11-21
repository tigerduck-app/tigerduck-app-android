// The root's sections between Account and About, in iOS's order (tigerduck-app#939).

package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.PageSettings
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.theme.VisualPreset
import java.util.Locale

/** The accent, how the app is drawn, the display and text size, and the bottom bar. */
@Composable
internal fun AppearanceSection(appState: AppState, open: (SettingsLink) -> Unit) {
    SettingsSectionCard(SettingsSection.APPEARANCE) {
        AccentSettingsRows(appState, onOpenAccentColor = { open(SettingsLink.ACCENT_COLOR) })
        HorizontalDivider()
        // The style names are not localized: they read the same in every language.
        SettingsPickerRow(
            label = SettingsRow.VISUAL_PRESET.label(),
            value = appState.visualPreset.displayName,
            options = VisualPreset.entries.map { it.rawValue to it.displayName },
            selectedKey = appState.visualPreset.rawValue,
            onSelect = { appState.visualPreset = VisualPreset.fromRawValue(it) },
        )
        HorizontalDivider()
        val themes = listOf(
            "system" to stringResource(R.string.settings_theme_system),
            "dark" to stringResource(R.string.settings_theme_dark),
            "light" to stringResource(R.string.settings_theme_light),
        )
        SettingsPickerRow(
            label = SettingsRow.COLOR_THEME.label(),
            value = themes.firstOrNull { it.first == appState.themeMode }?.second ?: themes.first().second,
            options = themes,
            selectedKey = appState.themeMode,
            onSelect = { appState.themeMode = it },
        )
        HorizontalDivider()
        SettingsLinkRow(SettingsRow.DISPLAY_SIZE.label()) { open(SettingsLink.DISPLAY_SIZE) }
        HorizontalDivider()
        SettingsLinkRow(SettingsRow.TAB_BAR.label()) { open(SettingsLink.TAB_EDITOR) }
    }
}

/** A row for each page with a setting to show. */
@Composable
internal fun PagesSection(state: SettingsState, openPage: (PageSettings) -> Unit) {
    Column {
        SectionHeader(stringResource(R.string.settings_section_pages))
        ContentCard {
            Column {
                PageSettings.entries.filter { it.isShown(state) }.forEachIndexed { index, page ->
                    if (index > 0) HorizontalDivider()
                    SettingsLinkRow(stringResource(page.title)) { openPage(page) }
                }
            }
        }
    }
}

/**
 * fdroid gets the same link as Play: TigerSync's essential-info sync always runs there, so the
 * entry point is never hidden. The value reads "Off" there, as TigerSync is off at its source on
 * fdroid (AppPreferences.kt), matching course sync and server push being off.
 */
@Composable
internal fun TigerSyncSection(state: SettingsState, open: (SettingsLink) -> Unit) {
    SettingsSectionCard(SettingsSection.TIGERSYNC) {
        SettingsLinkRowWithValue(
            label = SettingsRow.TIGERSYNC.label(),
            value = stringResource(if (state.tigerSyncOn) R.string.settings_sync_status_on else R.string.settings_sync_status_off),
            onClick = { open(SettingsLink.CLOUD_SYNC) },
        )
    }
}

/**
 * The notification permissions, in a section of their own as on iOS. The reminders and School
 * Mail's notifications are on their pages' settings.
 */
@Composable
internal fun NotificationPermissionsSection(open: (SettingsLink) -> Unit) {
    SettingsSectionCard(SettingsSection.NOTIFICATION_PERMISSIONS) {
        SettingsLinkRow(SettingsRow.NOTIFICATION_PERMISSIONS.label()) { open(SettingsLink.NOTIFICATION_PERMISSIONS) }
    }
}

@Composable
internal fun LanguageSection(appLanguage: String, open: (SettingsLink) -> Unit) {
    val normalized = AppLanguageManager.normalize(appLanguage)
    val value = if (normalized == AppLanguageManager.SYSTEM) {
        stringResource(R.string.settings_language_follow_system)
    } else {
        val locale = Locale.forLanguageTag(normalized)
        locale.getDisplayName(locale).ifBlank { normalized }
    }
    SettingsSectionCard(SettingsSection.LANGUAGE) {
        SettingsLinkRowWithValue(SettingsRow.LANGUAGE.label(), value, onClick = { open(SettingsLink.LANGUAGE) })
    }
}

/**
 * The app's icon, screen rotation, vibration, the API endpoint, storage and usage analytics. iOS
 * has the icon, the endpoint and storage here; the other three are Android's own.
 */
@Composable
internal fun OtherSection(viewModel: SettingsViewModel, open: (SettingsLink) -> Unit) {
    val appIcon by viewModel.prefs.appIcon.flow.collectAsStateWithLifecycle()
    var analyticsEnabled by remember { mutableStateOf(viewModel.prefs.analyticsEnabled) }
    Column {
        SettingsSectionCard(SettingsSection.OTHER) {
            SettingsLinkRowWithValue(SettingsRow.APP_ICON.label(), stringResource(appIcon.title), onClick = { open(SettingsLink.APP_ICON) })
            HorizontalDivider()
            ScreenRotationRow(viewModel.appState)
            HorizontalDivider()
            SettingsLinkRow(SettingsRow.VIBRATION.label()) { open(SettingsLink.VIBRATION) }
            HorizontalDivider()
            SettingsLinkRow(SettingsRow.API_ENDPOINT.label()) { open(SettingsLink.API_ENDPOINT) }
            HorizontalDivider()
            SettingsLinkRow(SettingsRow.STORAGE.label()) { open(SettingsLink.STORAGE) }
            HorizontalDivider()
            SettingsToggleRow(SettingsRow.ANALYTICS.label(), analyticsEnabled) {
                analyticsEnabled = it
                viewModel.setAnalyticsEnabled(it)
            }
        }
        PageFootnote(stringResource(R.string.settings_analytics_description))
    }
}

@Composable
private fun ScreenRotationRow(appState: AppState) {
    val modes = listOf(
        "auto" to stringResource(R.string.common_automatic),
        "enabled" to stringResource(R.string.common_enabled),
        "disabled" to stringResource(R.string.common_disabled),
    )
    SettingsPickerRow(
        label = SettingsRow.SCREEN_ROTATION.label(),
        // Anything else stored reads as automatic, as the app applies it.
        value = modes.firstOrNull { it.first == appState.rotationMode }?.second ?: modes.first().second,
        options = modes,
        selectedKey = appState.rotationMode,
        onSelect = { appState.rotationMode = it },
    )
}
