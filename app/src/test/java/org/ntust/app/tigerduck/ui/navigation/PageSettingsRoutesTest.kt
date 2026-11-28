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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.mail.MailRoutes
import org.ntust.app.tigerduck.ui.component.LocalOpenPageSettings
import org.ntust.app.tigerduck.ui.component.PageSettings
import org.ntust.app.tigerduck.ui.screen.settings.SettingsLink
import org.ntust.app.tigerduck.ui.screen.settings.SettingsSubPage
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where each page's ⋮ Settings goes: that page's own page of Settings (tigerduck-app#939), one per
 * page. Pushed, as Settings pushes all of its pages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PageSettingsRoutesTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `each page opens a settings page of its own`() {
        val routes = PageSettings.entries.map { it.route }
        assertEquals(PageSettings.entries.size, routes.toSet().size)
        assertEquals("settings/page/class_table", PageSettings.CLASS_TABLE.route)
        assertTrue(routes.none { it == Screen.Settings.route })
    }

    @Test
    fun `each sub-page opens a page of its own, About › Others where it always was`() {
        val routes = SettingsSubPage.entries.map { it.link.route }
        assertEquals(SettingsSubPage.entries.size, routes.toSet().size)
        assertTrue(routes.none { route -> PageSettings.entries.any { it.route == route } })
        assertEquals(Screen.AboutOthers.route, SettingsSubPage.ABOUT_OTHERS.link.route)
        assertEquals("settings/sub/notification_switches", SettingsSubPage.NOTIFICATION_SWITCHES.link.route)
    }

    @Test
    fun `School Mail's "Use another app" guide has no page to open`() {
        assertTrue(SettingsLink.entries.none { it.name.contains("GUIDE") })
        assertTrue(Screen::class.java.declaredClasses.none { it.simpleName.contains("Guide") })
        assertTrue(MailRoutes::class.java.declaredFields.none { it.name.contains("GUIDE") })
    }

    @Test
    fun `a debug page is opened only in a debug build, where it is registered`() {
        assertEquals(
            SettingsLink.entries.filter { it.route.contains("debug", ignoreCase = true) && it != SettingsLink.API_ENDPOINT },
            SettingsLink.entries.filter { it.debugOnly },
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
                    composable(PageSettings.MAIL.route) { Text("School Mail settings") }
                }
            }
        }
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("School Mail settings").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals("page", nav.previousBackStackEntry?.destination?.route) }
    }
}
