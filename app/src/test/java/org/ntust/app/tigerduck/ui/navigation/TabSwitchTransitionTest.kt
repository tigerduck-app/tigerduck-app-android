// Drives a real NavHost through the app's own transitions and reads what is on screen just after
// each move: a bottom-bar tab swaps at once, while a screen opened over a tab still slides.

package org.ntust.app.tigerduck.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class TabSwitchTransitionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val tabs = setOf("home", "mail", "more")
    private val tabTap = TabTap()
    private lateinit var navController: NavHostController

    @Before
    fun setUp() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = "home",
                enterTransition = { pushEnter(tabs, tabTap) },
                exitTransition = { pushExit(tabs, tabTap) },
                popEnterTransition = { popEnter(tabs, tabTap) },
                popExitTransition = { popExit(tabs, tabTap) },
            ) {
                for (route in listOf("home", "mail", "more", "settings")) {
                    page(route) { Box(Modifier.fillMaxSize().testTag(route)) }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
    }

    /**
     * [action], then 80 ms: five frames, enough for Navigation to drop a page it swapped out at
     * once (it takes four), and well inside the 150 ms crossfade tabs had.
     */
    private fun justAfter(action: NavHostController.() -> Unit) {
        composeRule.runOnUiThread { navController.action() }
        composeRule.mainClock.advanceTimeBy(80)
    }

    @Test
    fun `a tab tap shows the tab at once, with nothing of the one before it`() {
        justAfter { navigateToTab("mail", firstTab = "home", tabTap) }

        composeRule.onNodeWithTag("mail").assertExists()
        // A fade would still hold the page it leaves, part see-through over the background.
        composeRule.onNodeWithTag("home").assertDoesNotExist()
    }

    @Test
    fun `a back from a tab to the first tab swaps at once too`() {
        composeRule.mainClock.autoAdvance = true
        composeRule.runOnIdle { navController.navigateToTab("mail", firstTab = "home", tabTap) }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false

        justAfter { popBackStack() }

        composeRule.onNodeWithTag("home").assertExists()
        composeRule.onNodeWithTag("mail").assertDoesNotExist()
    }

    @Test
    fun `a screen opened over a tab still slides in over it`() {
        justAfter { navigate("settings") }

        composeRule.onNodeWithTag("home").assertExists()
        // Still on its way in from the end edge, not faded in where it will rest.
        val left = composeRule.onNodeWithTag("settings").getUnclippedBoundsInRoot().left
        assertTrue("not sliding: at $left", left > 0.dp)
    }
}
