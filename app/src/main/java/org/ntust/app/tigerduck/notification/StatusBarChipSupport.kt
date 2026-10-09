// Whether this device can draw the Android 16 "Live Update" status-bar chip,
// decided from the OEM skin as well as the OS version.
//
// The platform's own answer — NotificationManagerCompat.canPostPromotedNotifications()
// — says whether the chip will show, but not whether the user can change that:
//
//   OPPO Reno 11 / ColorOS 16.0.5    returns false until the user turns on a
//                                    per-app switch that ships off, then true
//   Samsung One UI 8.0 / Android 16  returns false, and no setting changes it
//
// Both are API 36, so SDK_INT cannot separate them either, yet they need
// opposite treatment. The first must show a red "tap to fix" row, because the
// switch it links to is the fix. The second cannot be fixed by the user at all
// and must not be sent to a settings page that changes nothing.
//
// Hence a small table keyed on the skin, of the skins with no chip to grant.
// Only skins someone has actually put a build on are in it; everything else
// falls through to the platform answer, which is what this file replaced and
// the right default for hardware nobody has measured — an untested OEM is
// treated as capable, not as broken.
//
// An OPPO Find X9 on ColorOS 16.0.10 was once seen rendering the chip while
// the API said false, and this table used to report every ColorOS 16 phone as
// granted on the strength of it. Nobody recorded that phone's switch, though,
// and the Reno 11 shows the API following the switch exactly, so ColorOS is
// taken as the Reno 11 measured it: a false is a switch the user can turn on.

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
     * the special access really is off — revoked, or never turned on, as
     * ColorOS ships it — and the settings deep link really will fix it.
     */
    PLATFORM_DECIDES,
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
     * `ro.mi.os.version.code` — 3 for HyperOS 3.0. Null when absent or
     * unreadable, which includes every non-Xiaomi device and MIUI.
     */
    val hyperOsVersion: Int?,
    /**
     * `ro.vivo.product.overseas` reads `yes` — the same property OriginOS's
     * own SystemUI reads (through `FtBuild.isOverSeas()`) to pick its
     * overseas code paths. False everywhere else, including China vivo.
     */
    val vivoOverseas: Boolean,
) {
    val isSamsung: Boolean get() = matches("samsung")

    /** Redmi and POCO phones report the Xiaomi manufacturer and run the same HyperOS. */
    val isXiaomi: Boolean get() = matches("xiaomi") || matches("redmi") || matches("poco")

    /**
     * OPPO, OnePlus and realme ship the same Oplus ROM, so a result measured
     * on one is the best evidence available for the other two.
     */
    val isOplus: Boolean get() = matches("oppo") || matches("oneplus") || matches("realme")

    val isVivo: Boolean get() = matches("vivo")

    val isHonor: Boolean get() = matches("honor")

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

            // HyperOS draws Live Updates in its own island rather than an AOSP
            // chip, and only from HyperOS 3 on, so an earlier HyperOS has no
            // chip whatever API level it reports.
            isXiaomi && hyperOsVersion != null && hyperOsVersion < HYPER_OS_3 ->
                StatusBarChipSupport.UNSUPPORTED

            // Samsung on 8.5+, Pixels, HyperOS 3+, ColorOS 16, MagicOS,
            // OriginOS, and every skin not in the table above. HyperOS 3 is
            // here on measurement, not by default: on a POCO C85 (HyperOS
            // 3.0.302, Android 16) the island takes exactly the notifications
            // the platform flagged PROMOTED_ONGOING, and the platform sets that
            // flag from the same POST_PROMOTED_NOTIFICATIONS check
            // canPostPromotedNotifications() reports, so the API is the truth
            // there. So it is on ColorOS 16 (OPPO Reno 11, 16.0.5), where the
            // API turns true with the per-app switch and the island with it,
            // and on MagicOS 10 (Honor X6d 5G) and OriginOS 6 (vivo V60 Lite),
            // which answer true and render with no switch at all. Anything
            // else is untested, and untested means "use the standard", not
            // "assume broken".
            else -> StatusBarChipSupport.PLATFORM_DECIDES
        }

    /**
     * Whether the chip shows a fixed string where other skins run a clock.
     *
     * HyperOS's island never reads the chronometer. It fills its right-hand
     * slot with the first non-empty of short critical text, title, subtext and
     * text, so a notification that leaves the first unset — correctly, for
     * every other skin — shows its title there instead of a countdown.
     *
     * ColorOS and OriginOS do not read it either. With the in-class preview
     * posted, the OPPO Reno 11 (ColorOS 16.0.5) island showed the title and
     * the vivo V60 Lite (OriginOS 6) island the app name, while the Honor
     * X6d 5G (MagicOS 10) ticked the countdown. Both draw short critical text
     * when it is set — IslandCheck's percentage shows on each.
     *
     * OnePlus and realme are included unmeasured, as is every other vivo:
     * OxygenOS and realme UI are built on the same Oplus ROM, and a wrong
     * guess costs far less one way than the other. Text on a chip that would
     * have run the clock still counts down, a minute at a time; no text on an
     * island that ignores the clock shows no countdown at all.
     */
    val chipShowsStaticText: Boolean
        get() = (isXiaomi || isOplus || isVivo) &&
            chipSupport != StatusBarChipSupport.UNSUPPORTED

    /**
     * Whether the Live Update's card shows the countdown in place of the
     * content text, and reads only sub text and ProgressStyle for the rest.
     *
     * ColorOS 16 draws a promoted notification as its own Live Alerts card,
     * in the shade and when the island is tapped (SystemUIPlugin,
     * `normal_card_content_section`): the title, then one slot holding either
     * the chronometer or the content text, never both, then the sub text.
     * BigTextStyle gets the standard template, so the big text never shows,
     * and the card draws a bar only for a `Notification.ProgressStyle`, not
     * for `setProgress`. On an OPPO Reno 11 (ColorOS 16.0.5) the in-class card
     * was the title and the countdown alone, where the POCO C85, Honor X6d 5G
     * and Pixel cards also showed the bar and the room, instructor and time.
     * OnePlus and realme run the same plugin.
     */
    val cardHidesTextBehindClock: Boolean
        get() = isOplus && chipSupport != StatusBarChipSupport.UNSUPPORTED

    /**
     * Whether the status bar draws a third-party small icon in its own
     * colours unless every pixel is grey, rather than tinting it.
     *
     * AOSP tints the small icon of any app targeting Lollipop or later,
     * whatever its fill. Overseas OriginOS rasterises the icon first and
     * tints it only when that bitmap passes the grayscale test or the app is
     * a system app (OriginOS 6, `StatusBarIconViewBinder.bindIconColors`), so
     * on a vivo V60 Lite the yellow brand icon stayed yellow next to every
     * other app's system-coloured one. Its island draws the same small icon,
     * untinted, so no fill can be yellow in one and system-coloured in the
     * other. The domestic branch of that SystemUI tints as AOSP does.
     */
    val statusBarKeepsColouredSmallIcon: Boolean
        get() = isVivo && vivoOverseas

    /**
     * Whether the island paints a VectorDrawable small icon white and draws
     * any other drawable as it is.
     *
     * ColorOS 16's island (SystemUIPlugin, `com.oplus.systemui.plugins`)
     * applies a white SRC_ATOP filter only when the icon is a VectorDrawable,
     * so the yellow vector came out white on an OPPO Reno 11. Nothing else
     * reaches that decision: not setColor, not colorized, not a setting. The
     * framework there also swaps a third-party app's status bar icon for its
     * launcher icon, so a coloured small icon only reaches the island and the
     * Live Update card. OnePlus and realme run the same Oplus ROM.
     */
    val islandWhitensVectorSmallIcon: Boolean
        get() = isOplus

    /**
     * Whether the island tints every small icon grey except a bitmap.
     *
     * MagicOS's island (`CapsuleViewFactory`) skips its tint only for a
     * BitmapDrawable, so the yellow vector came out grey on a Honor X6d
     * (MagicOS 10). Its status bar and shade sort the icon by its pixels
     * instead (`HnNotificationUtils.getSmallIconInfo`): a white or
     * one-colour icon is tinted in the status bar like every other app's and
     * drawn in the shade as a white glyph on a colour tile, while a
     * many-coloured one is drawn as it is in both. The full-colour logo made
     * the shade show the app icon but put a coloured icon in the status bar,
     * and the two cannot be split: both read the one tint flag that sort
     * sets. A one-colour bitmap keeps the status bar monochrome and the
     * island yellow, and leaves the shade its tile.
     */
    val islandGreysNonBitmapSmallIcon: Boolean
        get() = isHonor

    /**
     * The preference key of the chip's switch on the page the promotion
     * settings action opens, when that page is more than the one switch.
     *
     * ColorOS 16 sends the action to the app's whole notification page
     * (`com.oplus.notificationmanager`), where "Show Live Updates on Live
     * Alerts" ships off between the other switches. That page reads Settings'
     * `:settings:fragment_args_key` extra and pulses the row it names once, as
     * it does for a search result: on an OPPO Reno 11 (ColorOS 16.0.5) the row
     * lit up 0.6 s after the page opened and faded over about a second.
     * OnePlus and realme run the same page. Null elsewhere: AOSP's promotion
     * page is the one switch, and no other skin reads this key.
     */
    val promotionSettingsHighlightKey: String?
        get() = if (isOplus) COLOR_OS_LIVE_ALERT_SWITCH else null

    companion object {
        /** `PreferenceKey.SHOWN_AS_LIVE_ALERT` in ColorOS's notification manager. */
        private const val COLOR_OS_LIVE_ALERT_SWITCH = "shown_as_live_alert_enable_key"

        /** First One UI built on Android 16 QPR2, and the first that promotes anything. */
        const val ONE_UI_8_5 = 80500

        /** First HyperOS whose island shows promoted notifications. */
        const val HYPER_OS_3 = 3

        /**
         * Read once per process and shared: the inputs cannot change while the
         * app is alive, and every miss costs up to two reflective property reads.
         */
        private val cached: DeviceSkin by lazy {
            DeviceSkin(
                sdkInt = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER.orEmpty(),
                brand = Build.BRAND.orEmpty(),
                oneUiVersion = systemProperty("ro.build.version.oneui")?.toIntOrNull(),
                hyperOsVersion = systemProperty("ro.mi.os.version.code")?.toIntOrNull(),
                vivoOverseas = systemProperty("ro.vivo.product.overseas") == "yes",
            )
        }

        fun current(): DeviceSkin = cached

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
