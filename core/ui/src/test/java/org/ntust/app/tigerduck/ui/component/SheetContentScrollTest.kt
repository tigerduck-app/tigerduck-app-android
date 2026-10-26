// Drags and flings the content of a DetentSheet, as the class table's course sheet holds it, and
// reads back where the content and the sheet ended up.

package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class, qualifiers = "en-w360dp-h640dp")
class SheetContentScrollTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var sheet: DetentSheetState
    private lateinit var scroll: ScrollState
    private var dismissed = false
    private lateinit var scope: CoroutineScope

    /** A course sheet pulled up to full, its content about one and a half screens tall. */
    private fun showExpandedSheet() {
        composeRule.setContent {
            sheet = rememberDetentSheetState()
            scope = rememberCoroutineScope()
            scroll = rememberScrollState()
            val sheetScroll = remember(scroll) { SheetContentScroll(scroll) }
            DetentSheetLayer(
                state = sheet,
                onDismissed = { dismissed = true },
                halfHeight = true,
                fillHeight = true,
                openAt = DetentSheetValue.Full,
                containerColor = Color.White,
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag(CONTENT)
                        .then(sheetScroll.modifier)
                        .verticalScroll(scroll),
                ) {
                    Column { Spacer(Modifier.fillMaxWidth().height(900.dp)) }
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals("the sheet did not open in full", DetentSheetValue.Full, sheet.currentValue)
        assertTrue("the content is too short to scroll", scroll.maxValue > 0)
    }

    private fun scrollToBottom() {
        composeRule.runOnIdle { scope.launch { scroll.scrollTo(scroll.maxValue) } }
        composeRule.waitForIdle()
    }

    private fun swipeContentDown(durationMillis: Long) {
        composeRule.onNodeWithTag(CONTENT, useUnmergedTree = true).performTouchInput {
            swipeDown(startY = top + 40f, endY = bottom - 40f, durationMillis = durationMillis)
        }
        composeRule.waitForIdle()
    }

    /**
     * A swipe as a phone reports a finger: from a standstill, in steps 4 ms apart, the first few
     * of them inside the touch slop. The test API's swipes cross the slop in their first step.
     */
    private fun TouchInjectionScope.fingerSwipe(from: Offset, to: Offset) {
        val steps = 20
        down(from)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            moveTo(lerp(from, to, t * t), delayMillis = 4)
        }
        up()
    }

    @Test
    fun `a hard fling down from the bottom scrolls the content and leaves the sheet up`() {
        showExpandedSheet()
        scrollToBottom()

        swipeContentDown(durationMillis = 50)

        assertTrue("the content did not scroll", scroll.value < scroll.maxValue)
        assertEquals("the fling moved the sheet", DetentSheetValue.Full, sheet.currentValue)
        assertFalse("the fling closed the sheet", dismissed)
    }

    @Test
    fun `a hard fling down from the bottom ends once the content reaches its top`() {
        showExpandedSheet()
        scrollToBottom()
        composeRule.mainClock.autoAdvance = false

        composeRule.onNodeWithTag(CONTENT, useUnmergedTree = true).performTouchInput {
            swipeDown(startY = top + 40f, endY = bottom - 40f, durationMillis = 50)
        }
        composeRule.mainClock.advanceTimeBy(600)

        // A fling that ran on past the top would still be scrolling, unseen, and take the next tap.
        assertEquals("the fling did not reach the top", 0, scroll.value)
        assertFalse("the fling ran on past the top", scroll.isScrollInProgress)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(DetentSheetValue.Full, sheet.currentValue)
    }

    @Test
    fun `a fling down right after a fling up hits the bottom scrolls the content and leaves the sheet up`() {
        showExpandedSheet()
        scrollToBottom()
        composeRule.mainClock.autoAdvance = false

        composeRule.onNodeWithTag(CONTENT, useUnmergedTree = true).performTouchInput {
            swipeUp(startY = bottom - 40f, endY = top + 40f, durationMillis = 50)
        }
        // A quick hand comes straight back down.
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.onNodeWithTag(CONTENT, useUnmergedTree = true).performTouchInput {
            fingerSwipe(from = Offset(centerX, top + 40f), to = Offset(centerX, bottom - 40f))
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertTrue("the content did not scroll", scroll.value < scroll.maxValue)
        assertEquals("the fling moved the sheet", DetentSheetValue.Full, sheet.currentValue)
        assertFalse("the fling closed the sheet", dismissed)
    }

    @Test
    fun `a slow drag down from the bottom stops at the top without moving the sheet`() {
        showExpandedSheet()
        scrollToBottom()

        swipeContentDown(durationMillis = 1500)

        assertEquals("the content did not reach its top", 0, scroll.value)
        assertEquals("the drag moved the sheet", DetentSheetValue.Full, sheet.currentValue)
        assertFalse(dismissed)
    }

    @Test
    fun `once the content is at its top, the next fling down pulls the sheet down`() {
        showExpandedSheet()
        scrollToBottom()
        swipeContentDown(durationMillis = 50)
        composeRule.runOnIdle { scope.launch { scroll.scrollTo(0) } }
        composeRule.waitForIdle()

        swipeContentDown(durationMillis = 50)

        assertNotEquals("a fling at the top left the sheet up", DetentSheetValue.Full, sheet.currentValue)
    }

    @Test
    fun `a drag down that starts at the top pulls the sheet down`() {
        showExpandedSheet()

        swipeContentDown(durationMillis = 1500)

        assertNotEquals("a drag at the top left the sheet up", DetentSheetValue.Full, sheet.currentValue)
    }

    @Test
    fun `a fling up from the top still scrolls the content`() {
        showExpandedSheet()

        composeRule.onNodeWithTag(CONTENT, useUnmergedTree = true).performTouchInput {
            swipeUp(startY = bottom - 40f, endY = top + 40f, durationMillis = 200)
        }
        composeRule.waitForIdle()

        assertTrue("the content did not scroll up", scroll.value > 0)
        assertEquals(DetentSheetValue.Full, sheet.currentValue)
    }

    private companion object {
        const val CONTENT = "sheet-content"
    }
}
