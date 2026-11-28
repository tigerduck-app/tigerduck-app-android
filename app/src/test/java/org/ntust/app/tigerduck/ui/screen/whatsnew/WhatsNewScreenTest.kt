// What's New over the whole screen: its top row sits just under the status bar, a ✕ there closes
// the whole flow from any page, as Back does, and the status bar's icons stay readable on the page.
//
// Robolectric gives the window no status bar, so open hands the page's window one.

package org.ntust.app.tigerduck.ui.screen.whatsnew

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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

    private fun summary(versionCode: Int, title: String) = WhatsNewSummary(
        versionCode = versionCode,
        title = title,
        items = listOf(WhatsNewSummaryItem(title = "Row of $title", body = "What it does.", icon = null)),
    )

    private val twoSteps = WhatsNewFlow(
        listOf(summary(29, "What's new in 2.3.1"), summary(30, "What's new in 2.4.0")).map(WhatsNewStep::Summary),
    )

    private var dismissals = 0

    private fun open(dark: Boolean = false) {
        composeRule.setContent {
            if (dark) {
                MaterialTheme(colorScheme = darkColorScheme()) { WhatsNewScreen(twoSteps, onDismiss = { dismissals++ }) }
            } else {
                WhatsNewScreen(twoSteps, onDismiss = { dismissals++ })
            }
        }
        composeRule.waitForIdle()
        val statusBar = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBarPx, 0, 0))
            .build()
        composeRule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(dialog().window!!.decorView, statusBar) }
        composeRule.waitForIdle()
    }

    private fun dialog() = ShadowDialog.getLatestDialog() as ComponentDialog

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun closeButton() = composeRule.onNodeWithContentDescription(string(R.string.action_close))

    private fun next() {
        composeRule.onNodeWithText(string(R.string.action_next)).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `the page reaches the top of the screen, its top row just under the status bar`() {
        open()

        // The 48dp row of the back arrow, the dots and the ✕, centered in it: 3x density.
        val closeCenter = closeButton().fetchSemanticsNode().boundsInWindow.center.y
        assertEquals(statusBarPx + 24 * 3f, closeCenter, 1f)
    }

    @Test
    fun `the close button closes the whole flow from a later page`() {
        open()
        next()

        closeButton().performClick()
        composeRule.waitForIdle()

        assertEquals(1, dismissals)
    }

    @Test
    fun `back closes the whole flow from a later page`() {
        open()
        next()

        composeRule.runOnUiThread { dialog().onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        assertEquals(1, dismissals)
    }

    @Test
    fun `tapping Continue and the close button together closes it once`() {
        open()
        next()

        // Both before the page has slid away.
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText(string(R.string.whats_new_continue)).performClick()
        closeButton().performClick()
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertEquals(1, dismissals)
    }

    @Test
    fun `a light page gets dark status bar icons`() {
        open()

        val window = dialog().window!!
        assertTrue(WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars)
    }

    @Test
    fun `a dark page gets light status bar icons`() {
        open(dark = true)

        val window = dialog().window!!
        assertFalse(WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars)
    }
}
