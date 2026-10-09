// Pins what every notification builder starts with.
//
// The small icon each skin needs and the duck-yellow accent used to be set at
// each of eleven builders, and the mail ones had lost the accent. The large
// icon, the launcher logo, is for the notifications that come from outside the
// app, and only where the shade does not already show that logo in the small
// icon's place.

package org.ntust.app.tigerduck.notification

import android.app.Application
import android.os.Build
import androidx.core.content.ContextCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [Build.VERSION_CODES.BAKLAVA])
class BrandedNotificationTest {

    private val context: Application = RuntimeEnvironment.getApplication()

    private fun skin(manufacturer: String, sdkInt: Int = 36) = DeviceSkin(
        sdkInt = sdkInt,
        manufacturer = manufacturer,
        brand = manufacturer,
        oneUiVersion = null,
        hyperOsVersion = null,
        vivoOverseas = false,
    )

    @Test
    fun `every builder gets the skin's small icon and the brand accent`() {
        val oppo = skin("OPPO")
        val notification = context.brandedNotification("channel", skin = oppo).build()

        assertEquals(oppo.notificationSmallIcon, notification.smallIcon.resId)
        assertEquals(ContextCompat.getColor(context, R.color.duck_yellow), notification.color)
        assertNull("no large icon unless asked for", notification.getLargeIcon())
    }

    @Test
    fun `shades that show the app icon get no large icon to repeat it`() {
        // vivo V60 Lite, OPPO Reno 11, Galaxy A26 and the Pixel emulator all
        // drew the launcher icon where the small icon goes.
        for (vendor in listOf("vivo", "OPPO", "samsung", "Google")) {
            val notification = context
                .brandedNotification("channel", withLargeIcon = true, skin = skin(vendor))
                .build()
            assertNull(vendor, notification.getLargeIcon())
        }
    }

    @Test
    fun `shades that show the small icon get the logo as the large icon`() {
        // Honor X6d: a glyph on a tile. POCO C85: the small icon. moto g34,
        // ZTE P505, Zenfone 6 (Android 14 and 15): the small icon in a circle.
        for (skin in listOf(skin("HONOR"), skin("Xiaomi"), skin("motorola", sdkInt = 35))) {
            val notification = context
                .brandedNotification("channel", withLargeIcon = true, skin = skin)
                .build()
            assertNotNull(skin.manufacturer, notification.getLargeIcon())
        }
    }
}
