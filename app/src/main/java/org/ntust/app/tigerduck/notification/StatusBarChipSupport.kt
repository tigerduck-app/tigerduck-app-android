// Whether this device can draw the Android 16 "Live Update" status-bar chip,
// decided from the OEM skin as well as the OS version.
//
// The platform's own answer — NotificationManagerCompat.canPostPromotedNotifications()
// — is wrong in both directions on shipping hardware:
//
//   OPPO Find X9 / ColorOS 16.0.10   returns false, and renders the chip anyway
//   Samsung One UI 8.0 / Android 16  returns false, and the chip is genuinely absent
//
// Both are API 36, so SDK_INT cannot separate them either, yet they need
// opposite treatment. The first is a working device that must not show a red
// "tap to fix" row for a permission that is not the problem. The second cannot
// be fixed by the user at all and must not be sent to a settings page that
// changes nothing.
//
// Hence a small table keyed on the skin. Only skins someone has actually put a
// build on are in it; everything else falls through to the platform answer,
// which is what this file replaced and the right default for hardware nobody
// has measured — an untested OEM is treated as capable, not as broken.

package org.ntust.app.tigerduck.notification

import android.os.Build

/** What to believe about the status-bar chip on a given device. */
enum class StatusBarChipSupport {
    /**
     * No chip surface at all, and nothing the user can do about it. The
     * permission row paints grey and stops being a link.
     */
    UNSUPPORTED,

    /**
     * `canPostPromotedNotifications()` is trustworthy here, so a `false` means
     * the user really has revoked the special access and the settings deep
     * link really will fix it.
     */
    PLATFORM_DECIDES,

    /**
     * The chip renders even though the platform API reports `false`. Treated
     * as permanently granted: there is no permission to ask for, and no
     * settings page that would change the outcome.
     */
    ALWAYS_ON,
}

/**
 * The device facts [chipSupport] reads, captured once so the decision itself
 * stays a pure function over data and can be tested on the JVM. Nothing here
 * changes while the process is alive.
 */
data class DeviceSkin(
    val sdkInt: Int,
    val manufacturer: String,
    val brand: String,
    /**
     * `ro.build.version.oneui` as Samsung encodes it — 70000 for One UI 7.0,
     * 80500 for 8.5, 90000 for 9.0. Null when absent or unreadable, which
     * includes every non-Samsung device.
     */
    val oneUiVersion: Int?,
    /**
     * Major version of the Oplus ROM shared by ColorOS, OxygenOS and realme
     * UI — 16 for ColorOS 16.0.10. Null when absent or unreadable.
     */
    val oplusRomMajor: Int?,
) {
    val isSamsung: Boolean get() = matches("samsung")

    /**
     * OPPO, OnePlus and realme ship the same Oplus ROM, so a result measured
     * on one is the best evidence available for the other two.
     */
    val isOplus: Boolean get() = matches("oppo") || matches("oneplus") || matches("realme")

    private fun matches(vendor: String) =
        manufacturer.equals(vendor, ignoreCase = true) || brand.equals(vendor, ignoreCase = true)

    val chipSupport: StatusBarChipSupport
        get() = when {
            // The original standard, and still the first gate: promoted
            // ongoing notifications do not exist as an API below Android 16.
            sdkInt < Build.VERSION_CODES.BAKLAVA -> StatusBarChipSupport.UNSUPPORTED

            // One UI 8.5 is Android 16 QPR2; 8.0 is plain Android 16, where the
            // platform still gates Live Updates behind its internal
            // `ui_rich_ongoing` flag and promotes nothing whatever the app
            // sends. Every Galaxy that stops at 8.0 — S22 series, Z Fold 4,
            // Z Flip 4, some Z Flip 6 units — is in this bucket for good.
            isSamsung && oneUiVersion != null && oneUiVersion < ONE_UI_8_5 ->
                StatusBarChipSupport.UNSUPPORTED

            // Measured on an OPPO Find X9 running ColorOS 16.0.10: a plain
            // AOSP promoted notification renders, with no vendor-specific code
            // and no allowlist, while the capability API says false. An
            // unreadable ROM version still lands here, because on an Oplus
            // device at API 36 the false negative is the more likely reading of
            // a `false` than a revoked permission.
            isOplus && (oplusRomMajor == null || oplusRomMajor >= COLOR_OS_16) ->
                StatusBarChipSupport.ALWAYS_ON

            // Samsung on 8.5+, Pixels, and every skin not in the table above:
            // HyperOS, Funtouch OS and MagicOS are all untested, and untested
            // means "use the standard", not "assume broken".
            else -> StatusBarChipSupport.PLATFORM_DECIDES
        }

    companion object {
        /** First One UI built on Android 16 QPR2, and the first that promotes anything. */
        const val ONE_UI_8_5 = 80500

        /** The ColorOS generation shipped on Android 16. */
        const val COLOR_OS_16 = 16

        /**
         * Read once per process and shared: the inputs cannot change while the
         * app is alive, and every miss costs three reflective property reads.
         */
        private val cached: DeviceSkin by lazy {
            DeviceSkin(
                sdkInt = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER.orEmpty(),
                brand = Build.BRAND.orEmpty(),
                oneUiVersion = systemProperty("ro.build.version.oneui")?.toIntOrNull(),
                oplusRomMajor = leadingMajor(
                    systemProperty("ro.build.version.oplusrom")
                        ?: systemProperty("ro.build.version.opporom")
                ),
            )
        }

        fun current(): DeviceSkin = cached

        private val LEADING_DIGITS = Regex("\\d+")

        /**
         * First run of digits in an OEM version string — "V16.0.10" is 16.
         * Returns null rather than 0 for anything unparseable, so a bad read
         * is never mistaken for an old ROM.
         */
        internal fun leadingMajor(version: String?): Int? =
            version?.let { LEADING_DIGITS.find(it)?.value?.toIntOrNull() }

        /**
         * `android.os.SystemProperties` is hidden but greylisted, so reflection
         * works on every release this app runs on. Every failure path — a
         * blocklist change, a stripped class, a missing key — returns null, and
         * null falls through to [StatusBarChipSupport.PLATFORM_DECIDES]. There
         * is no public API for a skin version, and no way to tell One UI 8.0
         * from 8.5 without one.
         */
        private fun systemProperty(key: String): String? = try {
            @Suppress("PrivateApi")
            val get = Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
            (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }
}
