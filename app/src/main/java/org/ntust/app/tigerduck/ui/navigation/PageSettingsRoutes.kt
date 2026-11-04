package org.ntust.app.tigerduck.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.ntust.app.tigerduck.ui.component.LocalOpenPageSettings
import org.ntust.app.tigerduck.ui.component.PageSettings

/**
 * The settings page a page's ⋮ opens, pushed as Settings pushes every one of its pages. Bulletins,
 * School Mail and Library have a page of their own; the rows of Home, the class table and Moodle
 * sit among the others in Settings itself, which is where theirs opens.
 */
internal val PageSettings.route: String
    get() = when (this) {
        PageSettings.HOME, PageSettings.CLASS_TABLE, PageSettings.MOODLE -> Screen.Settings.route
        PageSettings.BULLETINS -> Screen.AnnouncementSubscriptions.route
        PageSettings.MAIL -> Screen.SchoolMailSettings.route
        PageSettings.LIBRARY -> Screen.LibrarySettings.route
    }

/** The app's navigation for [content], with every page under it able to open its settings. */
@Composable
internal fun PageSettingsHost(content: @Composable (NavHostController) -> Unit) {
    val navController = rememberNavController()
    val open = remember(navController) { { page: PageSettings -> navController.navigate(page.route) } }
    CompositionLocalProvider(LocalOpenPageSettings provides open) { content(navController) }
}
