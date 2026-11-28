// What's New over the whole screen: its top row sits just under the status bar, and the status bar's
// icons stay readable on the page. A flow an update shows has no way out but its last page's button:
// no close button, and Back goes a page back. One opened again from Settings closes on Back.
//
// Robolectric gives the window no status bar, so open hands the page's window one.

package org.ntust.app.tigerduck.ui.screen.whatsnew

import android.app.Application
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.WhatsNewSummary
import org.ntust.app.tigerduck.data.model.WhatsNewSummaryItem
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app this test does not need.
@Config(application = Application::class, qualifiers = "w384dp-h832dp-xxhdpi")
class WhatsNewScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val statusBarPx = 100
    private val navigationBarPx = 150

    private fun summary(versionCode: Int, title: String) = WhatsNewSummary(
        versionCode = versionCode,
        title = title,
        items = listOf(WhatsNewSummaryItem(title = "Row of $title", body = "What it does.", icon = null)),
    )

    private val twoSteps = WhatsNewFlow(
        listOf(summary(29, "What's new in 2.3.1"), summary(30, "What's new in 2.4.0")).map(WhatsNewStep::Summary),
    )

    private var dismissals = 0

    private fun open(dark: Boolean = false, skippable: Boolean = false) {
        composeRule.setContent {
            if (dark) {
                MaterialTheme(colorScheme = darkColorScheme()) { WhatsNewScreen(twoSteps, onDismiss = { dismissals++ }) }
            } else {
                WhatsNewScreen(twoSteps, onDismiss = { dismissals++ }, skippable = skippable)
            }
        }
        composeRule.waitForIdle()
        val bars = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBarPx, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigationBarPx))
            .build()
        composeRule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(dialog().window!!.decorView, bars) }
        composeRule.waitForIdle()
    }

    private fun dialog() = ShadowDialog.getLatestDialog() as ComponentDialog

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun next() {
        composeRule.onNodeWithText(string(R.string.action_next)).performClick()
        composeRule.waitForIdle()
    }

    private fun back() {
        composeRule.runOnUiThread { dialog().onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    @Test
    fun `the page reaches the top of the screen, its top row just under the status bar`() {
        open()

        // The dots are centered in the 48dp row of the back arrow and the dots: 3x density.
        val dots = composeRule.onNodeWithContentDescription("Page 1 of 2", substring = true)
        assertEquals(statusBarPx + 24 * 3f, dots.fetchSemanticsNode().boundsInWindow.center.y, 1f)
    }

    @Test
    fun `the last page's button sits above the navigation bar`() {
        open()
        next()

        val window = dialog().window!!.decorView
        val button = composeRule.onNodeWithText(string(R.string.whats_new_continue)).fetchSemanticsNode().boundsInWindow
        assertTrue("the button reaches under the navigation bar", button.bottom <= window.height - navigationBarPx)
    }

    @Test
    fun `there is no close button`() {
        open()
        next()

        composeRule.onNodeWithContentDescription(string(R.string.action_close)).assertDoesNotExist()
    }

    @Test
    fun `back on a later page goes a page back and closes nothing`() {
        open()
        next()

        back()

        composeRule.onNodeWithText("What's new in 2.3.1").assertIsDisplayed()
        assertEquals(0, dismissals)
    }

    @Test
    fun `back on the first page closes nothing`() {
        open()

        back()

        composeRule.onNodeWithText("What's new in 2.3.1").assertIsDisplayed()
        assertEquals(0, dismissals)
    }

    @Test
    fun `the last page's button closes it, once however often it is tapped`() {
        open()
        next()

        // Both before the page has slid away.
        composeRule.mainClock.autoAdvance = false
        val done = composeRule.onNodeWithText(string(R.string.whats_new_continue))
        done.performClick()
        done.performClick()
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals("closed while still on screen", 0, dismissals)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertEquals(1, dismissals)
    }

    @Test
    fun `recreated on a later page, it stays open on that page`() {
        val screen = @Composable { WhatsNewScreen(twoSteps, onDismiss = { dismissals++ }) }
        composeRule.setContent(screen)
        composeRule.waitForIdle()
        next()

        composeRule.activityRule.scenario.recreate()
        composeRule.runOnUiThread { composeRule.activity.setContent(content = screen) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("What's new in 2.4.0").assertIsDisplayed()
        assertEquals(0, dismissals)
    }

    @Test
    fun `closed and recreated before it has slid away, it still closes`() {
        val screen = @Composable { WhatsNewScreen(twoSteps, onDismiss = { dismissals++ }) }
        composeRule.setContent(screen)
        composeRule.waitForIdle()
        next()

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText(string(R.string.whats_new_continue)).performClick()
        repeat(3) { composeRule.mainClock.advanceTimeByFrame() }
        // As a change of dark mode on the way down: the new activity shows what the old one saved.
        composeRule.activityRule.scenario.recreate()
        composeRule.runOnUiThread { composeRule.activity.setContent(content = screen) }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertEquals(1, dismissals)
        composeRule.onNodeWithText("What's new in 2.4.0").assertDoesNotExist()
    }

    @Test
    fun `one opened again from Settings closes on back from a later page`() {
        open(skippable = true)
        next()

        back()

        assertEquals(1, dismissals)
    }

    @Test
    fun `the page shows through both system bars`() {
        open()

        // Android 10 to 14 give a dialog's window bars of the theme's color; 15 makes them clear.
        val window = dialog().window!!
        assertEquals(Color.TRANSPARENT, window.statusBarColor)
        assertEquals(Color.TRANSPARENT, window.navigationBarColor)
    }

    @Test
    fun `a light page gets dark status and navigation bar icons`() {
        open()

        val window = dialog().window!!
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        assertTrue(bars.isAppearanceLightStatusBars)
        assertTrue(bars.isAppearanceLightNavigationBars)
    }

    @Test
    fun `a dark page gets light status bar icons`() {
        open(dark = true)

        val window = dialog().window!!
        assertFalse(WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars)
    }
}
