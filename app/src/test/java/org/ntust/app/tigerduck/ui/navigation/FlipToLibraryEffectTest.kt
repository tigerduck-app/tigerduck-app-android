// Drives the real flip effect: a rotation-vector sensor fed face-up then face-down
// readings, a real NavHost, and the activity window's focus switched off and on the way
// a dialog or the shade switches it. FlipToLibraryGateTest pins the rule; this pins that
// the effect asks it, so a flip over a dialog cannot quietly reach Library again.

package org.ntust.app.tigerduck.ui.navigation

import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import org.ntust.app.tigerduck.mail.ComposeMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorBuilder
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class FlipToLibraryEffectTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController
    private lateinit var sensor: Sensor
    private var nowNanos = 0L
    private var haptics = 0

    /** Questions asked. */
    private var offers = 0

    /** Where the question's Turn on goes, the app-wide singleton in the app. */
    private val openRequest = FlipToLibraryOpenRequest()

    /** Moved on to rebuild the page with a new NavController, the part of an activity recreation the question outlives. */
    private var generation by mutableStateOf(0)

    /** The switch, which a Turn on stores on. */
    private var switchOn by mutableStateOf(true)

    /** Whether the library session still holds when checked again, as at a flip. */
    private var sessionValid = true

    @Before
    fun setUp() {
        sensor = SensorBuilder.newBuilder().setType(Sensor.TYPE_ROTATION_VECTOR).build()
        shadowOf(sensorManager()).addSensor(sensor)
    }

    private fun sensorManager() = composeRule.activity.getSystemService(SensorManager::class.java)

    /**
     * The app as MainNavigation wires it: the effect first, then the NavHost it drives. [enabled]
     * is the switch, [offerUnanswered] whether the question that offers it is still to ask.
     */
    private fun show(
        withGraph: Boolean = true,
        enabled: Boolean = true,
        signedIn: Boolean = true,
        offerUnanswered: () -> Boolean = { false },
    ) {
        switchOn = enabled
        composeRule.setContent { key(generation) {
            navController = rememberNavController()
            FlipToLibraryEffect(
                navController = navController,
                flipToLibraryEnabled = { switchOn },
                signedInToLibrary = { signedIn },
                stillSignedInToLibrary = { signedIn && sessionValid },
                onNavigated = { haptics++ },
                offerUnanswered = offerUnanswered,
                onOffer = { offers++ },
                openRequest = openRequest,
            )
            if (withGraph) {
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {}
                    composable(Screen.Library.route) {}
                    composable(
                        Screen.SchoolMailCompose.route,
                        arguments = listOf(
                            navArgument("mode") { type = NavType.StringType; defaultValue = ComposeMode.NEW.name },
                            navArgument("folder") { type = NavType.StringType; defaultValue = "" },
                            navArgument("uid") { type = NavType.LongType; defaultValue = -1L },
                        ),
                    ) {}
                }
            }
        } }
        composeRule.waitForIdle()
    }

    /** The page and its NavController made anew, as a theme, font or display size change does. */
    private fun rebuildPage() {
        composeRule.runOnUiThread { generation++ }
        composeRule.waitForIdle()
    }

    /** The question's Turn on, as FlipToLibraryPrompt gives it: the switch on, then the request. */
    private fun turnOn() {
        composeRule.runOnUiThread {
            switchOn = true
            openRequest.request()
        }
        composeRule.waitForIdle()
    }

    /**
     * The window manager telling the activity's window it gained or lost the focus, through
     * the same ViewRootImpl call Robolectric's ActivityController makes, so every focus
     * listener Compose has hears it.
     */
    private fun focusWindow(focused: Boolean) {
        composeRule.runOnUiThread {
            val root = ReflectionHelpers.callInstanceMethod<Any>(composeRule.activity.window.decorView, "getViewRootImpl")
            ReflectionHelpers.callInstanceMethod<Any>(
                root,
                "windowFocusChanged",
                ClassParameter.from(Boolean::class.javaPrimitiveType, focused),
            )
        }
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
    }

    /** Face up long enough to arm, then face down long enough to fire: one flip. */
    private fun flip() {
        composeRule.runOnUiThread {
            listOf(UPRIGHT, UPRIGHT, FACE_DOWN, FACE_DOWN).forEach { values ->
                shadowOf(sensorManager()).sendSensorEventToListeners(
                    SensorEventBuilder.newBuilder(sensor, values.copyOf())
                        .setTimestamp(nowNanos)
                        .build(),
                )
                nowNanos += HALF_SECOND_NANOS
            }
        }
        composeRule.waitForIdle()
    }

    private fun route() = navController.currentDestination?.route

    @Test
    fun `the first flip after the app opens reaches Library`() {
        // The cold start a queued flip once covered: the effect is composed before the
        // NavHost, as in MainNavigation, and this is the earliest a flip can land. Sensor
        // events are only handled once the frame that composed both is done.
        show()
        focusWindow(true)
        flip()
        assertEquals(Screen.Library.route, route())
        assertEquals(1, haptics)
    }

    @Test
    fun `a flip while something else has the window's focus does nothing, and the next one works`() {
        show()
        // A dialog, a sheet, a menu or the shade: each takes the focus from the app's window.
        focusWindow(false)
        flip()
        assertEquals("home", route())
        assertEquals(0, haptics)

        focusWindow(true)
        flip()
        assertEquals(Screen.Library.route, route())
    }

    @Test
    fun `a flip on the mail composer does nothing`() {
        show()
        focusWindow(true)
        composeRule.runOnUiThread { navController.navigate(Screen.SchoolMailCompose.route(ComposeMode.NEW)) }
        composeRule.waitForIdle()
        flip()
        assertEquals(Screen.SchoolMailCompose.route, route())
    }

    @Test
    fun `a flip with no graph is dropped rather than crashing`() {
        show(withGraph = false)
        focusWindow(true)
        flip()
        assertNull(navController.currentBackStackEntry)
    }

    @Test
    fun `with the switch off, a flip asks whether to turn it on rather than opening Library`() {
        show(enabled = false, offerUnanswered = { true })
        focusWindow(true)
        flip()
        assertEquals(1, offers)
        assertEquals("home", route())
        assertEquals(0, haptics)
    }

    @Test
    fun `turning it on from the question opens Library for that flip`() {
        show(enabled = false, offerUnanswered = { true })
        focusWindow(true)
        flip()
        turnOn()
        assertEquals(Screen.Library.route, route())
        assertEquals(1, haptics)
    }

    /**
     * The question is the app's, not the page's: it stays up while a theme, font or display
     * size change makes the activity anew under it, and its Turn on opens Library on the new
     * page. The page is rebuilt here with a new NavController rather than a new activity.
     */
    @Test
    fun `a Turn on after the page is rebuilt with a new NavController opens Library on the new one`() {
        show(enabled = false, offerUnanswered = { true })
        focusWindow(true)
        flip()
        val asked = navController
        rebuildPage()
        turnOn()
        assertEquals(Screen.Library.route, route())
        assertEquals(1, haptics)
        assertEquals("home", asked.currentDestination?.route)
    }

    /** The sign-in can be refused between the question and its answer. */
    @Test
    fun `a Turn on after the library session ends opens nothing`() {
        show(enabled = false, offerUnanswered = { true })
        focusWindow(true)
        flip()
        sessionValid = false
        turnOn()
        assertEquals("home", route())
        assertEquals(0, haptics)
        assertFalse(openRequest.take())
    }

    @Test
    fun `nothing is asked before a flip`() {
        show(enabled = false, offerUnanswered = { true })
        focusWindow(true)
        assertEquals(0, offers)
    }

    @Test
    fun `with the switch off and the question answered, nothing listens`() {
        show(enabled = false, offerUnanswered = { false })
        focusWindow(true)
        assertTrue(shadowOf(sensorManager()).listeners.isEmpty())
        flip()
        assertEquals(0, offers)
        assertEquals("home", route())
    }

    @Test
    fun `an answer to the question stops the listening`() {
        var unanswered by mutableStateOf(true)
        show(enabled = false, offerUnanswered = { unanswered })
        focusWindow(true)
        assertFalse(shadowOf(sensorManager()).listeners.isEmpty())
        composeRule.runOnUiThread { unanswered = false }
        composeRule.waitForIdle()
        assertTrue(shadowOf(sensorManager()).listeners.isEmpty())
    }

    @Test
    fun `signed out of the library, a flip asks nothing`() {
        show(enabled = false, signedIn = false, offerUnanswered = { true })
        focusWindow(true)
        flip()
        assertEquals(0, offers)
    }

    @Test
    fun `a flip over a dialog asks nothing, and the next one asks`() {
        show(enabled = false, offerUnanswered = { true })
        focusWindow(false)
        flip()
        assertEquals(0, offers)

        focusWindow(true)
        flip()
        assertEquals(1, offers)
    }

    @Test
    fun `a flip on the Library page asks nothing`() {
        show(enabled = false, offerUnanswered = { true })
        focusWindow(true)
        composeRule.runOnUiThread { navController.navigate(Screen.Library.route) }
        composeRule.waitForIdle()
        flip()
        assertEquals(0, offers)
    }

    @Test
    fun `a flip on the mail composer asks nothing`() {
        show(enabled = false, offerUnanswered = { true })
        focusWindow(true)
        composeRule.runOnUiThread { navController.navigate(Screen.SchoolMailCompose.route(ComposeMode.NEW)) }
        composeRule.waitForIdle()
        flip()
        assertEquals(0, offers)
    }

    private companion object {
        val UPRIGHT = floatArrayOf(0f, 0f, 0f, 1f)
        // Half a turn about the x axis: the screen faces the floor.
        val FACE_DOWN = floatArrayOf(1f, 0f, 0f, 0f)
        const val HALF_SECOND_NANOS = 500_000_000L
    }
}
