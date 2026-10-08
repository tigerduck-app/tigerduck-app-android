// Flings the real What's New sheet and follows its Continue button frame by
// frame. Two things in Material3's ModalBottomSheet used to make the sheet
// bounce on its own after an upward fling, lifting the page off the bottom
// of the screen:
//
// - It hands the leftover of an upward fling to an already fully open sheet,
//   whose spring then throws it past its top. KeepOpenSheetDownTest pins the
//   rule that swallows it; the first two tests here pin that the rule is
//   wired in, for flings from the scrolling list and from the button row
//   that doesn't scroll.
// - Its default insets pad by however much of the status bar the sheet's top
//   is under, so a bounce changed the sheet's height, which restarted the
//   bounce, forever. A fling on Material's own drag handle never reaches the
//   rule and still throws the sheet past its top once; the third test pins
//   that the sheet then comes to rest.
//
// Robolectric gives the window no status bar, so setUp hands the sheet one.

package org.ntust.app.tigerduck.ui.screen.whatsnew

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.WhatsNewSummary
import org.ntust.app.tigerduck.data.model.WhatsNewSummaryItem
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import kotlin.math.abs
import androidx.compose.material3.R as M3R

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app this test does not need.
// A phone-sized screen, like the Galaxy A26 the glitch was found on.
@Config(application = Application::class, qualifiers = "w384dp-h832dp-xxhdpi")
class WhatsNewSheetFlingTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val summary = WhatsNewSummary(
        versionCode = 29,
        title = "What's new",
        items = listOf(
            WhatsNewSummaryItem(title = "Export your class table", body = "Save it as an image.", icon = null),
            WhatsNewSummaryItem(title = "A better mail experience", body = "Smoother to use.", icon = null),
        ),
    )

    private var dismissed = false

    @Before
    fun setUp() {
        composeRule.setContent {
            WhatsNewSheet(WhatsNewFlow(pages = emptyList(), summary = summary), onDismiss = { dismissed = true })
        }
        composeRule.waitForIdle()
        // The sheet draws in its own dialog window.
        val sheetWindow = ShadowDialog.getLatestDialog().window!!.decorView
        val statusBar = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 100, 0, 0))
            .build()
        composeRule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(sheetWindow, statusBar) }
        composeRule.waitForIdle()
    }

    private fun title() = composeRule.onNodeWithText(summary.title)

    private fun continueButton() =
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.whats_new_continue))

    private fun dragHandle() = composeRule.onNodeWithContentDescription(
        composeRule.activity.getString(M3R.string.m3c_bottom_sheet_drag_handle_description),
    )

    private fun continueTop() = continueButton().fetchSemanticsNode().boundsInWindow.top

    /** A quick flick upward from [node], then where the button is on each of the next [frames] frames. */
    private fun flingUpFrom(node: SemanticsNodeInteraction, frames: Int = 120): List<Float> {
        composeRule.mainClock.autoAdvance = false
        node.performTouchInput { swipeUp(startY = centerY, endY = centerY - 900f, durationMillis = 60) }
        return List(frames) {
            composeRule.mainClock.advanceTimeByFrame()
            continueTop()
        }
    }

    private fun assertStaysPut(rest: Float, tops: List<Float>) {
        val highest = tops.min()
        assertTrue("the sheet rose ${rest - highest}px past fully open", rest - highest <= 0.5f)
        assertEquals("the sheet did not settle back where it rests", rest, tops.last(), 0.5f)
    }

    @Test
    fun `an upward fling on the list leaves the open sheet where it is`() {
        val rest = continueTop()

        val tops = flingUpFrom(title())

        assertStaysPut(rest, tops)
    }

    @Test
    fun `an upward fling on the button row leaves the open sheet where it is`() {
        val rest = continueTop()

        val tops = flingUpFrom(continueButton())

        assertStaysPut(rest, tops)
    }

    @Test
    fun `a sheet flung up by its drag handle comes to rest`() {
        val rest = continueTop()

        val tops = flingUpFrom(dragHandle(), frames = 180)

        // It may overshoot once — that's Material's own spring — but within
        // three seconds it has to be still, back where it rests.
        val lastSecond = tops.takeLast(60)
        assertTrue(
            "the sheet was still moving between ${lastSecond.min()} and ${lastSecond.max()}",
            lastSecond.all { abs(it - rest) <= 0.5f },
        )
    }

    @Test
    fun `a downward fling still closes the sheet`() {
        title().performTouchInput { swipeDown(startY = centerY, endY = centerY + 900f, durationMillis = 60) }
        composeRule.waitForIdle()

        assertTrue(dismissed)
    }
}
