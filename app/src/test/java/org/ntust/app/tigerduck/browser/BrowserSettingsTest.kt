package org.ntust.app.tigerduck.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.util.JvmContext

/** Zoom steps, each site's zoom and desktop choice, and the Appearance kept. */
class BrowserSettingsTest {

    private lateinit var settings: BrowserSettings

    @Before
    fun setUp() {
        val context = JvmContext()
        context.getSharedPreferences(BrowserSettings.PREFS_FILE, 0).edit().clear().commit()
        settings = BrowserSettings(context)
    }

    @Test
    fun `zoom goes in steps of ten between fifty and two hundred`() {
        assertEquals(110, BrowserZoom.zoomIn(100))
        assertEquals(90, BrowserZoom.zoomOut(100))
        assertEquals(110, BrowserZoom.zoomIn(105))
        assertEquals(100, BrowserZoom.zoomOut(105))
        assertEquals(200, BrowserZoom.zoomIn(200))
        assertEquals(50, BrowserZoom.zoomOut(50))
        assertFalse(BrowserZoom.canZoomIn(200))
        assertFalse(BrowserZoom.canZoomOut(50))
        assertTrue(BrowserZoom.canZoomIn(190))
    }

    @Test
    fun `each site keeps its own zoom and desktop choice`() {
        settings.setZoom("moodle2.ntust.edu.tw", 130)
        settings.setDesktopSite("moodle2.ntust.edu.tw", true)

        assertEquals(130, settings.zoom("Moodle2.ntust.edu.tw"))
        assertTrue(settings.desktopSite("moodle2.ntust.edu.tw"))
        assertEquals(BrowserZoom.DEFAULT, settings.zoom("other.ntust.edu.tw"))
        assertFalse(settings.desktopSite("other.ntust.edu.tw"))
    }

    @Test
    fun `clearing the sites forgets every site's choice and keeps the appearance`() {
        settings.appearance = BrowserAppearance.DARK
        settings.setZoom("moodle2.ntust.edu.tw", 130)
        settings.setDesktopSite("moodle2.ntust.edu.tw", true)

        settings.clearSites()

        assertEquals(BrowserZoom.DEFAULT, settings.zoom("moodle2.ntust.edu.tw"))
        assertFalse(settings.desktopSite("moodle2.ntust.edu.tw"))
        assertEquals(BrowserAppearance.DARK, settings.appearance)
    }

    @Test
    fun `the appearance starts at light`() {
        assertEquals(BrowserAppearance.LIGHT, settings.appearance)
    }

    @Test
    fun `the desktop user agent drops the Android platform and Mobile`() {
        val mobile = "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_arm64 Build/BP31.250610.004; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/140.0.0.0 Mobile Safari/537.36"

        val desktop = BrowserUserAgent.desktop(mobile)

        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/140.0.0.0 Safari/537.36",
            desktop,
        )
    }
}
