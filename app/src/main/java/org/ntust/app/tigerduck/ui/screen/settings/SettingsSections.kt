// The root's sections between Account and About: Appearance, Notifications, the pages, TigerSync,
// Language and Other settings, in the order of Sam's outline (2026-11-29).

package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.PageSettings
import org.ntust.app.tigerduck.ui.theme.VisualPreset
import java.util.Locale

/**
 * The accent, with where its gradient is used on a sub-page of its own, how the app is drawn, the
 * display and text size, and the bottom bar.
 */
@Composable
internal fun AppearanceSection(appState: AppState, open: (SettingsLink) -> Unit) {
    SettingsSectionCard(SettingsSection.APPEARANCE) {
        AccentSettingsRow(appState, onOpenAccentColor = { open(SettingsLink.ACCENT_COLOR) })
        HorizontalDivider()
        SettingsLinkRow(SettingsRow.GRADIENT_USAGE.label()) { open(SettingsLink.GRADIENT_USAGE) }
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

/**
 * The notification permissions, and a sub-page that leads to each notification's own settings,
 * which stay on their pages' settings too.
 */
@Composable
internal fun NotificationsSection(open: (SettingsLink) -> Unit) {
    SettingsSectionCard(SettingsSection.NOTIFICATIONS) {
        SettingsLinkRow(SettingsRow.NOTIFICATION_PERMISSIONS.label()) { open(SettingsLink.NOTIFICATION_PERMISSIONS) }
        HorizontalDivider()
        SettingsLinkRow(SettingsRow.NOTIFICATION_SWITCHES.label()) { open(SettingsLink.NOTIFICATION_SWITCHES) }
    }
}

/** A row for each of the app's pages with a setting to show; the browser's and Moodle's are under Other settings. */
@Composable
internal fun PagesSection(state: SettingsState, openPage: (PageSettings) -> Unit) {
    Column {
        SettingsSectionTitle(stringResource(R.string.settings_section_pages))
        ContentCard {
            Column {
                PageSettings.entries.filter { it.linkedFrom == RootPart.PAGES && it.isShown(state) }.forEachIndexed { index, page ->
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
 * The app's icon, storage, the browser's and Moodle's pages, which serve every page rather than
 * being one, and the rest of the settings on a sub-page of their own (Other settings › Other
 * settings).
 */
@Composable
internal fun OtherSection(
    viewModel: SettingsViewModel,
    state: SettingsState,
    open: (SettingsLink) -> Unit,
    openPage: (PageSettings) -> Unit,
) {
    val appIcon by viewModel.prefs.appIcon.flow.collectAsStateWithLifecycle()
    SettingsSectionCard(SettingsSection.OTHER) {
        SettingsLinkRowWithValue(SettingsRow.APP_ICON.label(), stringResource(appIcon.title), onClick = { open(SettingsLink.APP_ICON) })
        HorizontalDivider()
        SettingsLinkRow(SettingsRow.STORAGE.label()) { open(SettingsLink.STORAGE) }
        PageSettings.entries.filter { it.linkedFrom == RootPart.OTHER && it.isShown(state) }.forEach { page ->
            HorizontalDivider()
            SettingsLinkRow(stringResource(page.title)) { openPage(page) }
        }
        HorizontalDivider()
        SettingsLinkRow(SettingsRow.OTHER_SETTINGS.label()) { open(SettingsLink.OTHER_SETTINGS) }
    }
}
