// Flings the real What's New sheet and follows its Continue button frame by
// frame. Two things in Material3's ModalBottomSheet used to make the sheet
// bounce on its own after an upward fling, lifting the page off the bottom
// of the screen:
//
// - It hands the leftover of an upward fling to an already fully open sheet,
//   whose spring then throws it past its top. KeepOpenSheetDownTest pins the
//   rule that swallows it; the "leaves the open sheet where it is" tests pin
//   that every part of the sheet hands its flings to that rule: the list,
//   short or scrolled to its end, the button row that doesn't scroll, and
//   the drag handle the sheet draws itself.
// - Its default insets pad by however much of the status bar the sheet's top
//   is under, so a bounce changed the sheet's height, which restarted the
//   bounce, forever. A sheet grabbed while it springs back is Material's to
//   drag, and can still be thrown past its top once; "comes to rest" pins
//   that it then settles.
//
// Robolectric gives the window no status bar, so openSheet hands the sheet
// one.

package org.ntust.app.tigerduck.ui.screen.whatsnew

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
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

    /** Long enough that the list scrolls. */
    private val longSummary = summary.copy(
        items = List(12) { WhatsNewSummaryItem(title = "Feature ${it + 1}", body = "What it does.", icon = null) },
    )

    private var dismissed = false

    private fun openSheet(summary: WhatsNewSummary = this.summary) {
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

    /** Where [this] ends, scrolled out of view or not. */
    private fun SemanticsNodeInteraction.bottom() =
        fetchSemanticsNode().let { it.positionInWindow.y + it.size.height }

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
        openSheet()
        val rest = continueTop()

        val tops = flingUpFrom(title())

        assertStaysPut(rest, tops)
    }

    @Test
    fun `an upward fling that scrolls a long list to its end leaves the sheet where it is`() {
        openSheet(longSummary)
        val rest = continueTop()
        val lastRow = composeRule.onNodeWithText(longSummary.items.last().title!!)
        assertTrue("the list fits without scrolling", lastRow.bottom() > rest)

        val tops = flingUpFrom(title())

        // The list ran to its end, and what was left of the fling reached
        // the sheet.
        assertTrue("the list did not scroll to its end", lastRow.bottom() <= rest)
        assertStaysPut(rest, tops)
    }

    @Test
    fun `an upward fling on the button row leaves the open sheet where it is`() {
        openSheet()
        val rest = continueTop()

        val tops = flingUpFrom(continueButton())

        assertStaysPut(rest, tops)
    }

    @Test
    fun `an upward fling on the drag handle leaves the open sheet where it is`() {
        openSheet()
        val rest = continueTop()

        val tops = flingUpFrom(dragHandle())

        assertStaysPut(rest, tops)
    }

    @Test
    fun `a sheet flung up while it springs back comes to rest`() {
        openSheet()
        val rest = continueTop()
        composeRule.mainClock.autoAdvance = false
        // A short, slow pull: not enough to close, so the sheet springs back…
        title().performTouchInput { swipeDown(startY = centerY, endY = centerY + 120f, durationMillis = 800) }
        val pulled = continueTop()
        repeat(6) { composeRule.mainClock.advanceTimeByFrame() }
        assertTrue("the sheet is not springing back", continueTop() in rest + 0.5f..pulled - 0.5f)

        // …and is flicked up on the way. Material's own drag takes that one,
        // and throws the sheet past its top once.
        val tops = flingUpFrom(continueButton(), frames = 180)

        val lastSecond = tops.takeLast(60)
        assertTrue(
            "the sheet was still moving between ${lastSecond.min()} and ${lastSecond.max()}",
            lastSecond.all { abs(it - rest) <= 0.5f },
        )
    }

    @Test
    fun `tapping the drag handle closes the sheet`() {
        openSheet()
        dragHandle().performClick()
        composeRule.waitForIdle()

        assertTrue(dismissed)
    }

    @Test
    fun `the drag handle offers TalkBack a way to close the sheet`() {
        openSheet()
        dragHandle().performSemanticsAction(SemanticsActions.Dismiss)
        composeRule.waitForIdle()

        assertTrue(dismissed)
    }

    @Test
    // A landscape phone or a split-screen half: 92% of this would put the
    // sheet's top 25px under the status bar.
    @Config(qualifiers = "w384dp-h360dp-xxhdpi")
    fun `on a short window the resting sheet stays clear of the status bar`() {
        openSheet()
        val sheetTop = dragHandle().fetchSemanticsNode().boundsInWindow.top

        assertTrue("the sheet's top is at $sheetTop, under the 100px status bar", sheetTop >= 99.5f)
    }

    @Test
    fun `a downward fling still closes the sheet`() {
        openSheet()
        title().performTouchInput { swipeDown(startY = centerY, endY = centerY + 900f, durationMillis = 60) }
        composeRule.waitForIdle()

        assertTrue(dismissed)
    }
}
