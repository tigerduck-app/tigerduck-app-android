// Pins the device table behind the status-bar chip.
//
// Getting a row wrong is invisible in a build and expensive in the field: too
// strict and a working phone shows a permanent red permission row for a
// feature that is already running, too lax and a phone that can never show a
// chip sends its owner to a settings page that changes nothing. Neither shows
// up without the hardware in hand, so the measured devices are written down
// here as cases.

package org.ntust.app.tigerduck.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusBarChipSupportTest {

    private fun skin(
        sdkInt: Int = 36,
        manufacturer: String = "Google",
        brand: String = manufacturer,
        oneUiVersion: Int? = null,
        oplusRomMajor: Int? = null,
        hyperOsVersion: Int? = null,
    ) = DeviceSkin(sdkInt, manufacturer, brand, oneUiVersion, oplusRomMajor, hyperOsVersion)

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
            skin(sdkInt = 35, manufacturer = "OPPO", oplusRomMajor = 15).chipSupport,
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

    // --- Oplus: the platform answer is a false negative -------------------

    @Test
    fun `ColorOS 16 renders the chip while the API denies it`() {
        // OPPO Find X9 / ColorOS 16.0.10, confirmed in production before 2.1.0.
        assertEquals(
            StatusBarChipSupport.ALWAYS_ON,
            skin(manufacturer = "OPPO", oplusRomMajor = 16).chipSupport,
        )
    }

    @Test
    fun `OnePlus and realme share the Oplus ROM and the verdict`() {
        for (maker in listOf("OnePlus", "realme")) {
            assertEquals(
                maker,
                StatusBarChipSupport.ALWAYS_ON,
                skin(manufacturer = maker, oplusRomMajor = 16).chipSupport,
            )
        }
    }

    @Test
    fun `an unreadable Oplus ROM version on API 36 still trusts the device`() {
        // On an Oplus device at API 36 a false from the capability API is more
        // likely the known false negative than a permission the user revoked.
        assertEquals(
            StatusBarChipSupport.ALWAYS_ON,
            skin(manufacturer = "OPPO", oplusRomMajor = null).chipSupport,
        )
    }

    @Test
    fun `an older Oplus ROM somehow on API 36 defers to the platform`() {
        assertEquals(
            StatusBarChipSupport.PLATFORM_DECIDES,
            skin(manufacturer = "OPPO", oplusRomMajor = 15).chipSupport,
        )
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
    fun `only the HyperOS island needs a static countdown`() {
        assertEquals(true, skin(manufacturer = "Xiaomi", hyperOsVersion = 3).chipShowsStaticText)
        assertEquals(true, skin(manufacturer = "Xiaomi", hyperOsVersion = 4).chipShowsStaticText)
    }

    @Test
    fun `chips that run the chronometer keep it`() {
        // Short critical text outranks the chronometer on AOSP chips, so
        // setting it anywhere else would freeze a clock that works.
        for (skin in listOf(
            skin(manufacturer = "Google"),
            skin(manufacturer = "samsung", oneUiVersion = DeviceSkin.ONE_UI_8_5),
            skin(manufacturer = "OPPO", oplusRomMajor = 16),
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
    }

    // --- everything else: untested means capable, not broken --------------

    @Test
    fun `a skin nobody has measured is treated as capable`() {
        // The rule this table was written to: not on the list, meets the API
        // floor, so the chip is available and the platform decides the grant.
        for (maker in listOf("vivo", "HONOR", "HUAWEI", "motorola", "asus")) {
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
            StatusBarChipSupport.ALWAYS_ON,
            skin(manufacturer = "oppo", oplusRomMajor = 16).chipSupport,
        )
    }

    @Test
    fun `brand alone is enough to identify the vendor`() {
        // Some builds carry the vendor in BRAND and an ODM in MANUFACTURER.
        assertEquals(
            StatusBarChipSupport.ALWAYS_ON,
            skin(manufacturer = "unknown", brand = "OPPO", oplusRomMajor = 16).chipSupport,
        )
    }

    @Test
    fun `a blank manufacturer is not mistaken for a vendor`() {
        assertEquals(
            StatusBarChipSupport.PLATFORM_DECIDES,
            skin(manufacturer = "", brand = "").chipSupport,
        )
    }

    // --- version-string parsing -------------------------------------------

    @Test
    fun `an Oplus ROM version string yields its major`() {
        assertEquals(16, DeviceSkin.leadingMajor("V16.0.10"))
        assertEquals(15, DeviceSkin.leadingMajor("15.0.1"))
    }

    @Test
    fun `an unparseable ROM version is null rather than zero`() {
        // Zero would read as "very old ROM" and silently downgrade a device.
        assertNull(DeviceSkin.leadingMajor(null))
        assertNull(DeviceSkin.leadingMajor(""))
        assertNull(DeviceSkin.leadingMajor("unknown"))
    }
}
