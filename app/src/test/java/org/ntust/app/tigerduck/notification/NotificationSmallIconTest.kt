// Pins which small icon each skin posts with.
//
// The brand icon is filled duck yellow, and on most skins that fill only
// reaches surfaces meant to be coloured: HyperOS draws it in the island and
// tints the status bar copy itself. Overseas OriginOS is the exception. It
// tints a third-party status bar icon only when every pixel is grey, so the
// yellow fill sat in the status bar while every other app followed the system
// colour. Those phones post the white twin instead; everyone else keeps yellow.

package org.ntust.app.tigerduck.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.R

class NotificationSmallIconTest {

    private fun skin(
        manufacturer: String = "Google",
        brand: String = manufacturer,
        vivoOverseas: Boolean = false,
    ) = DeviceSkin(
        sdkInt = 36,
        manufacturer = manufacturer,
        brand = brand,
        oneUiVersion = null,
        hyperOsVersion = null,
        vivoOverseas = vivoOverseas,
    )

    @Test
    fun `overseas OriginOS keeps a coloured icon out of the status bar`() {
        // vivo V60 Lite, OriginOS 6, ro.vivo.product.overseas=yes: the yellow
        // fill showed as a yellow status bar icon, the white one was tinted.
        val vivo = skin(manufacturer = "vivo", vivoOverseas = true)
        assertTrue(vivo.statusBarKeepsColouredSmallIcon)
        assertEquals(R.drawable.ic_notification_white, vivo.notificationSmallIcon)
    }

    @Test
    fun `China OriginOS tints every icon and keeps the yellow one`() {
        // The domestic branch of the same SystemUI tints any app targeting
        // Lollipop or later, as AOSP does, so the island can stay yellow.
        val vivo = skin(manufacturer = "vivo", vivoOverseas = false)
        assertFalse(vivo.statusBarKeepsColouredSmallIcon)
        assertEquals(R.drawable.ic_notification, vivo.notificationSmallIcon)
    }

    @Test
    fun `the overseas flag means nothing off vivo`() {
        assertEquals(
            R.drawable.ic_notification,
            skin(manufacturer = "Xiaomi", vivoOverseas = true).notificationSmallIcon,
        )
    }

    @Test
    fun `ColorOS gets the yellow icon in a wrapper its island will not whiten`() {
        // OPPO Reno 11 (ColorOS 16): the island paints a VectorDrawable white
        // and draws anything else as it is, while the status bar shows the
        // launcher icon instead of the small one. OnePlus and realme run the
        // same Oplus ROM.
        for (manufacturer in listOf("OPPO", "OnePlus", "realme")) {
            val oplus = skin(manufacturer)
            assertTrue(manufacturer, oplus.islandWhitensVectorSmallIcon)
            assertEquals(manufacturer, R.drawable.ic_notification_wrapped, oplus.notificationSmallIcon)
        }
    }

    @Test
    fun `MagicOS posts the yellow icon as a bitmap`() {
        // Honor X6d (MagicOS 10): the island greys every small icon but a
        // bitmap, and a one-colour bitmap still gets the status bar's tint.
        // The full-colour logo kept the island coloured too, but put a
        // coloured icon in the status bar next to everyone's monochrome one.
        val honor = skin(manufacturer = "HONOR")
        assertTrue(honor.islandGreysNonBitmapSmallIcon)
        assertEquals(R.drawable.ic_notification_bitmap, honor.notificationSmallIcon)
    }

    @Test
    fun `measured skins with nothing to work around keep the plain yellow vector`() {
        // POCO C86 (HyperOS): tinted status bar, yellow island.
        // Pixel: tints both surfaces whatever the fill.
        for (manufacturer in listOf("Xiaomi", "Google", "samsung")) {
            val other = skin(manufacturer)
            assertFalse(manufacturer, other.islandWhitensVectorSmallIcon)
            assertFalse(manufacturer, other.islandGreysNonBitmapSmallIcon)
            assertEquals(manufacturer, R.drawable.ic_notification, other.notificationSmallIcon)
        }
    }
}
