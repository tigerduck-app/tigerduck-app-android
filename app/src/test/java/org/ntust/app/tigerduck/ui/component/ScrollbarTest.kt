package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    ) : LazyListLayoutInfo {
        override val viewportStartOffset: Int = -beforeContentPadding
        override val viewportEndOffset: Int = viewport - beforeContentPadding
        override val viewportSize: IntSize = IntSize(1080, viewport)
        override val orientation: Orientation = Orientation.Vertical
        override val reverseLayout: Boolean = false
        override val mainAxisItemSpacing: Int = 0
    }

    /** Rows of [row] px from [firstIndex], the first scrolled [scrolledIntoFirst] px off the top. */
    private fun rows(firstIndex: Int, count: Int, row: Int, scrolledIntoFirst: Int = 0) =
        List(count) { Item(firstIndex + it, it * row - scrolledIntoFirst, row) }

    @Test
    fun `a list that fits shows no thumb`() {
        val info = Layout(rows(0, 5, 100), totalItemsCount = 5, viewport = 1000)
        assertNull(lazyListScrollbarThumb(info, canScrollBackward = false, canScrollForward = false))
    }

    @Test
    fun `the thumb is as tall as the share of the list on screen`() {
        // 100 rows of 100 px, 1000 px on screen: a tenth of the list.
        val info = Layout(rows(0, 10, 100), totalItemsCount = 100, viewport = 1000)
        val thumb = lazyListScrollbarThumb(info, canScrollBackward = false, canScrollForward = true)!!
        assertEquals(0.1f, thumb.size, 0.001f)
        assertEquals(0f, thumb.offset, 0.001f)
    }

    @Test
    fun `half way down the list the thumb is half way down its track`() {
        // Scrolled 4500 of the 9000 px there is to scroll.
        val info = Layout(rows(45, 10, 100), totalItemsCount = 100, viewport = 1000)
        val thumb = lazyListScrollbarThumb(info, canScrollBackward = true, canScrollForward = true)!!
        assertEquals(0.5f, thumb.offset, 0.001f)
    }

    @Test
    fun `part of a row scrolled off counts`() {
        val info = Layout(rows(45, 11, 100, scrolledIntoFirst = 90), totalItemsCount = 100, viewport = 1000)
        val thumb = lazyListScrollbarThumb(info, canScrollBackward = true, canScrollForward = true)!!
        assertEquals(4590f / 9000f, thumb.offset, 0.001f)
    }

    @Test
    fun `the ends come from the list, not the estimate`() {
        // Rows of uneven height make the average wrong; the thumb still meets both ends exactly.
        val uneven = listOf(Item(97, 0, 300), Item(98, 300, 300), Item(99, 600, 400))
        val atEnd = lazyListScrollbarThumb(
            Layout(uneven, totalItemsCount = 100, viewport = 1000),
            canScrollBackward = true,
            canScrollForward = false,
        )!!
        assertEquals(1f, atEnd.offset, 0.001f)

        val atTop = lazyListScrollbarThumb(
            Layout(rows(0, 10, 100), totalItemsCount = 100, viewport = 1000),
            canScrollBackward = false,
            canScrollForward = true,
        )!!
        assertEquals(0f, atTop.offset, 0.001f)
    }

    @Test
    fun `content padding counts toward the list's length`() {
        // The chrome overlay's room at the top is list the thumb has to travel past too.
        val info = Layout(rows(0, 8, 100), totalItemsCount = 100, viewport = 1000, beforeContentPadding = 200)
        val thumb = lazyListScrollbarThumb(info, canScrollBackward = false, canScrollForward = true)!!
        assertEquals(1000f / 10200f, thumb.size, 0.001f)
    }

    @Test
    fun `an empty list shows no thumb`() {
        val info = Layout(emptyList(), totalItemsCount = 0, viewport = 1000)
        assertNull(lazyListScrollbarThumb(info, canScrollBackward = false, canScrollForward = true))
    }
}
