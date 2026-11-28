package org.ntust.app.tigerduck.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.ui.component.LocalOpenPageSettings
import org.ntust.app.tigerduck.ui.component.PageSettings
import org.ntust.app.tigerduck.ui.screen.settings.PageSettingsScreen
import org.ntust.app.tigerduck.ui.screen.settings.SettingsLink
import org.ntust.app.tigerduck.ui.screen.settings.SettingsScreen
import org.ntust.app.tigerduck.ui.screen.settings.SettingsSubPage
import org.ntust.app.tigerduck.ui.screen.settings.SubPageSettingsScreen

/**
 * A page's settings, pushed as Settings pushes every one of its pages: from Settings' Pages
 * section, a search result, or the page's own ⋮.
 */
internal val PageSettings.route: String
    get() = "settings/page/${name.lowercase()}"

/** The page each of Settings' links opens. About › Others keeps the route it had before the other sub-pages came. */
internal val SettingsLink.route: String
    get() = when (this) {
        SettingsLink.TAB_EDITOR -> Screen.TabEditor.route
        SettingsLink.ACCENT_COLOR -> Screen.AccentColor.route
        SettingsLink.GRADIENT_USAGE, SettingsLink.NOTIFICATION_SWITCHES, SettingsLink.OTHER_SETTINGS -> "settings/sub/${name.lowercase()}"
        SettingsLink.DISPLAY_SIZE -> Screen.DisplaySizeSettings.route
        SettingsLink.NOTIFICATION_PERMISSIONS -> Screen.NotificationPermissionSettings.route
        SettingsLink.CLOUD_SYNC -> Screen.CloudSync.route
        SettingsLink.LANGUAGE -> Screen.LanguagePicker.route
        SettingsLink.APP_ICON -> Screen.AppIcon.route
        SettingsLink.STORAGE -> Screen.Storage.route
        SettingsLink.VIBRATION -> Screen.VibrationSettings.route
        SettingsLink.API_ENDPOINT -> Screen.ApiEndpointDebug.route
        SettingsLink.ABOUT_OTHERS -> Screen.AboutOthers.route
        SettingsLink.LICENSES -> Screen.OpenSourceLicenses.route
        SettingsLink.SOURCE_CODE -> Screen.SourceCodePicker.route
        SettingsLink.ASSIGNMENT_REMINDERS -> Screen.AssignmentReminderSettings.route
        SettingsLink.LIVE_ACTIVITY -> Screen.LiveActivitySettings.route
        SettingsLink.COURSE_NAME_SIZE -> Screen.CourseNameSizeSettings.route
        SettingsLink.CLASS_REMINDERS -> Screen.ClassReminderSettings.route
        SettingsLink.MAIL_NOTIFICATIONS -> Screen.SchoolMailNotificationSettings.route
        SettingsLink.CLASS_TABLE_LIST -> Screen.ClassTableList.route
        SettingsLink.DEBUG_TIME -> Screen.Debug.route
        SettingsLink.DEBUG_NOTIFICATION -> Screen.NotificationDebug.route
        SettingsLink.DEBUG_TRIGGERS -> Screen.TriggersDebug.route
        SettingsLink.DEBUG_SERVER_FAILURE -> Screen.ServerFailureDebug.route
        SettingsLink.DEBUG_OUTSIDE_APPS -> Screen.OutsideAppsDebug.route
        SettingsLink.DEBUG_MAIL_SERVER -> Screen.MailDevServerDebug.route
        SettingsLink.DEBUG_BROWSER_ALLOWLIST -> Screen.BrowserAllowlistDebug.route
        SettingsLink.DEBUG_VIEWER_SAMPLES -> Screen.ViewerSamplesDebug.route
    }

/**
 * Opens [link]. A debug page is registered only in a debug build, so a release build opens none:
 * navigating to a route the graph lacks throws ("destination cannot be found").
 */
internal fun NavController.openSettings(link: SettingsLink) {
    if (!link.debugOnly || BuildConfig.DEBUG) navigate(link.route)
}

/** Settings, a page of settings for each page that has them, and the sub-pages of its sections. */
internal fun NavGraphBuilder.settingsPages(navController: NavController) {
    val openPage = { page: PageSettings -> navController.navigate(page.route) }
    page(Screen.Settings.route) {
        SettingsScreen(open = navController::openSettings, openPage = openPage)
    }
    PageSettings.entries.forEach { settings ->
        page(settings.route) { entry ->
            PageSettingsScreen(settings, onBack = { navController.popIfTop(entry) }, open = navController::openSettings)
        }
    }
    SettingsSubPage.entries.forEach { subPage ->
        page(subPage.link.route) { entry ->
            SubPageSettingsScreen(subPage, onBack = { navController.popIfTop(entry) }, open = navController::openSettings, openPage = openPage)
        }
    }
}

/** The app's navigation for [content], with every page under it able to open its settings. */
@Composable
internal fun PageSettingsHost(content: @Composable (NavHostController) -> Unit) {
    val navController = rememberNavController()
    val open = remember(navController) { { page: PageSettings -> navController.navigate(page.route) } }
    CompositionLocalProvider(LocalOpenPageSettings provides open) { content(navController) }
}
