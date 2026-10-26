package org.ntust.app.tigerduck.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.ui.screen.coursemoodle.MoodleRoutes
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class CourseDetailSheetRulesTest {

    @Test
    fun `the sheet shows while a course is picked and its page is on top`() {
        assertTrue(CourseDetailSheetRules.shows(picked = true, pageOnTop = true))
    }

    @Test
    fun `a page opened over the sheet's page hides the sheet`() {
        assertFalse(CourseDetailSheetRules.shows(picked = true, pageOnTop = false))
    }

    @Test
    fun `no course picked, no sheet`() {
        assertFalse(CourseDetailSheetRules.shows(picked = false, pageOnTop = true))
    }

}

/** The pages the course detail opens, on a real NavHost: full screen, in the app's own navigation. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CoursePagesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController
    private var classTableOnTop = false

    private fun showNavigation() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = Screen.ClassTable.route) {
                composable(Screen.ClassTable.route) { entry -> classTableOnTop = navController.isOnTop(entry) }
                coursePages(navController, browserPreference = { "" })
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `every page the course detail opens is a page of the app's navigation`() {
        showNavigation()
        val routes = listOf(
            MoodleRoutes.ANNOUNCEMENTS,
            MoodleRoutes.ANNOUNCEMENT,
            MoodleRoutes.MATERIALS,
            MoodleRoutes.FORUMS,
            MoodleRoutes.FORUM,
            MoodleRoutes.DISCUSSION,
            MoodleRoutes.ASSIGNMENTS,
            MoodleRoutes.ASSIGNMENT,
            MoodleRoutes.GRADES,
            MoodleRoutes.MEMBERS,
            MoodleRoutes.MEMBER,
        )
        composeRule.runOnIdle {
            routes.forEach { assertNotNull(it, navController.graph.findNode(it)) }
        }
    }

    @Test
    fun `the page on top reads as on top from its first frame`() {
        // A sheet open across a change of dark mode would otherwise close for a frame, and come
        // back rebuilt at half height.
        var firstFrame: Boolean? = null
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = Screen.ClassTable.route) {
                composable(Screen.ClassTable.route) { entry ->
                    val onTop = navController.isOnTop(entry)
                    if (firstFrame == null) firstFrame = onTop
                }
            }
        }
        composeRule.waitForIdle()
        assertTrue(firstFrame == true)
    }

    @Test
    fun `the sheet's page is off the top while a page it opened shows, and back on top after a back`() {
        // A stand-in page under a course route: the Moodle screens need Hilt, which this test has not.
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = Screen.ClassTable.route) {
                composable(Screen.ClassTable.route) { entry -> classTableOnTop = navController.isOnTop(entry) }
                composable(MoodleRoutes.ASSIGNMENTS) {}
            }
        }
        composeRule.waitForIdle()
        assertTrue(classTableOnTop)

        composeRule.runOnIdle { navController.navigate(MoodleRoutes.assignments(42)) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        assertFalse("the sheet steps aside the moment the page opens", classTableOnTop)

        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitForIdle()
        assertTrue(classTableOnTop)
    }
}

/**
 * Where the course sheet was left, on a real NavHost: kept with its page while a page opened from
 * the sheet covers it, so the sheet comes back scrolled and pulled up as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CourseSheetPlaceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController
    private var place: CourseSheetPlace? = null
    private val course = androidx.compose.runtime.mutableStateOf("CS1001301")

    @Before
    fun setUp() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = Screen.ClassTable.route) {
                composable(Screen.ClassTable.route) { place = rememberCourseSheetPlace(course.value) }
                composable(MoodleRoutes.ASSIGNMENTS) { place = null }
            }
        }
        composeRule.waitForIdle()
    }

    private fun leaveAndComeBack() {
        composeRule.runOnIdle { navController.navigate(MoodleRoutes.assignments(42)) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitForIdle()
    }

    @Test
    fun `the sheet comes back scrolled and pulled up as it was left`() {
        composeRule.runOnIdle {
            runBlocking { place!!.scroll.scrollTo(300) }
            place!!.expanded = true
        }

        leaveAndComeBack()

        composeRule.runOnIdle {
            assertEquals(300, place!!.scroll.value)
            assertTrue(place!!.expanded)
        }
    }

    @Test
    fun `another course starts at the top and half height`() {
        composeRule.runOnIdle {
            runBlocking { place!!.scroll.scrollTo(300) }
            place!!.expanded = true
        }

        course.value = "CS2012701"
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(0, place!!.scroll.value)
            assertFalse(place!!.expanded)
        }
    }
}

class CourseDetailSheetModelsTest {

    class Probe : ViewModel() {
        var cleared = false
        override fun onCleared() {
            cleared = true
        }
    }

    private fun probeIn(models: CourseDetailSheetModels): Probe =
        ViewModelProvider.create(models, ViewModelProvider.NewInstanceFactory())[Probe::class]

    @Test
    fun `closing the sheet clears the view models of its pages`() {
        val models = CourseDetailSheetModels()
        val probe = probeIn(models)

        models.close()

        assertTrue(probe.cleared)
    }

    @Test
    fun `the sheet opened next gets view models of its own`() {
        val models = CourseDetailSheetModels()
        val first = probeIn(models)
        models.close()

        assertTrue(probeIn(models) !== first)
    }

    @Test
    fun `leaving the class table clears the view models of an open sheet`() {
        val page = ViewModelStore()
        val models = ViewModelProvider.create(page, ViewModelProvider.NewInstanceFactory())[CourseDetailSheetModels::class]
        val probe = probeIn(models)

        page.clear()

        assertTrue(probe.cleared)
    }
}
