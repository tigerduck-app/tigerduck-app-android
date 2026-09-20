package org.ntust.app.tigerduck.ui.component

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollChromeTest {

    private fun bar(height: Float = 200f) = AppBarState().apply { heightPx = height }

    @Test
    fun `scrolling up hides the bar`() {
        val state = bar()
        state.onScroll(-120f)
        assertEquals(-120f, state.offsetPx, 0.01f)
    }

    @Test
    fun `the bar never hides past its own height`() {
        val state = bar()
        state.onScroll(-500f)
        assertEquals(-200f, state.offsetPx, 0.01f)
    }

    @Test
    fun `any downward scroll brings it back, not only at the top`() {
        val state = bar()
        state.onScroll(-200f)
        state.onScroll(40f)
        assertEquals(-160f, state.offsetPx, 0.01f)
    }

    @Test
    fun `the bar never shows past its resting position`() {
        val state = bar()
        state.onScroll(300f)
        assertEquals(0f, state.offsetPx, 0.01f)
    }

    @Test
    fun `the bar consumes only what it used`() {
        val state = bar()
        assertEquals(-200f, state.onScroll(-500f), 0.01f)
        assertEquals(0f, state.onScroll(-50f), 0.01f)
    }

    @Test
    fun `a zero-height bar consumes nothing`() {
        assertEquals(0f, AppBarState().onScroll(-50f), 0.01f)
    }

    private fun reveal() = SearchRevealState(initialMaxPx = 56f)

    @Test
    fun `overscroll opens the search drawer first`() {
        val state = reveal()
        assertEquals(30f, state.consume(30f), 0.01f)
        assertEquals(30f, state.revealPx, 0.01f)
    }

    @Test
    fun `the drawer stops at its maximum and leaves the rest for refresh`() {
        val state = reveal()
        assertEquals(56f, state.consume(200f), 0.01f)
        assertEquals(56f, state.revealPx, 0.01f)
        assertEquals(0f, state.consume(100f), 0.01f)
    }

    @Test
    fun `scrolling up drains the drawer before the list moves`() {
        val state = reveal()
        state.consume(56f)
        assertEquals(-20f, state.consume(-20f), 0.01f)
        assertEquals(36f, state.revealPx, 0.01f)
    }

    @Test
    fun `a pinned drawer stays open`() {
        val state = reveal()
        state.consume(56f)
        state.pinned = true
        assertEquals(0f, state.consume(-56f), 0.01f)
        assertEquals(56f, state.revealPx, 0.01f)
    }

    @Test
    fun `a closed drawer consumes no upward scroll`() {
        assertEquals(0f, reveal().consume(-40f), 0.01f)
    }

    // --- the chrome changing height under a bar that is already part way up ---

    @Test
    fun `a fully hidden bar stays fully hidden when the chrome grows`() {
        val state = bar()
        state.onScroll(-500f)
        state.heightPx = 260f
        assertEquals(-260f, state.offsetPx, 0.01f)
    }

    @Test
    fun `a fully hidden bar stays fully hidden when the chrome shrinks`() {
        val state = bar()
        state.onScroll(-500f)
        state.heightPx = 140f
        assertEquals(-140f, state.offsetPx, 0.01f)
    }

    @Test
    fun `a partly hidden bar is re-clamped into a shorter chrome`() {
        val state = bar()
        state.onScroll(-160f)
        state.heightPx = 100f
        assertEquals(-100f, state.offsetPx, 0.01f)
    }

    @Test
    fun `a partly hidden bar keeps its offset when the chrome grows`() {
        val state = bar()
        state.onScroll(-160f)
        state.heightPx = 260f
        assertEquals(-160f, state.offsetPx, 0.01f)
    }

    @Test
    fun `the first measurement leaves a resting bar alone`() {
        val state = AppBarState()
        state.heightPx = 200f
        assertEquals(0f, state.offsetPx, 0.01f)
    }

    // --- where a lifted finger leaves things ---

    @Test
    fun `a bar more than half hidden settles hidden`() {
        assertEquals(-200f, appBarSettleTarget(-120f, 200f), 0.01f)
        assertEquals(-200f, appBarSettleTarget(-100f, 200f), 0.01f)
    }

    @Test
    fun `a bar less than half hidden settles back open`() {
        assertEquals(0f, appBarSettleTarget(-99f, 200f), 0.01f)
        assertEquals(0f, appBarSettleTarget(-1f, 200f), 0.01f)
    }

    @Test
    fun `an unmeasured bar has nowhere to settle`() {
        assertEquals(-50f, appBarSettleTarget(-50f, 0f), 0.01f)
    }

    @Test
    fun `a drawer past half way settles open`() {
        assertEquals(56f, searchDrawerSettleTarget(56f, 56f, pinned = false), 0.01f)
        assertEquals(56f, searchDrawerSettleTarget(28f, 56f, pinned = false), 0.01f)
    }

    @Test
    fun `a drawer short of half way settles shut`() {
        assertEquals(0f, searchDrawerSettleTarget(27f, 56f, pinned = false), 0.01f)
        assertEquals(0f, searchDrawerSettleTarget(1f, 56f, pinned = false), 0.01f)
    }

    @Test
    fun `a pinned drawer settles where it already is`() {
        assertEquals(20f, searchDrawerSettleTarget(20f, 56f, pinned = true), 0.01f)
    }

    @Test
    fun `an unmeasured drawer has nowhere to settle`() {
        assertEquals(10f, searchDrawerSettleTarget(10f, 0f, pinned = false), 0.01f)
    }

    /**
     * The animation itself needs a frame clock and a device; what is pinned here is that anything
     * already at its resting place returns without asking for one, which is what makes the settle
     * effect free on every composition that is not following a gesture.
     */
    @Test
    fun `settling something already at rest needs no frame clock`() = runBlocking {
        withTimeout(1_000) {
            bar().settle()
            reveal().settle()
        }
    }

    // --- pinning, which has to open the drawer and not merely freeze it ---

    @Test
    fun `pinning opens the drawer, so a field can never be focused invisibly`() {
        val state = reveal()
        state.pinned = true
        assertEquals(56f, state.revealPx, 0.01f)
    }

    // No test for the setter's `wasPinned` guard -- the one that stops every recomposition's
    // `pinned = true` from re-running the open -- because it buys nothing observable anywhere.
    // Nothing can move revealPx while pinned, so the repeated write lands on the same value, and
    // a float state's setter is equality-guarded, so writing that same value notifies nobody and
    // costs no recomposition either. The guard says what the setter means; it does not change
    // what happens, and there is nothing here for a test to hold on to.

    @Test
    fun `unpinning leaves the drawer open for the next scroll to close`() {
        val state = reveal()
        state.pinned = true
        state.pinned = false
        assertEquals(56f, state.revealPx, 0.01f)
        assertEquals(-16f, state.consume(-16f), 0.01f)
    }

    // --- the drawer measuring itself, rather than trusting a constant ---

    @Test
    fun `an unmeasured drawer opens to nothing`() {
        assertEquals(0f, SearchRevealState().consume(40f), 0.01f)
    }

    @Test
    fun `the drawer opens to the measured height of the field`() {
        val state = SearchRevealState()
        state.maxPx = 64f
        assertEquals(64f, state.consume(200f), 0.01f)
    }

    @Test
    fun `a shrinking measurement clamps the reveal`() {
        val state = reveal()
        state.consume(56f)
        state.maxPx = 40f
        assertEquals(40f, state.revealPx, 0.01f)
    }

    @Test
    fun `a drawer pinned before it is measured opens as soon as it is`() {
        // The real order on both screens: `pinned` is assigned during composition, and the field
        // is measured in the layout pass that follows.
        val state = SearchRevealState()
        state.pinned = true
        assertEquals(0f, state.revealPx, 0.01f)
        state.maxPx = 64f
        assertEquals(64f, state.revealPx, 0.01f)
    }

    @Test
    fun `snapToRest puts a hidden bar straight back, for a list that jumped to the top`() =
        runBlocking {
            val state = AppBarState().apply { heightPx = 200f }
            state.onScroll(-200f)
            assertEquals(-200f, state.offsetPx, 0.01f)
            // scrollToItem dispatches no delta, so nothing else would ever move this back.
            state.snapToRest()
            assertEquals(0f, state.offsetPx, 0.01f)
        }

    @Test
    fun `snapToRest on a bar already at rest is a no-op`() = runBlocking {
        val state = AppBarState().apply { heightPx = 200f }
        state.snapToRest()
        assertEquals(0f, state.offsetPx, 0.01f)
    }
}
