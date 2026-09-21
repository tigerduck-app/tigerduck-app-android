package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollbarTest {

    private data class Item(
        override val index: Int,
        override val offset: Int,
        override val size: Int,
    ) : LazyListItemInfo {
        override val key: Any get() = index
        override val contentType: Any? get() = null
    }

    private class Layout(
        override val visibleItemsInfo: List<LazyListItemInfo>,
        override val totalItemsCount: Int,
        viewport: Int,
        override val beforeContentPadding: Int = 0,
        override val afterContentPadding: Int = 0,
        override val mainAxisItemSpacing: Int = 0,
    ) : LazyListLayoutInfo {
        override val viewportStartOffset: Int = -beforeContentPadding
        override val viewportEndOffset: Int = viewport - beforeContentPadding
        override val viewportSize: IntSize = IntSize(1080, viewport)
        override val orientation: Orientation = Orientation.Vertical
        override val reverseLayout: Boolean = false
    }

    /** Rows of [row] px from [firstIndex], the first scrolled [scrolledIntoFirst] px off the top. */
    private fun rows(firstIndex: Int, count: Int, row: Int, scrolledIntoFirst: Int = 0) =
        List(count) { Item(firstIndex + it, it * row - scrolledIntoFirst, row) }

    private val hundred = { _: Int -> 100f }

    // --- the thumb -------------------------------------------------------------------------------

    @Test
    fun `a list that fits shows no thumb`() {
        val info = Layout(rows(0, 5, 100), totalItemsCount = 5, viewport = 1000)
        assertNull(lazyListScrollbarThumb(info, canScrollBackward = false, canScrollForward = false, sizeOf = hundred))
    }

    @Test
    fun `the thumb is as tall as the share of the list on screen`() {
        // 100 rows of 100 px, 1000 px on screen: a tenth of the list.
        val info = Layout(rows(0, 10, 100), totalItemsCount = 100, viewport = 1000)
        val thumb = lazyListScrollbarThumb(info, canScrollBackward = false, canScrollForward = true, sizeOf = hundred)!!
        assertEquals(0.1f, thumb.size, 0.001f)
        assertEquals(0f, thumb.offset, 0.001f)
    }

    @Test
    fun `half way down the list the thumb is half way down its track`() {
        // Scrolled 4500 of the 9000 px there is to scroll.
        val info = Layout(rows(45, 10, 100), totalItemsCount = 100, viewport = 1000)
        val thumb = lazyListScrollbarThumb(info, canScrollBackward = true, canScrollForward = true, sizeOf = hundred)!!
        assertEquals(0.5f, thumb.offset, 0.001f)
    }

    @Test
    fun `part of a row scrolled off counts`() {
        val info = Layout(rows(45, 11, 100, scrolledIntoFirst = 90), totalItemsCount = 100, viewport = 1000)
        val thumb = lazyListScrollbarThumb(info, canScrollBackward = true, canScrollForward = true, sizeOf = hundred)!!
        assertEquals(4590f / 9000f, thumb.offset, 0.001f)
    }

    @Test
    fun `a short header over one tall body is placed exactly once both are known`() {
        // An open message: a 200 px header and a 5000 px body, 1000 px on screen, 2000 px into the
        // body. Averaging the two rows would make the body 2600 px; knowing them does not.
        val heights = mapOf(0 to 200f, 1 to 5000f)
        val info = Layout(listOf(Item(1, -1800, 5000)), totalItemsCount = 2, viewport = 1000)
        val thumb = lazyListScrollbarThumb(info, canScrollBackward = true, canScrollForward = true) { heights.getValue(it) }!!
        assertEquals(2000f / 4200f, thumb.offset, 0.001f)
        assertEquals(1000f / 5200f, thumb.size, 0.001f)
    }

    @Test
    fun `the ends come from the list, not the estimate`() {
        // A wrong guess for the rows above still puts the thumb at the very bottom when the list is.
        val uneven = listOf(Item(97, 0, 300), Item(98, 300, 300), Item(99, 600, 400))
        val atEnd = lazyListScrollbarThumb(
            Layout(uneven, totalItemsCount = 100, viewport = 1000),
            canScrollBackward = true,
            canScrollForward = false,
        ) { 333f }!!
        assertEquals(1f, atEnd.offset, 0.001f)

        val atTop = lazyListScrollbarThumb(
            Layout(rows(0, 10, 100), totalItemsCount = 100, viewport = 1000),
            canScrollBackward = false,
            canScrollForward = true,
            sizeOf = hundred,
        )!!
        assertEquals(0f, atTop.offset, 0.001f)
    }

    @Test
    fun `content padding counts toward the list's length`() {
        // The chrome overlay's room at the top is list the thumb has to travel past too.
        val info = Layout(rows(0, 8, 100), totalItemsCount = 100, viewport = 1000, beforeContentPadding = 200)
        val thumb = lazyListScrollbarThumb(info, canScrollBackward = false, canScrollForward = true, sizeOf = hundred)!!
        assertEquals(1000f / 10200f, thumb.size, 0.001f)
    }

    @Test
    fun `an empty list shows no thumb`() {
        val info = Layout(emptyList(), totalItemsCount = 0, viewport = 1000)
        assertNull(lazyListScrollbarThumb(info, canScrollBackward = false, canScrollForward = true, sizeOf = hundred))
    }

    // --- remembered heights ----------------------------------------------------------------------

    @Test
    fun `rows keep the height they were laid out at, spacing included`() {
        val sizes = ItemSizes()
        sizes.record(Layout(listOf(Item(0, 0, 200), Item(1, 210, 5000)), totalItemsCount = 2, viewport = 1000, mainAxisItemSpacing = 10))
        // Scrolled on: row 0 is off screen now, and still remembered.
        sizes.record(Layout(listOf(Item(1, -1800, 5000)), totalItemsCount = 2, viewport = 1000, mainAxisItemSpacing = 10))
        assertEquals(210f, sizes.of(0))
        assertEquals(5010f, sizes.of(1))
        assertEquals(2610f, sizes.average()!!, 0.01f)
    }

    @Test
    fun `a list that changes length forgets what it measured`() {
        // Indices name other rows once a page is appended or a filter applied.
        val sizes = ItemSizes()
        sizes.record(Layout(rows(0, 3, 100), totalItemsCount = 50, viewport = 1000))
        sizes.record(Layout(rows(0, 2, 300), totalItemsCount = 80, viewport = 1000))
        assertEquals(300f, sizes.of(0))
        assertNull(sizes.of(2))
    }

    @Test
    fun `nothing laid out yet has no average to offer`() {
        assertNull(ItemSizes().average())
    }

    // --- fast scroll -----------------------------------------------------------------------------

    @Test
    fun `the thumb centres under the finger and stops at both ends`() {
        // A 1000 px track from 200 down, with a 100 px thumb: 900 px of travel.
        assertEquals(0f, fastScrollFraction(y = 250f, trackTop = 200f, track = 1000f, thumbHeight = 100f), 0.001f)
        assertEquals(0.5f, fastScrollFraction(y = 700f, trackTop = 200f, track = 1000f, thumbHeight = 100f), 0.001f)
        assertEquals(1f, fastScrollFraction(y = 1150f, trackTop = 200f, track = 1000f, thumbHeight = 100f), 0.001f)
        // Above the track and below it: clamped rather than scrolling past either end.
        assertEquals(0f, fastScrollFraction(y = 0f, trackTop = 200f, track = 1000f, thumbHeight = 100f), 0.001f)
        assertEquals(1f, fastScrollFraction(y = 5000f, trackTop = 200f, track = 1000f, thumbHeight = 100f), 0.001f)
    }

    @Test
    fun `a thumb that fills its track has nowhere to go`() {
        assertEquals(0f, fastScrollFraction(y = 500f, trackTop = 0f, track = 400f, thumbHeight = 400f), 0.001f)
    }

    @Test
    fun `half way down the track lands half way down the list`() {
        // 100 rows of 100 px, 1000 on screen: 9000 px to scroll, so the middle is 4500 -- row 45.
        val info = Layout(rows(0, 10, 100), totalItemsCount = 100, viewport = 1000)
        assertEquals(45 to 0, fastScrollTarget(0.5f, info, hundred))
        assertEquals(0 to 0, fastScrollTarget(0f, info, hundred))
    }

    @Test
    fun `a target part way into a row keeps the remainder as its offset`() {
        val info = Layout(rows(0, 10, 100), totalItemsCount = 100, viewport = 1000)
        assertEquals(45 to 90, fastScrollTarget(4590f / 9000f, info, hundred))
    }

    @Test
    fun `fast scrolling a message lands inside its body`() {
        // Half of the 4200 px there is to scroll is 2100: past the 200 px header, 1900 into the body.
        val heights = mapOf(0 to 200f, 1 to 5000f)
        val info = Layout(listOf(Item(0, 0, 200), Item(1, 200, 5000)), totalItemsCount = 2, viewport = 1000)
        assertEquals(1 to 1900, fastScrollTarget(0.5f, info) { heights.getValue(it) })
    }

    @Test
    fun `the bottom of the track asks for the last row outright`() {
        // The list clamps that to its true end, wherever the estimate put it.
        val info = Layout(rows(0, 10, 100), totalItemsCount = 100, viewport = 1000)
        assertEquals(99 to 0, fastScrollTarget(1f, info, hundred))
    }

    @Test
    fun `fast scrolling an empty list goes nowhere`() {
        val info = Layout(emptyList(), totalItemsCount = 0, viewport = 1000)
        assertEquals(0 to 0, fastScrollTarget(0.7f, info, hundred))
    }

    @Test
    fun `only the trailing edge takes hold, on whichever side that is`() {
        assertTrue(isOnTrailingEdge(x = 1070f, width = 1080f, edge = 60f, direction = LayoutDirection.Ltr))
        assertFalse(isOnTrailingEdge(x = 1000f, width = 1080f, edge = 60f, direction = LayoutDirection.Ltr))
        assertFalse(isOnTrailingEdge(x = 10f, width = 1080f, edge = 60f, direction = LayoutDirection.Ltr))
        assertTrue(isOnTrailingEdge(x = 10f, width = 1080f, edge = 60f, direction = LayoutDirection.Rtl))
        assertFalse(isOnTrailingEdge(x = 1070f, width = 1080f, edge = 60f, direction = LayoutDirection.Rtl))
    }
}
