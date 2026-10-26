// Drags, flings and taps a DetentSheet the way a phone reports a finger, and reads back where the
// sheet went and how it got there.

package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need. A phone's size and density.
@Config(application = Application::class, qualifiers = "en-w411dp-h891dp-420dpi")
class DetentSheetTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var state: DetentSheetState
    private lateinit var scope: CoroutineScope
    private var dismissed = 0
    private var clicks = 0
    /** How far a finger goes before a drag starts, in px: Robolectric's is 16 dp, a phone's 8. */
    private var touchSlop = 0f
    private val density get() = composeRule.density.density

    private val height get() = state.draggable.anchors.positionOf(DetentSheetValue.Hidden)
    private val offset get() = state.draggable.offset

    /** The window's height, in dp: a smaller one stands for a rotation or a split screen. */
    private var windowHeight by mutableStateOf(891.dp)

    /**
     * A sheet as the course sheet uses it, filling the window; or, with [contentHeight], one as tall
     * as its content, as the other sheets are.
     */
    private fun show(
        openAt: DetentSheetValue? = null,
        halfHeight: Boolean = true,
        contentHeight: Dp? = null,
    ) {
        composeRule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            scope = rememberCoroutineScope()
            state = rememberDetentSheetState()
            Box(Modifier.fillMaxWidth().height(windowHeight)) {
                DetentSheetLayer(
                    state = state,
                    onDismissed = { dismissed++ },
                    halfHeight = halfHeight,
                    fillHeight = contentHeight == null,
                    openAt = openAt,
                    containerColor = Color.White,
                ) {
                    val size = if (contentHeight == null) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(contentHeight)
                    Box(size.testTag(CONTENT)) {
                        Box(Modifier.size(48.dp).testTag(BUTTON).clickable { clicks++ })
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Steps the clock a frame at a time for [millis], noting the highest and lowest the sheet's top went. */
    private fun watch(millis: Long): ClosedFloatingPointRange<Float> {
        var highest = Float.MAX_VALUE
        var lowest = -Float.MAX_VALUE
        repeat((millis / 16).toInt()) {
            composeRule.mainClock.advanceTimeByFrame()
            highest = minOf(highest, offset)
            lowest = maxOf(lowest, offset)
        }
        return highest..lowest
    }

    /** A finger's drag of [dy] px over [millis] ms, in 8 ms steps; a held finger before the lift drops the speed to nothing. */
    private fun TouchInjectionScope.drag(from: Offset, dy: Float, millis: Long, holdMillis: Long = 0) {
        val steps = (millis / 8).toInt().coerceAtLeast(1)
        down(from)
        for (i in 1..steps) moveTo(Offset(from.x, from.y + dy * i / steps), delayMillis = 8)
        if (holdMillis > 0) {
            repeat((holdMillis / 8).toInt()) { moveTo(Offset(from.x, from.y + dy), delayMillis = 8) }
        }
        up()
    }

    @Test
    fun `opens at half height`() {
        show()
        assertEquals(DetentSheetValue.Half, state.currentValue)
        assertEquals(height / 2f, offset, 1f)
    }

    @Test
    fun `opens in full when asked to`() {
        show(openAt = DetentSheetValue.Full)
        assertEquals(DetentSheetValue.Full, state.currentValue)
        assertEquals(0f, offset, 1f)
    }

    @Test
    fun `a short slow drag up from half height carries on to full screen`() {
        show()
        // The sheet moves 44 dp past the touch slop, then the finger holds still: no fling, only how
        // far it went. Over this sheet's 32 dp, under Material's 56.
        composeRule.onNodeWithTag(CONTENT).performTouchInput {
            drag(from = Offset(centerX, 200f), dy = -(touchSlop + 44f * density), millis = 500, holdMillis = 200)
        }
        composeRule.waitForIdle()
        assertEquals(DetentSheetValue.Full, state.currentValue)
    }

    @Test
    fun `a shorter drag than that settles back`() {
        show()
        // 24 dp past the touch slop, under this sheet's 32.
        composeRule.onNodeWithTag(CONTENT).performTouchInput {
            drag(from = Offset(centerX, 200f), dy = -(touchSlop + 24f * density), millis = 500, holdMillis = 200)
        }
        composeRule.waitForIdle()
        assertEquals(DetentSheetValue.Half, state.currentValue)
    }

    @Test
    fun `a gentle flick up from half height carries on to full screen`() {
        show()
        // 100 dp a second: under Material's 125 dp/s, over this sheet's 80. The sheet moves 24 dp
        // past the touch slop, too short to carry on by distance alone.
        val distance = touchSlop + 24f * density
        composeRule.onNodeWithTag(CONTENT).performTouchInput {
            drag(from = Offset(centerX, 200f), dy = -distance, millis = (distance / density * 10f).toLong())
        }
        composeRule.waitForIdle()
        assertEquals(DetentSheetValue.Full, state.currentValue)
    }

    @Test
    fun `it settles more slowly than Material's sheet`() {
        show()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(CONTENT).performTouchInput {
            drag(from = Offset(centerX, 400f), dy = -120f * density, millis = 120)
        }
        // Material's spring is all but there 180 ms after the lift; this one is still well on its way.
        watch(180)
        assertTrue("arrived as quickly as Material's: $offset", offset > 0.05f * height / 2f)
        watch(1000)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(DetentSheetValue.Full, state.currentValue)
    }

    @Test
    fun `a hard flick up from half height never carries the sheet past the top`() {
        show()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(CONTENT).performTouchInput {
            drag(from = Offset(centerX, 700f), dy = -600f, millis = 48)
        }
        val highest = watch(1200).start
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertTrue("went $highest px past the top", highest >= -0.5f)
        assertEquals(DetentSheetValue.Full, state.currentValue)
    }

    @Test
    fun `a hard flick up on the handle at full screen leaves the sheet where it is`() {
        show(openAt = DetentSheetValue.Full)
        composeRule.mainClock.autoAdvance = false
        // The strip at the top of the sheet, beside the handle: Material's own drag took these.
        composeRule.onRoot().performTouchInput {
            drag(from = Offset(40f, 30f * density), dy = -25f * density, millis = 24)
        }
        val moved = watch(800)
        assertTrue("went ${moved.start} px past the top", moved.start >= -0.5f)
        assertTrue("dipped ${moved.endInclusive} px below the top", moved.endInclusive <= 0.5f)
        assertFalse("still animating, and would take the next touch", state.draggable.isAnimationRunning)
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun `a fling down at full screen with the content at its top goes to half height`() {
        show(openAt = DetentSheetValue.Full)
        composeRule.onNodeWithTag(CONTENT).performTouchInput {
            drag(from = Offset(centerX, 300f), dy = 300f, millis = 80)
        }
        composeRule.waitForIdle()
        assertEquals(DetentSheetValue.Half, state.currentValue)
        assertEquals(0, dismissed)
    }

    @Test
    fun `a fling down from half height closes the sheet`() {
        show()
        composeRule.onNodeWithTag(CONTENT).performTouchInput {
            drag(from = Offset(centerX, 100f), dy = 300f, millis = 80)
        }
        composeRule.waitForIdle()
        assertEquals(DetentSheetValue.Hidden, state.currentValue)
        assertEquals(1, dismissed)
    }

    @Test
    fun `a tap above the sheet closes it`() {
        show()
        composeRule.onRoot().performTouchInput { click(Offset(centerX, 100f)) }
        composeRule.waitForIdle()
        assertEquals(DetentSheetValue.Hidden, state.currentValue)
        assertEquals(1, dismissed)
    }

    @Test
    fun `back at full screen goes to half height, and back again closes the sheet`() {
        show(openAt = DetentSheetValue.Full)
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(DetentSheetValue.Half, state.currentValue)
        assertEquals(0, dismissed)

        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(DetentSheetValue.Hidden, state.currentValue)
        assertEquals(1, dismissed)
    }

    @Test
    fun `the handle raises a sheet at half height and closes one at full`() {
        show()
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand)).performClick()
        composeRule.waitForIdle()
        assertEquals(DetentSheetValue.Full, state.currentValue)

        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse)).performClick()
        composeRule.waitForIdle()
        assertEquals(DetentSheetValue.Hidden, state.currentValue)
        assertEquals(1, dismissed)
    }

    @Test
    fun `TalkBack can expand, collapse and dismiss from the handle`() {
        show()
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
            .performSemanticsAction(SemanticsActions.Expand)
        composeRule.waitForIdle()
        assertEquals(DetentSheetValue.Full, state.currentValue)

        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse))
            .performSemanticsAction(SemanticsActions.Collapse)
        composeRule.waitForIdle()
        assertEquals(DetentSheetValue.Half, state.currentValue)

        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss)
        composeRule.waitForIdle()
        assertEquals(DetentSheetValue.Hidden, state.currentValue)
        assertEquals(1, dismissed)
    }

    @Test
    fun `a tap outside while the sheet is still opening closes it`() {
        composeRule.mainClock.autoAdvance = false
        show()
        composeRule.mainClock.advanceTimeBy(64)
        assertTrue("already open", state.draggable.isAnimationRunning)
        composeRule.onRoot().performTouchInput { click(Offset(centerX, 20f)) }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(DetentSheetValue.Hidden, state.currentValue)
        assertEquals(1, dismissed)
    }

    @Test
    fun `a smaller window keeps the sheet at its height`() {
        show()
        // Half way down a 891 dp window lies below a 400 dp one: the old offset alone would read Hidden.
        windowHeight = 400.dp
        composeRule.waitForIdle()
        assertEquals(DetentSheetValue.Half, state.currentValue)
        assertEquals(height / 2f, offset, 1f)
        assertEquals(0, dismissed)
    }

    @Test
    fun `a window that changes size during a drag on the sheet leaves it at rest after`() {
        show(openAt = DetentSheetValue.Full)
        composeRule.onRoot().performTouchInput {
            down(Offset(40f, 30f * density))
            repeat(6) { moveBy(Offset(0f, 12f * density), delayMillis = 16) }
        }
        windowHeight = 700.dp
        composeRule.waitForIdle()
        composeRule.onRoot().performTouchInput {
            repeat(3) { moveBy(Offset(0f, 12f * density), delayMillis = 16) }
            up()
        }
        composeRule.waitForIdle()
        assertFalse("still animating, and would take the next touch", state.draggable.isAnimationRunning)
        assertEquals(state.draggable.anchors.positionOf(state.currentValue), offset, 0.5f)
    }

    @Test
    fun `a sheet as tall as its content rises to show all of it`() {
        show(contentHeight = 300.dp)
        assertEquals(DetentSheetValue.Full, state.currentValue)
        val bottom = composeRule.onNodeWithTag(CONTENT).fetchSemanticsNode().boundsInRoot.bottom
        assertEquals(height, bottom, 1f)
    }

    @Test
    fun `a hard flick up on a sheet as tall as its content leaves it where it is`() {
        // The browser's menu and the app's other sheets: Material's threw them past their top.
        show(contentHeight = 300.dp)
        val top = offset
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(CONTENT).performTouchInput {
            drag(from = Offset(centerX, bottom - 20f), dy = -200f * density, millis = 40)
        }
        val moved = watch(800)
        composeRule.mainClock.autoAdvance = true
        assertTrue("went ${top - moved.start} px past its top", moved.start >= top - 0.5f)
        assertTrue("dipped ${moved.endInclusive - top} px below its top", moved.endInclusive <= top + 0.5f)
    }

    @Test
    fun `a short sheet has no half height to stop at`() {
        show(contentHeight = 300.dp)
        assertFalse(state.hasHalf)
    }

    @Test
    fun `a sheet that can't stop half way opens in full`() {
        show(halfHeight = false, contentHeight = 800.dp)
        assertEquals(DetentSheetValue.Full, state.currentValue)
        assertFalse(state.hasHalf)
    }

    @Test
    fun `a tall sheet as tall as its content opens at half height`() {
        show(contentHeight = 800.dp)
        assertEquals(DetentSheetValue.Half, state.currentValue)
    }

    @Test
    fun `hide slides the sheet away and calls onDismissed once`() {
        show()
        composeRule.runOnIdle { scope.launch { state.hide() } }
        composeRule.waitForIdle()
        assertEquals(DetentSheetValue.Hidden, state.currentValue)
        assertFalse(state.isVisible)
        assertEquals(1, dismissed)
    }

    @Test
    fun `grabbing the sheet while it opens doesn't close it`() {
        composeRule.mainClock.autoAdvance = false
        show()
        composeRule.mainClock.advanceTimeBy(96)
        val grabbedAt = offset
        // A finger on the rising sheet, held a moment, then lifted.
        composeRule.onRoot().performTouchInput {
            down(Offset(40f, grabbedAt + 30f * density))
            moveBy(Offset(0f, -4f), delayMillis = 48)
        }
        composeRule.mainClock.advanceTimeBy(160)
        assertEquals("closed under the finger", 0, dismissed)
        composeRule.onRoot().performTouchInput { up() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(0, dismissed)
        assertTrue(state.isVisible)
    }

    @Test
    fun `back while the sheet opens closes it`() {
        composeRule.mainClock.autoAdvance = false
        show()
        composeRule.mainClock.advanceTimeBy(64)
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(DetentSheetValue.Hidden, state.currentValue)
        assertEquals(1, dismissed)
    }

    @Test
    fun `a sheet whose half height goes away while it opens to it still opens, and closes after`() {
        // 380 dp of content under a 48 dp handle: taller than half of an 800 dp window, shorter
        // than half of an 891 dp one.
        windowHeight = 800.dp
        composeRule.mainClock.autoAdvance = false
        show(contentHeight = 380.dp)
        composeRule.mainClock.advanceTimeBy(64)
        windowHeight = 891.dp
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertFalse("its offset is lost", offset.isNaN())
        assertTrue(state.isVisible)
        assertEquals(state.draggable.anchors.positionOf(DetentSheetValue.Full), offset, 0.5f)

        composeRule.runOnIdle { scope.launch { state.hide() } }
        composeRule.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun `a tap just after the sheet has come up reaches what it taps`() {
        composeRule.mainClock.autoAdvance = false
        show()
        // Up and still to the eye. A sheet still easing in by fractions of a pixel would take the
        // tap for a grab of its own.
        composeRule.mainClock.advanceTimeBy(DetentSheetMotion.DURATION_MILLIS + 50L)
        // A finger's tap, spelled out: down and up through the sheet's own touch handling.
        composeRule.onNodeWithTag(BUTTON).performTouchInput { click() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(1, clicks)
    }

    private companion object {
        const val CONTENT = "sheet-content"
        const val BUTTON = "sheet-button"
    }
}

class DetentSheetMotionTest {

    private val seconds = DetentSheetMotion.DURATION_MILLIS / 1000f

    @Test
    fun `a fling starts the curve at its own speed where it can`() {
        // 2.5 times the curve's average speed over 500 px.
        val velocity = -2.5f * 500f / seconds
        assertEquals(2.5f, DetentSheetMotion.startSlope(velocity, -500f), 0.001f)
    }

    @Test
    fun `a hard fling starts it no faster than it can go without passing its end`() {
        assertEquals(3f, DetentSheetMotion.startSlope(-20_000f, -500f), 0f)
    }

    @Test
    fun `a slow fling, or none, still eases out`() {
        assertEquals(2f, DetentSheetMotion.startSlope(-100f, -500f), 0f)
        assertEquals(2f, DetentSheetMotion.startSlope(0f, -500f), 0f)
    }

    @Test
    fun `a fling away from the height eases out toward it all the same`() {
        assertEquals(2f, DetentSheetMotion.startSlope(4_000f, -500f), 0f)
    }

    @Test
    fun `a sheet already at its height doesn't move`() {
        assertEquals(0f, DetentSheetMotion.startSlope(-4_000f, 0f), 0f)
    }

    @Test
    fun `the curve goes only forward, from the start to the end`() {
        for (slope in listOf(0f, 2f, 2.5f, 3f)) {
            var last = 0f
            for (i in 0..100) {
                val p = DetentSheetMotion.progress(i / 100f, slope)
                assertTrue("slope $slope went back at ${i / 100f}", p >= last - 1e-6f)
                assertTrue("slope $slope passed its end at ${i / 100f}", p <= 1f + 1e-6f)
                last = p
            }
            assertEquals(0f, DetentSheetMotion.progress(0f, slope), 0f)
            assertEquals(1f, DetentSheetMotion.progress(1f, slope), 1e-6f)
        }
    }
}
