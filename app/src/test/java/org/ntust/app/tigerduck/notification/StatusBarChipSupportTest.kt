// Pins the device table behind the status-bar chip.
//
// Getting a row wrong is invisible in a build and expensive in the field: too
// lax and a phone that can never show a chip sends its owner to a settings
// page that changes nothing, too generous and a phone whose switch is off
// never tells its owner the switch exists. Neither shows up without the
// hardware in hand, so the measured devices are written down here as cases.

package org.ntust.app.tigerduck.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusBarChipSupportTest {

    private fun skin(
        sdkInt: Int = 36,
        manufacturer: String = "Google",
        brand: String = manufacturer,
        oneUiVersion: Int? = null,
        hyperOsVersion: Int? = null,
    ) = DeviceSkin(sdkInt, manufacturer, brand, oneUiVersion, hyperOsVersion)

    // --- the original standard, still the first gate ----------------------

    @Test
    fun `below API 36 nothing can show a chip`() {
        assertEquals(StatusBarChipSupport.UNSUPPORTED, skin(sdkInt = 35).chipSupport)
    }

    @Test
    fun `below API 36 the OEM table cannot override the API level`() {
        // A Galaxy S25 on One UI 7 and an OPPO on ColorOS 15 are both API 35.
        // hasPromotableCharacteristics() does not exist there, so no vendor
        // bypass has anything to bypass to.
        assertEquals(
            StatusBarChipSupport.UNSUPPORTED,
            skin(sdkInt = 35, manufacturer = "samsung", oneUiVersion = 70000).chipSupport,
        )
        assertEquals(
            StatusBarChipSupport.UNSUPPORTED,
            skin(sdkInt = 35, manufacturer = "OPPO").chipSupport,
        )
    }

    // --- Samsung: API 36 is not enough, One UI 8.5 is the real line -------

    @Test
    fun `One UI 8 0 on API 36 has no chip at all`() {
        // Galaxy Z Flip 6, Android 16: canPostPromotedNotifications() returns
        // false and no settings page changes that. 8.5 is Android 16 QPR2,
        // 8.0 is plain Android 16 with ui_rich_ongoing still off.
        assertEquals(
            StatusBarChipSupport.UNSUPPORTED,
            skin(manufacturer = "samsung", oneUiVersion = 80000).chipSupport,
        )
    }

    @Test
    fun `One UI 7 0 that somehow reaches API 36 is still unsupported`() {
        assertEquals(
            StatusBarChipSupport.UNSUPPORTED,
            skin(manufacturer = "samsung", oneUiVersion = 70000).chipSupport,
        )
    }

    @Test
    fun `One UI 8 5 is the boundary and is supported`() {
        assertEquals(
            StatusBarChipSupport.PLATFORM_DECIDES,
            skin(manufacturer = "samsung", oneUiVersion = DeviceSkin.ONE_UI_8_5).chipSupport,
        )
    }

    @Test
    fun `One UI 9 0 keeps the platform as the authority`() {
        // A07 on Android 17: the automation bypass survives, and the
        // permission is a real, revocable one again.
        assertEquals(
            StatusBarChipSupport.PLATFORM_DECIDES,
            skin(sdkInt = 37, manufacturer = "samsung", oneUiVersion = 90000).chipSupport,
        )
    }

    @Test
    fun `an unreadable One UI version falls back to the platform answer`() {
        // SystemProperties is reached by reflection and may stop working. The
        // fallback is the behaviour that shipped before this table existed,
        // not a guess in either direction.
        assertEquals(
            StatusBarChipSupport.PLATFORM_DECIDES,
            skin(manufacturer = "samsung", oneUiVersion = null).chipSupport,
        )
    }

    // --- Oplus: the platform answer is the verdict ------------------------

    @Test
    fun `ColorOS 16 trusts the platform`() {
        // OPPO Reno 11 / ColorOS 16.0.5: the API answers false while the
        // per-app switch is off, its default, and true once it is on, and the
        // island follows it. A false is a switch the user can turn on, so the
        // row must stay red and keep its link. The Find X9 is treated the same.
        assertEquals(StatusBarChipSupport.PLATFORM_DECIDES, skin(manufacturer = "OPPO").chipSupport)
    }

    @Test
    fun `OnePlus and realme share the Oplus ROM and the verdict`() {
        for (maker in listOf("OnePlus", "realme")) {
            assertEquals(
                maker,
                StatusBarChipSupport.PLATFORM_DECIDES,
                skin(manufacturer = maker).chipSupport,
            )
        }
    }

    // --- Xiaomi: the island arrived with HyperOS 3 -----------------------

    @Test
    fun `HyperOS 3 on API 36 trusts the platform`() {
        // POCO C85, HyperOS 3.0.302 / Android 16: the island shows exactly the
        // notifications flagged PROMOTED_ONGOING, and that flag follows the
        // same permission canPostPromotedNotifications() reports.
        assertEquals(
            StatusBarChipSupport.PLATFORM_DECIDES,
            skin(manufacturer = "Xiaomi", brand = "POCO", hyperOsVersion = 3).chipSupport,
        )
    }

    @Test
    fun `HyperOS before 3 has no chip even on API 36`() {
        for (version in listOf(1, 2)) {
            assertEquals(
                "HyperOS $version",
                StatusBarChipSupport.UNSUPPORTED,
                skin(manufacturer = "Xiaomi", hyperOsVersion = version).chipSupport,
            )
        }
    }

    @Test
    fun `HyperOS 3 on Android 15 is held back by the API floor`() {
        // Some older phones got HyperOS 3 on an Android 15 base.
        assertEquals(
            StatusBarChipSupport.UNSUPPORTED,
            skin(sdkInt = 35, manufacturer = "Xiaomi", hyperOsVersion = 3).chipSupport,
        )
    }

    @Test
    fun `an unreadable HyperOS version falls back to the platform answer`() {
        assertEquals(
            StatusBarChipSupport.PLATFORM_DECIDES,
            skin(manufacturer = "Xiaomi", hyperOsVersion = null).chipSupport,
        )
    }

    @Test
    fun `Redmi and POCO brands are Xiaomi`() {
        for (brand in listOf("Redmi", "POCO")) {
            assertEquals(
                brand,
                StatusBarChipSupport.UNSUPPORTED,
                skin(manufacturer = "unknown", brand = brand, hyperOsVersion = 2).chipSupport,
            )
        }
    }

    // --- which chips need the countdown spelled out -----------------------

    @Test
    fun `the HyperOS island needs a static countdown`() {
        assertEquals(true, skin(manufacturer = "Xiaomi", hyperOsVersion = 3).chipShowsStaticText)
        assertEquals(true, skin(manufacturer = "Xiaomi", hyperOsVersion = 4).chipShowsStaticText)
    }

    @Test
    fun `the ColorOS and OriginOS islands need a static countdown`() {
        // The in-class preview, Android 16: the OPPO Reno 11 (ColorOS 16.0.5)
        // island showed the title and the vivo V60 Lite (OriginOS 6) island
        // the app name, where the countdown should have been. OnePlus and
        // realme ship the same Oplus ROM as OPPO.
        for (maker in listOf("OPPO", "OnePlus", "realme", "vivo")) {
            assertEquals(maker, true, skin(manufacturer = maker).chipShowsStaticText)
        }
    }

    @Test
    fun `chips that run the chronometer keep it`() {
        // Short critical text outranks the chronometer on AOSP chips, so
        // setting it anywhere else would freeze a clock that works. The Honor
        // X6d 5G (MagicOS 10) island ticked the preview's countdown.
        for (skin in listOf(
            skin(manufacturer = "Google"),
            skin(manufacturer = "samsung", oneUiVersion = DeviceSkin.ONE_UI_8_5),
            skin(manufacturer = "HONOR"),
        )) {
            assertEquals(skin.manufacturer, false, skin.chipShowsStaticText)
        }
    }

    @Test
    fun `no chip means no static countdown to keep fresh`() {
        assertEquals(false, skin(manufacturer = "Xiaomi", hyperOsVersion = 2).chipShowsStaticText)
        assertEquals(
            false,
            skin(sdkInt = 35, manufacturer = "Xiaomi", hyperOsVersion = 3).chipShowsStaticText,
        )
        for (maker in listOf("OPPO", "vivo")) {
            assertEquals(maker, false, skin(sdkInt = 35, manufacturer = maker).chipShowsStaticText)
        }
    }

    // --- skins that need nothing --------------------------------------------

    @Test
    fun `MagicOS and OriginOS trust the platform`() {
        // Honor X6d 5G / MagicOS 10.0.0.193 and vivo V60 Lite / OriginOS 6,
        // both Android 16: the API answers true, the OS sets
        // FLAG_PROMOTED_ONGOING, the island renders, and neither has an
        // island switch to turn off.
        for (maker in listOf("HONOR", "vivo")) {
            assertEquals(
                maker,
                StatusBarChipSupport.PLATFORM_DECIDES,
                skin(manufacturer = maker).chipSupport,
            )
        }
    }

    // --- everything else: untested means capable, not broken --------------

    @Test
    fun `a skin nobody has measured is treated as capable`() {
        // The rule this table was written to: not on the list, meets the API
        // floor, so the chip is available and the platform decides the grant.
        for (maker in listOf("HUAWEI", "motorola", "asus")) {
            assertEquals(
                maker,
                StatusBarChipSupport.PLATFORM_DECIDES,
                skin(manufacturer = maker).chipSupport,
            )
        }
    }

    @Test
    fun `stock Android defers to the platform`() {
        assertEquals(StatusBarChipSupport.PLATFORM_DECIDES, skin(manufacturer = "Google").chipSupport)
    }

    // --- matching details -------------------------------------------------

    @Test
    fun `vendor matching ignores case`() {
        assertEquals(
            StatusBarChipSupport.UNSUPPORTED,
            skin(manufacturer = "SAMSUNG", oneUiVersion = 80000).chipSupport,
        )
        assertEquals(
            StatusBarChipSupport.UNSUPPORTED,
            skin(manufacturer = "XIAOMI", hyperOsVersion = 2).chipSupport,
        )
    }

    @Test
    fun `brand alone is enough to identify the vendor`() {
        // Some builds carry the vendor in BRAND and an ODM in MANUFACTURER.
        assertEquals(
            StatusBarChipSupport.UNSUPPORTED,
            skin(manufacturer = "unknown", brand = "samsung", oneUiVersion = 80000).chipSupport,
        )
    }

    @Test
    fun `a blank manufacturer is not mistaken for a vendor`() {
        assertEquals(
            StatusBarChipSupport.PLATFORM_DECIDES,
            skin(manufacturer = "", brand = "").chipSupport,
        )
    }
}
