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
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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

    /** What the latest pop transition decided: true slides, false swaps at once. */
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
    fun `tapping the first tab from a pushed screen swaps at once`() {
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

/** A page that closes itself must close only itself, however many times it asks. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PopIfTopTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    @Test
    fun `a second pop for the same page leaves the page under it`() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = "classTable") {
                composable("classTable") {}
                composable("classTable/course") {}
                composable("courseMoodle/announcements") {}
            }
        }
        composeRule.runOnIdle {
            navController.navigate("classTable/course")
            navController.navigate("courseMoodle/announcements")
        }
        composeRule.waitForIdle()
        val top = composeRule.runOnIdle { navController.currentBackStackEntry!! }

        composeRule.runOnIdle {
            navController.popIfTop(top)
            navController.popIfTop(top)
        }
        composeRule.waitForIdle()

        assertEquals("classTable/course", navController.currentBackStackEntry?.destination?.route)
    }
}

/** A widget or notification opening a tab from a page pushed over it lands on that tab, not a second copy of it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StartRouteTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    @Test
    fun `opening a tab from a page pushed over it goes back to the tab`() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = "home") {
                composable("home") {}
                composable("more") {}
                composable("settings") {}
            }
        }
        composeRule.runOnIdle {
            navController.navigate("more")
            navController.navigate("settings")
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            navController.openStartRoute("more", tabRoutes = setOf("home", "more"), firstTab = "home", TabTap())
        }
        composeRule.waitForIdle()

        assertEquals("more", navController.currentBackStackEntry?.destination?.route)
        assertEquals("home", navController.previousBackStackEntry?.destination?.route)
    }

    @Test
    fun `opening the tab already on screen leaves it as it is`() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = "home") {
                composable("home") {}
                composable("classTable") {}
            }
        }
        composeRule.runOnIdle { navController.navigate("classTable") }
        composeRule.waitForIdle()
        val before = composeRule.runOnIdle { navController.currentBackStackEntry!!.id }

        composeRule.runOnIdle {
            navController.openStartRoute("classTable", tabRoutes = setOf("home", "classTable"), firstTab = "home", TabTap())
        }
        composeRule.waitForIdle()

        assertEquals("not rebuilt, so nothing on it is lost", before, navController.currentBackStackEntry!!.id)
    }

    @Test
    fun `a page pushed from More keeps the More tab selected`() {
        assertEquals("more", selectedTabRoute("settings"))
        assertEquals("calendar", selectedTabRoute("calendar"))
    }
}
