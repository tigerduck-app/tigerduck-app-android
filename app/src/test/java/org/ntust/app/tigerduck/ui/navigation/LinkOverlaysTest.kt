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
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a link from a reminder, a bulletin or School Mail leaves over the page it opens, on a real
 * NavHost: what is over the pages closes before that page opens, as on iOS
 * (tigerduck-app/tigerduck-app#672), and a mail being written is asked first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LinkOverlaysTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController
    private val guard = LeaveGuard()

    /** What closed and opened, in order. */
    private val events = mutableListOf<String>()

    private val overlays = LinkOverlays(
        closeCourseSheet = { events += COURSE_SHEET },
        closeTabMenu = { events += TAB_MENU },
        closeSignInSheet = { events += SIGN_IN_SHEET },
        closeImport = { events += IMPORT },
        closeShareSheet = { events += SHARE_SHEET },
    )

    /** What the page on screen does when a link asks it to let go; a mail being written asks. */
    private var asks: ((proceed: () -> Unit) -> Unit)? = null

    private fun showNavigation() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = Screen.Home.route) {
                composable(Screen.Home.route) {}
                composable(Screen.ClassTable.route) {}
                composable(
                    Screen.AnnouncementDetail.route,
                    arguments = listOf(navArgument("id") { type = NavType.IntType }),
                ) {}
            }
            asks?.let { ask -> AskBeforeLeaving(guard) { ask(it) } }
        }
        composeRule.waitForIdle()
    }

    private fun follow(target: String) {
        composeRule.runOnIdle {
            navController.followLink(target, guard, overlays) {
                events += OPENED
                navController.navigate(target)
            }
        }
        composeRule.waitForIdle()
    }

    private fun route(): String? {
        var route: String? = null
        composeRule.runOnIdle { route = navController.currentBackStackEntry?.destination?.route }
        return route
    }

    @Test
    fun `a link to another page closes what is over the pages before that page opens`() {
        showNavigation()

        follow(Screen.AnnouncementDetail.route(7))

        assertEquals(listOf(COURSE_SHEET, TAB_MENU, SIGN_IN_SHEET, SHARE_SHEET, IMPORT, OPENED), events)
        assertEquals(Screen.AnnouncementDetail.route, route())
    }

    /**
     * The import is the class table's, and a share link has just opened one over it. The share
     * sheet the scanner handed over to is no page's, and would cover it.
     */
    @Test
    fun `a link to the class table leaves its import, and closes the scanner's share sheet`() {
        showNavigation()

        follow(Screen.ClassTable.route)

        assertEquals(listOf(COURSE_SHEET, TAB_MENU, SIGN_IN_SHEET, SHARE_SHEET, OPENED), events)
    }

    @Test
    fun `a link to the page on screen leaves it, and what is over it, as they are`() {
        showNavigation()

        follow(Screen.Home.route)

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `a mail being written that keeps its user, as Keep editing does, closes nothing`() {
        asks = { }
        showNavigation()

        follow(Screen.AnnouncementDetail.route(7))

        assertEquals(emptyList<String>(), events)
        assertEquals(Screen.Home.route, route())
    }

    @Test
    fun `a mail being written closes what is over the pages only once it lets go`() {
        var held: (() -> Unit)? = null
        asks = { held = it }
        showNavigation()

        follow(Screen.AnnouncementDetail.route(7))
        assertEquals("waits on Save draft or Discard", emptyList<String>(), events)

        assertNotNull(held)
        composeRule.runOnIdle { held!!() }
        composeRule.waitForIdle()
        assertEquals(listOf(COURSE_SHEET, TAB_MENU, SIGN_IN_SHEET, SHARE_SHEET, IMPORT, OPENED), events)
    }

    private companion object {
        const val COURSE_SHEET = "course sheet"
        const val TAB_MENU = "tab menu"
        const val SIGN_IN_SHEET = "sign-in sheet"
        const val IMPORT = "import"
        const val SHARE_SHEET = "scanner's share sheet"
        const val OPENED = "opened"
    }
}
