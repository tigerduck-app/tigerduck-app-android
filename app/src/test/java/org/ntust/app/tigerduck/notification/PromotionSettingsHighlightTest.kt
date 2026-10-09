// Pins the switch the chip row points at on ColorOS.
//
// There the promotion settings action opens the app's whole notification
// page, and the switch the chip needs, "Show Live Updates on Live Alerts",
// ships off among the others. The page highlights a row named by Settings'
// fragment_args_key extra, as it does for a search result, so the intent
// names that row.

package org.ntust.app.tigerduck.notification

import android.app.Application
import android.os.Build
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [Build.VERSION_CODES.BAKLAVA])
class PromotionSettingsHighlightTest {

    private fun skin(manufacturer: String) = DeviceSkin(
        sdkInt = 36,
        manufacturer = manufacturer,
        brand = manufacturer,
        oneUiVersion = null,
        hyperOsVersion = null,
        vivoOverseas = false,
    )

    @Test
    fun `ColorOS gets the Live Alerts switch highlighted`() {
        // OPPO Reno11 5G, ColorOS 16.0.5: com.oplus.notificationmanager's
        // AppNotificationSettingsFragmentNorm, PreferenceKey.SHOWN_AS_LIVE_ALERT.
        for (vendor in listOf("OPPO", "OnePlus", "realme")) {
            val intent = promotionSettingsIntent(PACKAGE, skin(vendor))
            assertEquals(vendor, "shown_as_live_alert_enable_key", intent.getStringExtra(FRAGMENT_ARG_KEY))
        }
    }

    @Test
    fun `other skins get the plain promotion page`() {
        for (vendor in listOf("Google", "samsung", "Xiaomi", "HONOR", "vivo")) {
            val intent = promotionSettingsIntent(PACKAGE, skin(vendor))
            assertFalse(vendor, intent.hasExtra(FRAGMENT_ARG_KEY))
            assertNull(vendor, skin(vendor).promotionSettingsHighlightKey)
        }
    }

    @Test
    fun `the intent still names the action and the app`() {
        val intent = promotionSettingsIntent(PACKAGE, skin("OPPO"))
        assertEquals(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS, intent.action)
        assertEquals(PACKAGE, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `the fallback to the notification page keeps the highlight`() {
        // The page ColorOS falls back to is the one the switch is on.
        val oppo = promotionFallbackIntent(PACKAGE, skin("OPPO"))
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, oppo.action)
        assertEquals(PACKAGE, oppo.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals("shown_as_live_alert_enable_key", oppo.getStringExtra(FRAGMENT_ARG_KEY))

        assertFalse(promotionFallbackIntent(PACKAGE, skin("HONOR")).hasExtra(FRAGMENT_ARG_KEY))
    }

    private companion object {
        const val PACKAGE = "org.ntust.app.tigerduck"
        const val FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    }
}
