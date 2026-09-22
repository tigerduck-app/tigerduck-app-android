package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Long-pressing the scrollbar and dragging, on a real `LazyColumn`. The case that matters is the
 * one a person actually produces: the thumb only shows while the list moves, so the press usually
 * lands while a fling is still coasting.
 */
@RunWith(AndroidJUnit4::class)
class ScrollbarFastScrollTest {

    @get:Rule
    val rule = createComposeRule()

    private fun showList(): LazyListState {
        lateinit var state: LazyListState
        rule.setContent {
            state = rememberLazyListState()
            LazyColumn(
                state = state,
                modifier = Modifier.height(400.dp).fillMaxWidth().testTag("list").scrollbar(state),
            ) {
                items(200) { Box(Modifier.fillMaxWidth().height(50.dp)) }
            }
        }
        return state
    }

    /** Long-presses the trailing edge half way down, then drags to the bottom of the track. */
    private fun grabAndDragToBottom() {
        val longPress = rule.onNodeWithTag("list").fetchLongPressTimeout()
        rule.onNodeWithTag("list").performTouchInput { down(Offset(width - 20f, height / 2f)) }
        rule.mainClock.advanceTimeBy(longPress + 100)
        rule.onNodeWithTag("list").performTouchInput { moveTo(Offset(width - 20f, height - 1f)) }
        rule.mainClock.advanceTimeBy(100)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.fetchLongPressTimeout(): Long {
        var timeout = 0L
        performTouchInput { timeout = viewConfiguration.longPressTimeoutMillis }
        return timeout
    }

    @Test
    fun aGrabWhileTheListIsStillCoastingDragsIt() {
        val state = showList()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("list").performTouchInput { swipeUp(durationMillis = 80) }
        rule.mainClock.advanceTimeBy(100)
        rule.runOnIdle { assertTrue("the fling should still be coasting", state.isScrollInProgress) }

        grabAndDragToBottom()

        rule.runOnIdle {
            assertTrue("dragged to the bottom, at ${state.firstVisibleItemIndex}", state.firstVisibleItemIndex > 180)
        }
        rule.onNodeWithTag("list").performTouchInput { up() }
    }

    @Test
    fun aHoldThatRollsALittleStillTakesHold() {
        // A pad pressed against the edge rolls as it settles: one and a half slops, sideways and down.
        val state = showList()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("list").performTouchInput {
            down(center)
            moveBy(Offset(0f, -300f), delayMillis = 100)
            advanceEventTime(300)
            moveBy(Offset.Zero)
            up()
        }
        rule.mainClock.advanceTimeBy(100)
        var longPress = 0L
        var slop = 0f
        rule.onNodeWithTag("list").performTouchInput {
            longPress = viewConfiguration.longPressTimeoutMillis
            slop = viewConfiguration.touchSlop
            down(Offset(width - 20f, height / 2f))
        }
        rule.mainClock.advanceTimeBy(longPress / 2)
        rule.onNodeWithTag("list").performTouchInput { moveBy(Offset(-slop, slop) * (1.5f / 1.414f)) }
        rule.mainClock.advanceTimeBy(longPress / 2 + 100)
        rule.onNodeWithTag("list").performTouchInput { moveTo(Offset(width - 20f, height - 1f)) }
        rule.mainClock.advanceTimeBy(100)

        rule.runOnIdle {
            assertTrue("dragged to the bottom, at ${state.firstVisibleItemIndex}", state.firstVisibleItemIndex > 180)
        }
        rule.onNodeWithTag("list").performTouchInput { up() }
    }

    @Test
    fun aGrabAfterTheListHasStoppedDragsItToo() {
        val state = showList()
        rule.mainClock.autoAdvance = false
        // A drag that comes to a stop before it lifts, so nothing is left to coast.
        rule.onNodeWithTag("list").performTouchInput {
            down(center)
            moveBy(Offset(0f, -300f), delayMillis = 100)
            advanceEventTime(300)
            moveBy(Offset.Zero)
            up()
        }
        // Still inside the half second the thumb holds before it starts to fade.
        rule.mainClock.advanceTimeBy(100)
        rule.runOnIdle { assertTrue("the list should have stopped", !state.isScrollInProgress) }

        grabAndDragToBottom()

        rule.runOnIdle {
            assertTrue("dragged to the bottom, at ${state.firstVisibleItemIndex}", state.firstVisibleItemIndex > 180)
        }
        rule.onNodeWithTag("list").performTouchInput { up() }
    }
}
