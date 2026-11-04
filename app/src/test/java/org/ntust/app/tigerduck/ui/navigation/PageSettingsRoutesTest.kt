package org.ntust.app.tigerduck.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.ui.component.LocalOpenPageSettings
import org.ntust.app.tigerduck.ui.component.PageSettings
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where each page's ⋮ Settings goes: the page Android already has for its settings, else Settings,
 * whose rows hold that page's. Pushed, as Settings pushes all of its pages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PageSettingsRoutesTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `each page opens the settings page Android already has for it`() {
        assertEquals(
            mapOf(
                PageSettings.HOME to Screen.Settings.route,
                PageSettings.CLASS_TABLE to Screen.Settings.route,
                PageSettings.BULLETINS to Screen.AnnouncementSubscriptions.route,
                PageSettings.MAIL to Screen.SchoolMailSettings.route,
                PageSettings.LIBRARY to Screen.LibrarySettings.route,
                PageSettings.MOODLE to Screen.Settings.route,
            ),
            PageSettings.entries.associateWith { it.route },
        )
    }

    @Test
    fun `a page under the host pushes its settings over itself`() {
        lateinit var nav: NavHostController
        composeRule.setContent {
            PageSettingsHost { navController ->
                nav = navController
                NavHost(navController, startDestination = "page") {
                    composable("page") {
                        val open = LocalOpenPageSettings.current
                        TextButton(onClick = { open(PageSettings.MAIL) }) { Text("Settings") }
                    }
                    composable(Screen.SchoolMailSettings.route) { Text("School Mail settings") }
                }
            }
        }
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("School Mail settings").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals("page", nav.previousBackStackEntry?.destination?.route) }
    }
}
