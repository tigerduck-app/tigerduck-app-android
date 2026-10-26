package org.ntust.app.tigerduck.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.mail.ComposeMode
import org.ntust.app.tigerduck.mail.MailRoutes
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The start route's navigate, on a real NavHost: a route the graph cannot
 * take leaves the app where it is instead of throwing out of the effect.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StartRouteNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    @Before
    fun setUp() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = "home") {
                composable("home") {}
                composable("classTable") {}
                composable(
                    MailRoutes.COMPOSE,
                    arguments = listOf("mode", "folder", "mailto", "to", "held").map { name ->
                        navArgument(name) { type = NavType.StringType; defaultValue = "" }
                    } + navArgument("uid") { type = NavType.LongType; defaultValue = -1L },
                ) {}
                composable(
                    "schoolMail/message/{folder}/{uid}",
                    arguments = listOf(
                        navArgument("folder") { type = NavType.StringType },
                        navArgument("uid") { type = NavType.LongType },
                    ),
                ) {}
            }
        }
    }

    private fun open(route: String): Boolean {
        var opened = false
        composeRule.runOnIdle { opened = navController.navigateToStartRoute(route) }
        composeRule.waitForIdle()
        return opened
    }

    private val current get() = navController.currentBackStackEntry?.destination?.route

    @Test
    fun `a route the graph has opens`() {
        assertTrue(open("classTable"))
        assertEquals("classTable", current)
    }

    @Test
    fun `a route the graph does not have leaves the app where it is`() {
        assertFalse(open("no-such-route"))
        assertEquals("home", current)
    }

    @Test
    fun `an argument the graph cannot read leaves the app where it is`() {
        assertFalse(open("schoolMail/message/INBOX/9999999999999999999"))
        assertEquals("home", current)
    }

    @Test
    fun `a kept mail's composer replaces a composer on top instead of reusing it`() {
        // One the system rebuilt after ending the app, which would keep its view model under singleTop.
        assertTrue(open(MailRoutes.compose(ComposeMode.NEW)))
        val rebuilt = navController.currentBackStackEntry!!.id

        assertTrue(open(MailRoutes.compose(ComposeMode.NEW, held = "m1")))

        val entry = navController.currentBackStackEntry!!
        assertNotEquals(rebuilt, entry.id)
        assertEquals("m1", entry.arguments?.getString("held"))
        composeRule.runOnIdle { navController.popBackStack() }
        assertEquals("home", current)
    }
}
