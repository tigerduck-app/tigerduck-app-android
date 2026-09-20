package org.ntust.app.tigerduck.ui.component

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

    private fun reveal() = SearchRevealState(maxPx = 56f)

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
}
