package org.ntust.app.tigerduck.mail.sanitize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailReadableHtmlTest {

    @Test
    fun `relative luminance and contrast match WCAG 2 definition`() {
        val white = MailReadableHtml.RGBA(1.0, 1.0, 1.0)
        val black = MailReadableHtml.RGBA(0.0, 0.0, 0.0)
        val ratio = MailReadableHtml.contrast(white, black)
        assertEquals(21.0, ratio, 0.01)

        val same = MailReadableHtml.contrast(white, white)
        assertEquals(1.0, same, 0.001)
    }

    @Test
    fun `parse handles hex formats named colors and rgb function`() {
        val hex3 = MailReadableHtml.parse("#f00", legacy = false)
        assertNotNull(hex3)
        assertEquals(1.0, hex3!!.red, 0.001)
        assertEquals(0.0, hex3.green, 0.001)
        assertEquals(0.0, hex3.blue, 0.001)

        val hex6 = MailReadableHtml.parse("#00ff00", legacy = false)
        assertNotNull(hex6)
        assertEquals(0.0, hex6!!.red, 0.001)
        assertEquals(1.0, hex6.green, 0.001)
        assertEquals(0.0, hex6.blue, 0.001)

        val named = MailReadableHtml.parse("white", legacy = false)
        assertNotNull(named)
        assertEquals(1.0, named!!.red, 0.001)

        val rgbFunc = MailReadableHtml.parse("rgb(255, 0, 128)", legacy = false)
        assertNotNull(rgbFunc)
        assertEquals(1.0, rgbFunc!!.red, 0.001)
        assertEquals(0.0, rgbFunc.green, 0.001)
        assertEquals(128.0 / 255.0, rgbFunc.blue, 0.01)

        val transparent = MailReadableHtml.parse("transparent", legacy = false)
        assertNotNull(transparent)
        assertEquals(0.0, transparent!!.alpha, 0.001)
    }

    @Test
    fun `readable returns null when text already passes 7 to 1 contrast`() {
        val white = MailReadableHtml.RGBA(1.0, 1.0, 1.0)
        val black = MailReadableHtml.RGBA(0.0, 0.0, 0.0)
        assertNull(MailReadableHtml.readable(white, black))
        assertNull(MailReadableHtml.readable(black, white))
    }

    @Test
    fun `readable shifts dark text on dark background to meet 7 to 1 contrast`() {
        val darkText = MailReadableHtml.RGBA(0.1, 0.1, 0.1)
        val darkBg = MailReadableHtml.RGBA(0.12, 0.12, 0.12)
        val fix = MailReadableHtml.readable(darkText, darkBg)
        assertNotNull(fix)
        val newContrast = MailReadableHtml.contrast(fix!!, darkBg)
        assertTrue("New contrast ($newContrast) should be >= 7.0", newContrast >= 7.0)
    }

    @Test
    fun `readable shifts light text on light background to meet 7 to 1 contrast`() {
        val lightText = MailReadableHtml.RGBA(0.9, 0.9, 0.9)
        val lightBg = MailReadableHtml.RGBA(1.0, 1.0, 1.0)
        val fix = MailReadableHtml.readable(lightText, lightBg)
        assertNotNull(fix)
        val newContrast = MailReadableHtml.contrast(fix!!, lightBg)
        assertTrue("New contrast ($newContrast) should be >= 7.0", newContrast >= 7.0)
    }

    @Test
    fun `dark text on dark page background is adjusted to readable color`() {
        val input = """<p style="color: #222222">Dark text</p>"""
        val readable = MailReadableHtml(input)
        val adjusted = readable.adjust(
            pageBackgroundHex = "#121212",
            pageForegroundHex = "#ffffff",
            linkForegroundHex = "#007aff",
        )
        assertFalse(adjusted.contains("color: #222222"))
        val match = Regex("""color:\s*(#[0-9a-fA-F]{6})""").find(adjusted)
        assertNotNull("Should find adjusted color in output", match)
        val newColor = MailReadableHtml.parse(match!!.groupValues[1], legacy = false)
        assertNotNull(newColor)
        val bg = MailReadableHtml.parse("#121212", legacy = false)!!
        assertTrue(MailReadableHtml.contrast(newColor!!, bg) >= 7.0)
    }

    @Test
    fun `backdrop compositing respects nested colored elements`() {
        val input = """<div style="background-color: #ffffff"><p style="color: #000000">Black on white</p></div>"""
        val readable = MailReadableHtml(input)
        val adjusted = readable.adjust(
            pageBackgroundHex = "#121212",
            pageForegroundHex = "#ffffff",
            linkForegroundHex = "#007aff",
        )
        // Black on white has 21:1 contrast and should not be changed even on a dark page
        assertTrue(adjusted.contains("color: #000000"))
    }

    @Test
    fun `links on custom background are adjusted if link foreground lacks contrast`() {
        val input = """<div style="background-color: #000000"><a href="https://example.com">My Link</a></div>"""
        val readable = MailReadableHtml(input)
        // Link color #002244 has low contrast on #000000 custom backdrop
        val adjusted = readable.adjust(
            pageBackgroundHex = "#ffffff",
            pageForegroundHex = "#000000",
            linkForegroundHex = "#002244",
        )
        // Anchor should receive an adjusted color in style
        assertTrue(adjusted.contains("style="))
        assertTrue(adjusted.contains("color: #"))
    }

    @Test
    fun `font tag color attribute is adjusted`() {
        val input = """<font color="#111111">Old style text</font>"""
        val readable = MailReadableHtml(input)
        val adjusted = readable.adjust(
            pageBackgroundHex = "#121212",
            pageForegroundHex = "#ffffff",
            linkForegroundHex = "#007aff",
        )
        assertFalse(adjusted.contains("""color="#111111""""))
        assertTrue(adjusted.contains("""color="#"""))
    }

    @Test
    fun `targets preservation safety check protects href and src`() {
        val input = """<p><a href="https://example.com/test?a=1&amp;b=2">Link</a><img src="cid:abc"></p>"""
        val readable = MailReadableHtml(input)
        val adjusted = readable.adjust(
            pageBackgroundHex = "#121212",
            pageForegroundHex = "#ffffff",
            linkForegroundHex = "#007aff",
        )
        assertEquals(MailReadableHtml.targets(input), MailReadableHtml.targets(adjusted))
    }
}
