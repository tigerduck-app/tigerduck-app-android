// The browser menu stepping out of the way of the page while its zoom changes: only the zoom
// controls stay, until a second after the last change.

package org.ntust.app.tigerduck.ui.screen.browser

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.browser.BrowserAppearance
import org.ntust.app.tigerduck.core.strings.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.material3.R as M3R

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class BrowserZoomPeekTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()

    private val actions = object : BrowserActions() {
        override fun zoomIn() {}
        override fun zoomOut() {}
        override fun setDesktopSite(desktop: Boolean) { calls += "desktop $desktop" }
    }


    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun openMenu() {
        composeRule.setContent {
            MaterialTheme {
                val state = BrowserPageState().apply {
                    url = "https://moodle2.ntust.edu.tw/my/"
                    title = "Dashboard"
                }
                InAppBrowserScreen(state, BrowserAppearance.AUTO, emptyList(), actions) { Box(it) }
            }
        }
        composeRule.onNodeWithContentDescription(string(R.string.browser_more)).performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
    }

    private fun zoomIn() = composeRule.onNodeWithContentDescription(string(R.string.browser_zoom_in))
    private fun zoomOut() = composeRule.onNodeWithContentDescription(string(R.string.browser_zoom_out))
    private fun desktopSite() = composeRule.onNodeWithText(string(R.string.browser_desktop_site))
    private fun handle() = composeRule.onNodeWithContentDescription(
        string(M3R.string.m3c_bottom_sheet_drag_handle_description),
    )

    @Test
    fun `zooming leaves only the zoom controls, and brings the rest back a second later`() {
        openMenu()

        zoomIn().performClick()
        composeRule.mainClock.advanceTimeBy(300)

        zoomIn().assertExists()
        zoomOut().assertExists()
        composeRule.onNodeWithText("100%").assertExists()
        desktopSite().assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.browser_zoom)).assertDoesNotExist()
        // The sheet itself steps aside too: its handle with the rest.
        handle().assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(1_000)
        desktopSite().assertExists()
        handle().assertExists()
    }

    @Test
    fun `each change keeps the menu out of the way for another second`() {
        openMenu()

        zoomIn().performClick()
        composeRule.mainClock.advanceTimeBy(700)
        zoomOut().performClick()
        composeRule.mainClock.advanceTimeBy(700)
        desktopSite().assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(400)
        desktopSite().assertExists()
    }

    @Test
    fun `the percentage, putting the zoom back, steps the menu aside too`() {
        openMenu()

        composeRule.onNodeWithText("100%").performClick()
        composeRule.mainClock.advanceTimeBy(300)

        desktopSite().assertDoesNotExist()
    }

    @Test
    fun `a keyboard can't reach the parts of the menu that stepped aside`() {
        openMenu()
        zoomIn().performClick()
        composeRule.mainClock.advanceTimeBy(300)

        repeat(6) {
            // Tab puts the window in keyboard mode, where the menu's rows take focus.
            composeRule.onNodeWithTag(BROWSER_SHEET_TAG).performKeyInput { pressKey(Key.Tab) }
            composeRule.mainClock.advanceTimeByFrame()
            // A hidden row has no semantics while it hides, so focus on one finds no node in the
            // sheet at all. (The activity's own window keeps a focus of its own.)
            composeRule.onNode(isFocused() and hasAnyAncestor(hasTestTag(BROWSER_SHEET_TAG))).assertExists()
        }
    }

    @Test
    fun `the sheet still drags down from where the menu was`() {
        openMenu()
        val sheet = composeRule.onNodeWithTag(BROWSER_SHEET_TAG).getBoundsInRoot()
        val row = desktopSite().getBoundsInRoot()

        zoomIn().performClick()
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithTag(BROWSER_SHEET_TAG).performTouchInput {
            val density = composeRule.density.density
            val from = Offset(width / 2f, (row.top - sheet.top).value * density + 8f)
            swipe(from, from + Offset(0f, 600f), durationMillis = 60)
        }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag(BROWSER_SHEET_TAG).assertCountEquals(0)
    }

    @Test
    fun `the hidden handle doesn't close the sheet`() {
        openMenu()
        val sheet = composeRule.onNodeWithTag(BROWSER_SHEET_TAG).getBoundsInRoot()
        val handle = handle().getBoundsInRoot()

        zoomIn().performClick()
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.mainClock.autoAdvance = true
        // The handle sits above the pages' Box, so the tap goes in from the Box at a negative offset.
        composeRule.onNodeWithTag(BROWSER_SHEET_TAG).performTouchInput {
            val density = composeRule.density.density
            click(Offset(((handle.left + handle.right) / 2 - sheet.left).value * density, ((handle.top + handle.bottom) / 2 - sheet.top).value * density))
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(BROWSER_SHEET_TAG).assertExists()
    }

    @Test
    fun `a tap where the menu was brings it back at once`() {
        openMenu()
        val sheet = composeRule.onNodeWithTag(BROWSER_SHEET_TAG).getBoundsInRoot()
        val row = desktopSite().getBoundsInRoot()

        zoomIn().performClick()
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.onNodeWithTag(BROWSER_SHEET_TAG).performTouchInput {
            val density = composeRule.density.density
            click(Offset((row.left - sheet.left).value * density + 8f, (row.top - sheet.top).value * density + 8f))
        }
        // The tap's change reaches the screen once the main thread has run; the clock stays well
        // short of the second that would bring the menu back by itself.
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(100)

        desktopSite().assertExists()
    }
}
