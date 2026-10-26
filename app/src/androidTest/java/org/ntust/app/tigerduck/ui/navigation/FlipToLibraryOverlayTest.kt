package org.ntust.app.tigerduck.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.sensor.FlipSensor
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme

/**
 * The flip gate leans on one platform fact: a dialog, a bottom sheet or a menu is a window of
 * its own that takes the input focus from the app's window. Robolectric does not model window
 * focus, so this checks it on a device. Each test opens a real overlay, waits for the activity's
 * window to lose the focus, fires a flip by hand, and checks that Library is not reached until
 * the overlay closes and the focus comes back.
 */
@RunWith(AndroidJUnit4::class)
class FlipToLibraryOverlayTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private enum class Overlay { NONE, SHEET, MENU, DIALOG }

    /** A detector the test fires itself: no test can feed the platform's rotation sensor. */
    private class HandFlip : FlipSensor {
        var onFaceDown: (() -> Unit)? = null
        override fun register() = Unit
        override fun unregister() = Unit
    }

    private val sensor = HandFlip()
    private var overlay by mutableStateOf(Overlay.NONE)
    private lateinit var navController: NavHostController
    private lateinit var windowInfo: WindowInfo

    private fun show() {
        rule.setContent {
            TigerDuckAppTheme(darkTheme = false) {
                navController = rememberNavController()
                // What the effect's gate reads: the app window's focus, as Compose sees it.
                windowInfo = LocalWindowInfo.current
                FlipToLibraryEffect(
                    navController = navController,
                    flipToLibraryEnabled = { true },
                    signedInToLibrary = { true },
                    stillSignedInToLibrary = { true },
                    onNavigated = {},
                    newSensor = { _, onFaceDown -> sensor.also { it.onFaceDown = onFaceDown } },
                )
                NavHost(navController = navController, startDestination = HOME) {
                    composable(HOME) { Overlays() }
                    composable(Screen.Library.route) {}
                }
            }
        }
        awaitAppWindowFocus(true)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Overlays() {
        when (overlay) {
            Overlay.NONE -> Unit
            Overlay.SHEET -> ModalBottomSheet(onDismissRequest = { overlay = Overlay.NONE }) { Text("Sheet") }
            Overlay.MENU -> Box {
                DropdownMenu(expanded = true, onDismissRequest = { overlay = Overlay.NONE }) {
                    DropdownMenuItem(text = { Text("Item") }, onClick = {})
                }
            }
            Overlay.DIALOG -> TigerDuckDialog(onDismissRequest = { overlay = Overlay.NONE }, title = "Dialog")
        }
    }

    private fun open(kind: Overlay) {
        rule.runOnIdle { overlay = kind }
        awaitAppWindowFocus(false)
    }

    private fun close() {
        rule.runOnIdle { overlay = Overlay.NONE }
        awaitAppWindowFocus(true)
    }

    /** Both the platform's answer and the one the gate reads must agree. */
    private fun awaitAppWindowFocus(focused: Boolean) {
        rule.waitUntil(TIMEOUT_MS) {
            var platform = !focused
            var compose = !focused
            rule.runOnUiThread {
                platform = rule.activity.hasWindowFocus()
                compose = windowInfo.isWindowFocused
            }
            platform == focused && compose == focused
        }
    }

    private fun flip() {
        rule.runOnUiThread { checkNotNull(sensor.onFaceDown).invoke() }
        rule.waitForIdle()
    }

    private fun route() = navController.currentDestination?.route

    private fun assertTheFlipWaitsFor(kind: Overlay) {
        show()
        open(kind)
        rule.runOnUiThread { assertFalse("the app window kept the focus", rule.activity.hasWindowFocus()) }
        flip()
        assertEquals("a flip over the $kind left the page", HOME, route())

        close()
        flip()
        assertEquals("the flip after the $kind closed did nothing", Screen.Library.route, route())
    }

    @Test
    fun aBottomSheetTakesTheFocusAndHoldsTheFlipBack() = assertTheFlipWaitsFor(Overlay.SHEET)

    @Test
    fun aDropdownMenuTakesTheFocusAndHoldsTheFlipBack() = assertTheFlipWaitsFor(Overlay.MENU)

    @Test
    fun aDialogTakesTheFocusAndHoldsTheFlipBack() = assertTheFlipWaitsFor(Overlay.DIALOG)

    private companion object {
        const val HOME = "home"
        const val TIMEOUT_MS = 5_000L
    }
}
