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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodleRoutes
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class AssignmentRouteTest {

    @Test
    fun `an assignment's page names its assignment`() {
        assertEquals("900001", Screen.Assignment.idIn(Screen.Assignment.route("900001")))
    }

    @Test
    fun `any other route names none`() {
        assertNull(Screen.Assignment.idIn(Screen.ClassTable.route))
        assertNull(Screen.Assignment.idIn("assignment/"))
        assertNull(Screen.Assignment.idIn(Screen.AssignmentReminderSettings.route))
    }
}

/** Whether a link names the page on screen, on a real NavHost: its routes carry their arguments. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LinkTargetTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    @Before
    fun setUp() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = Screen.ClassTable.route) {
                composable(Screen.ClassTable.route) {}
                composable(
                    Screen.AnnouncementDetail.route,
                    arguments = listOf(navArgument("id") { type = NavType.IntType }),
                ) {}
                composable(
                    Screen.SchoolMailMessage.route,
                    arguments = listOf(
                        navArgument("folder") { type = NavType.StringType },
                        navArgument("uid") { type = NavType.LongType },
                    ),
                ) {}
                composable(
                    Screen.Assignment.route,
                    arguments = listOf(navArgument(MoodleRoutes.ARG_ASSIGNMENT_ID) { type = NavType.StringType }),
                ) {}
                composable(
                    MoodleRoutes.ASSIGNMENT,
                    arguments = listOf(
                        navArgument("courseId") { type = NavType.IntType },
                        navArgument(MoodleRoutes.ARG_ASSIGNMENT_ID) { type = NavType.StringType },
                    ),
                ) {}
            }
        }
        composeRule.waitForIdle()
    }

    private fun open(route: String) {
        composeRule.runOnIdle { navController.navigate(route) }
        composeRule.waitForIdle()
    }

    private fun showsPage(target: String): Boolean {
        var shows = false
        composeRule.runOnIdle { shows = navController.currentBackStackEntry.showsPage(target) }
        return shows
    }

    @Test
    fun `a link to the page on screen names it`() {
        assertTrue(showsPage(Screen.ClassTable.route))
        assertFalse(showsPage(Screen.Home.route))
    }

    /** A link to the bulletin on screen leaves it, and what is over it, as they are. */
    @Test
    fun `a bulletin link names the bulletin on screen`() {
        open(Screen.AnnouncementDetail.route(7))

        assertTrue(showsPage(Screen.AnnouncementDetail.route(7)))
        assertFalse("another bulletin is another page", showsPage(Screen.AnnouncementDetail.route(8)))
    }

    /** As School Mail's notification writes it, with the folder's name encoded. */
    @Test
    fun `a mail link names the mail on screen`() {
        open(Screen.SchoolMailMessage.route("Sent Items", 42))

        assertTrue(showsPage(Screen.SchoolMailMessage.route("Sent Items", 42)))
        assertFalse("another mail is another page", showsPage(Screen.SchoolMailMessage.route("Sent Items", 43)))
        assertFalse("so is one in another folder", showsPage(Screen.SchoolMailMessage.route("INBOX", 42)))
    }

    @Test
    fun `a reminder names the assignment a course opened`() {
        open(MoodleRoutes.assignment(42, "900001"))

        assertTrue(showsPage(Screen.Assignment.route("900001")))
        assertFalse("another assignment is another page", showsPage(Screen.Assignment.route("900002")))
    }

    @Test
    fun `a reminder names the assignment a reminder opened`() {
        open(Screen.Assignment.route("900001"))

        assertTrue(showsPage(Screen.Assignment.route("900001")))
        assertFalse(showsPage(Screen.Assignment.route("900002")))
    }
}
