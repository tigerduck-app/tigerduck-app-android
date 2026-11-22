package org.ntust.app.tigerduck.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The browser's zoom and School Mail's text size: 50% to 200% in tens, as the iPhone's mail steps. */
class PageZoomTest {

    @Test
    fun `the steps are tens from 50 to 200`() {
        assertEquals(110, PageZoom.zoomIn(100))
        assertEquals(90, PageZoom.zoomOut(100))
        assertEquals(200, PageZoom.zoomIn(190))
        assertFalse(PageZoom.canZoomIn(200))
        assertFalse(PageZoom.canZoomOut(50))
        assertTrue(PageZoom.canZoomOut(60))
    }

    @Test
    fun `a size off the steps is held to the ends and moves to the next step`() {
        assertEquals(200, PageZoom.clamp(900))
        assertEquals(50, PageZoom.clamp(-5))
        assertEquals(125, PageZoom.clamp(125))
        assertEquals(130, PageZoom.zoomIn(125))
        assertEquals(120, PageZoom.zoomOut(125))
    }

    @Test
    fun `the percentage is written as the language writes one`() {
        assertEquals("110%", PageZoom.label(110, Locale.US))
        assertEquals("110%", PageZoom.label(110, Locale.TRADITIONAL_CHINESE))
        // French puts a no-break space before the sign: which one depends on the locale data.
        val french = PageZoom.label(110, Locale.FRANCE)
        assertTrue(french, french.length == 5 && french.startsWith("110") && french.endsWith("%") && french[3] != '%')
    }
}
