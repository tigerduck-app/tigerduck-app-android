// Drives a real NavHost through the bottom bar's own navigateToTab and reads
// which way each pop's transition decided. NavTransitionsTest feeds
// slidesBetween a tabTapped flag directly; this pins the handoff behind that
// flag — the entry ids TabTap records around navigate() have to be the ones
// NavHost hands the transition — so a tap on the first tab from a pushed
// screen can't drift back to sliding like a back while every rule test passes.

package org.ntust.app.tigerduck.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class NavHostTabTapTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val tabs = setOf("home", "more")
    private val tabTap = TabTap()
    private lateinit var navController: NavHostController

    /** What the latest pop transition decided: true slides, false fades. */
    private var lastPopSlides: Boolean? = null

    @Before
    fun setUp() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = "home",
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
                popEnterTransition = { fadeIn() },
                popExitTransition = {
                    lastPopSlides = slides(isPop = true, tabs, tabTap)
                    fadeOut()
                },
            ) {
                composable("home") {}
                composable("more") {}
                composable("settings") {}
            }
        }
    }

    private fun step(action: NavHostController.() -> Unit) {
        composeRule.runOnIdle { navController.action() }
        composeRule.waitForIdle()
    }

    private fun openSettingsFromMore() {
        step { navigateToTab("more", firstTab = "home", tabTap) }
        step { navigate("settings") }
    }

    @Test
    fun `tapping the first tab from a pushed screen fades`() {
        openSettingsFromMore()
        step { navigateToTab("home", firstTab = "home", tabTap) }
        assertEquals("home", navController.currentBackStackEntry?.destination?.route)
        assertEquals(false, lastPopSlides)
    }

    @Test
    fun `backing out of the same pushed screen slides`() {
        openSettingsFromMore()
        step { popBackStack() }
        assertEquals("more", navController.currentBackStackEntry?.destination?.route)
        assertEquals(true, lastPopSlides)
    }

    @Test
    fun `a back after a first-tab tap still slides`() {
        openSettingsFromMore()
        step { navigateToTab("home", firstTab = "home", tabTap) }
        openSettingsFromMore()
        step { popBackStack() }
        assertEquals(true, lastPopSlides)
    }
}
