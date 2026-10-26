package org.ntust.app.tigerduck.browser

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The browser's bars following the page, and what the page is told. */
class BrowserColorsTest {

    private fun probe(
        body: String? = null,
        root: String? = null,
        theme: String? = null,
        scheme: String? = null,
        meta: String? = null,
        prefersScheme: Boolean = false,
    ): String {
        fun q(v: String?) = v?.let { "\"$it\"" } ?: "null"
        return """{"body":${q(body)},"root":${q(root)},"theme":${q(theme)},"scheme":${q(scheme)},"meta":${q(meta)},"prefersScheme":$prefersScheme}"""
    }

    @Test
    fun `the bars follow the page in auto, and are light until the page is known`() {
        assertTrue(BrowserColors.barsDark(BrowserAppearance.AUTO, pageDark = true))
        assertFalse(BrowserColors.barsDark(BrowserAppearance.AUTO, pageDark = false))
        // Most pages the browser opens are light, so a dark app doesn't flash dark bars first.
        assertFalse(BrowserColors.barsDark(BrowserAppearance.AUTO, pageDark = null))
    }

    @Test
    fun `the WebView's own background is as light or dark as the bars, light until the page is known`() {
        // Black under a light page flashed black on every open on a dark phone, Auto included,
        // where the page is told dark but most pages still come up light.
        assertEquals(Color.WHITE, BrowserColors.webBackground(BrowserAppearance.AUTO, pageDark = null))
        assertEquals(Color.WHITE, BrowserColors.webBackground(BrowserAppearance.AUTO, pageDark = false))
        assertEquals(Color.BLACK, BrowserColors.webBackground(BrowserAppearance.AUTO, pageDark = true))
        assertEquals(Color.WHITE, BrowserColors.webBackground(BrowserAppearance.LIGHT, pageDark = null))
        assertEquals(Color.BLACK, BrowserColors.webBackground(BrowserAppearance.DARK, pageDark = null))
    }

    @Test
    fun `light and dark set the bars whatever the page is`() {
        assertFalse(BrowserColors.barsDark(BrowserAppearance.LIGHT, pageDark = true))
        assertFalse(BrowserColors.barsDark(BrowserAppearance.LIGHT, pageDark = null))
        assertTrue(BrowserColors.barsDark(BrowserAppearance.DARK, pageDark = false))
        assertTrue(BrowserColors.barsDark(BrowserAppearance.DARK, pageDark = null))
    }

    @Test
    fun `the page is told the app's theme in auto and the pick otherwise`() {
        assertTrue(BrowserColors.pageToldDark(BrowserAppearance.AUTO, appDark = true))
        assertFalse(BrowserColors.pageToldDark(BrowserAppearance.AUTO, appDark = false))
        assertFalse(BrowserColors.pageToldDark(BrowserAppearance.LIGHT, appDark = true))
        assertTrue(BrowserColors.pageToldDark(BrowserAppearance.DARK, appDark = false))
    }

    @Test
    fun `the drawn background decides, the body's before the page's`() {
        assertTrue(BrowserColors.pageTone(probe(body = "rgb(18, 18, 18)", root = "rgb(255, 255, 255)"), toldDark = false)!!)
        assertFalse(BrowserColors.pageTone(probe(body = "rgba(0, 0, 0, 0)", root = "rgb(250, 250, 250)"), toldDark = true)!!)
    }

    @Test
    fun `with no background drawn the theme color, then the color scheme, decides`() {
        assertTrue(BrowserColors.pageTone(probe(body = "rgba(0, 0, 0, 0)", root = "rgba(0, 0, 0, 0)", theme = "#202124"), false)!!)
        assertTrue(BrowserColors.pageTone(probe(body = "rgba(0, 0, 0, 0)", scheme = "dark"), false)!!)
        assertFalse(BrowserColors.pageTone(probe(body = "rgba(0, 0, 0, 0)", scheme = "light"), true)!!)
    }

    @Test
    fun `a page that offers both schemes takes the one it was told`() {
        assertTrue(BrowserColors.pageTone(probe(scheme = "light dark"), toldDark = true)!!)
        assertFalse(BrowserColors.pageTone(probe(scheme = "light dark"), toldDark = false)!!)
    }

    @Test
    fun `a page naming nothing is the WebView's white`() {
        assertFalse(BrowserColors.pageTone(probe(body = "rgba(0, 0, 0, 0)", scheme = "normal"), toldDark = true)!!)
    }

    @Test
    fun `a light page the WebView darkens reads as dark, so the bars follow it`() {
        // Darkened as it is drawn: its own colors still read white.
        assertTrue(BrowserColors.pageTone(probe(body = "rgb(255, 255, 255)"), toldDark = true, darkened = true)!!)
        assertTrue(BrowserColors.pageTone(probe(body = "rgba(0, 0, 0, 0)", scheme = "normal"), toldDark = true, darkened = true)!!)
    }

    @Test
    fun `nothing is darkened while the page is told light, or where the WebView cannot darken`() {
        assertFalse(BrowserColors.pageTone(probe(body = "rgb(255, 255, 255)"), toldDark = false, darkened = true)!!)
        assertFalse(BrowserColors.pageTone(probe(body = "rgb(255, 255, 255)"), toldDark = true, darkened = false)!!)
    }

    @Test
    fun `a page that keeps itself light is not darkened`() {
        assertFalse(BrowserColors.pageTone(probe(body = "rgb(255, 255, 255)", meta = "only light"), toldDark = true, darkened = true)!!)
        assertFalse(BrowserColors.pageTone(probe(body = "rgb(255, 255, 255)", scheme = "light only"), toldDark = true, darkened = true)!!)
        // Light alone still lets the WebView darken it.
        assertTrue(BrowserColors.pageTone(probe(body = "rgb(255, 255, 255)", meta = "light"), toldDark = true, darkened = true)!!)
    }

    @Test
    fun `a page with its own dark style reads as its own colors`() {
        assertTrue(BrowserColors.pageTone(probe(body = "rgb(32, 32, 32)", meta = "dark light"), toldDark = true, darkened = true)!!)
        // It offers a dark scheme, so the WebView leaves it alone, light background and all.
        assertFalse(BrowserColors.pageTone(probe(body = "rgb(255, 255, 255)", meta = "dark light"), toldDark = true, darkened = true)!!)
        assertFalse(BrowserColors.pageTone(probe(body = "rgb(255, 255, 255)", scheme = "light dark"), toldDark = true, darkened = true)!!)
    }

    @Test
    fun `a page whose style asks prefers-color-scheme is left as it draws itself`() {
        // Its own style answers dark, white background and all, and the WebView leaves it alone.
        assertFalse(BrowserColors.pageTone(probe(body = "rgb(255, 255, 255)", prefersScheme = true), toldDark = true, darkened = true)!!)
        assertTrue(BrowserColors.pageTone(probe(body = "rgb(20, 20, 20)", prefersScheme = true), toldDark = true, darkened = true)!!)
    }

    @Test
    fun `the page's own color-scheme style wins over its meta`() {
        // Its style lets the WebView darken it, whatever the meta said.
        assertTrue(BrowserColors.pageTone(probe(body = "rgb(255, 255, 255)", scheme = "light", meta = "only light"), toldDark = true, darkened = true)!!)
        // With no style of its own, the meta counts.
        assertFalse(BrowserColors.pageTone(probe(body = "rgb(255, 255, 255)", scheme = "normal", meta = "only light"), toldDark = true, darkened = true)!!)
    }

    @Test
    fun `the meta is read as CSS reads it`() {
        // Any white space between the words.
        assertFalse(BrowserColors.pageTone(probe(body = "rgb(255, 255, 255)", meta = "only\nlight"), toldDark = true, darkened = true)!!)
        // A comma makes it no scheme at all, which the WebView darkens.
        assertTrue(BrowserColors.pageTone(probe(body = "rgb(255, 255, 255)", meta = "only,light"), toldDark = true, darkened = true)!!)
    }

    @Test
    fun `the probe reads the page's color-scheme meta, and a page with no root`() {
        assertTrue(BrowserColors.TONE_PROBE.contains("meta[name=\"color-scheme\"]"))
        assertTrue(BrowserColors.TONE_PROBE.contains("meta: "))
        assertTrue(BrowserColors.TONE_PROBE.contains("prefersScheme: "))
        assertTrue(BrowserColors.TONE_PROBE.contains("scheme: root ?"))
    }

    @Test
    fun `nothing to go by is unknown`() {
        assertNull(BrowserColors.pageTone(null, false))
        assertNull(BrowserColors.pageTone("null", false))
        assertNull(BrowserColors.pageTone("not json", false))
    }

    @Test
    fun `colors parse as computed styles and theme colors write them`() {
        assertEquals(BrowserColors.Rgba(18, 52, 86, 1.0), BrowserColors.parseColor("rgb(18, 52, 86)"))
        assertEquals(BrowserColors.Rgba(18, 52, 86, 0.5), BrowserColors.parseColor("rgba(18, 52, 86, 0.5)"))
        assertEquals(BrowserColors.Rgba(18, 52, 86, 0.25), BrowserColors.parseColor("rgb(18 52 86 / 25%)"))
        assertEquals(BrowserColors.Rgba(0xAA, 0xBB, 0xCC, 1.0), BrowserColors.parseColor("#abc"))
        assertEquals(BrowserColors.Rgba(0x12, 0x34, 0x56, 1.0), BrowserColors.parseColor("#123456"))
        assertEquals(0x80 / 255.0, BrowserColors.parseColor("#12345680")!!.alpha, 1e-9)
        assertNull(BrowserColors.parseColor("transparent"))
        assertNull(BrowserColors.parseColor("#12"))
    }

    @Test
    fun `dark is where white reads better than black`() {
        assertTrue(BrowserColors.parseColor("#121212")!!.isDark)
        assertTrue(BrowserColors.parseColor("#23395B")!!.isDark)
        assertFalse(BrowserColors.parseColor("#F5F6F8")!!.isDark)
        assertFalse(BrowserColors.parseColor("#FFD54F")!!.isDark)
    }

    @Test
    fun `an appearance code that is not one reads as light`() {
        assertEquals(BrowserAppearance.DARK, BrowserAppearance.fromCode("dark"))
        assertEquals(BrowserAppearance.AUTO, BrowserAppearance.fromCode("auto"))
        assertEquals(BrowserAppearance.LIGHT, BrowserAppearance.fromCode(null))
        assertEquals(BrowserAppearance.LIGHT, BrowserAppearance.fromCode("sepia"))
    }

    @Test
    fun `a malformed number in a page's color is no color, not a crash`() {
        assertNull(BrowserColors.parseColor("rgb(.,0,0)"))
        assertNull(BrowserColors.parseColor("rgb(1.2.3, 0, 0)"))
        assertNull(BrowserColors.parseColor("rgba(0, 0, 0, .)"))
        assertNull(BrowserColors.parseColor("rgba(0, 0, 0, .%)"))
        // A theme color the page wrote wrong is skipped for the scheme.
        assertTrue(BrowserColors.pageTone(probe(body = "rgba(0, 0, 0, 0)", theme = "rgb(.,0,0)", scheme = "dark"), false)!!)
    }
}
