package org.ntust.app.tigerduck.data.preferences

import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import org.ntust.app.tigerduck.ui.theme.contrastRatio

/**
 * Which accent each surface draws, from the preferences behind the accent
 * color page: [AppPreferences.accentColorHex], the swatch last picked or the
 * start of the gradient picked, [AppPreferences.accentGradientEndHex], that
 * gradient's end, and [AppPreferences.useWallpaperColors], whether the
 * wallpaper's colors are picked in their place.
 *
 * Only the fills the app's own screens paint draw a gradient (see
 * [org.ntust.app.tigerduck.ui.theme.TigerDuckTheme.accentBrush]), and the
 * watch's marks in the accent; everything else draws its start, as a single
 * color.
 *
 * Only the app's own screens follow the wallpaper, and only from Android 12,
 * the first with wallpaper colors to give. Everything else is handed the
 * fixed accent: the widgets and the watch draw it, as do the app's screens on
 * Android 11 and earlier, and the Live Update carries it, though its
 * notification draws the brand color instead. Picking the wallpaper leaves the
 * fixed accent as it was, so those keep the color they had, and so does an
 * older build, which reads only `accentColorHex`.
 */
object AccentPolicy {

    /** The accent of a fresh install: Blue. */
    const val DEFAULT_LIGHT_HEX = 0x007AFF

    /**
     * The fixed accent a stored `accentColorHex` stands for, as its light
     * palette hex. A value that is no RGB hex reads as [DEFAULT_LIGHT_HEX]:
     * above all -1, which a build of the unshipped first take on the
     * wallpaper accent stored there, and which would draw as white.
     */
    fun fixedLightHex(stored: Int): Int =
        if (stored in 0..0xFFFFFF) stored else DEFAULT_LIGHT_HEX

    /**
     * The fixed accent [lightHex] as [isDark]'s theme draws it: its dark pair
     * in dark mode, and in light mode itself, or for a color of the user's own
     * too light for the light cards a tone dark enough to stand out on them.
     */
    fun fixedColor(lightHex: Int, isDark: Boolean): Color {
        val hex = if (isDark) darkHex(lightHex) else lightModeHex(lightHex)
        return Color(0xFF000000L or (hex.toLong() and 0xFFFFFFL))
    }

    /** What `accentGradientEndHex` holds while the pick is a single color. */
    const val NO_GRADIENT = -1

    /**
     * The gradients the accent page offers ready-made, as their start and end
     * colors' light palette hexes, each a pair of the preset colors.
     */
    val presetGradients: List<Pair<Int, Int>> = listOf(
        0x007AFF to 0xAF52DE, // Blue to Purple
        0xAF52DE to 0xFF2D55, // Purple to Pink
        0xFF2D55 to 0xFF9500, // Pink to Orange
        0x34C759 to 0x5AC8FA, // Green to Cyan
        0x5AC8FA to 0x007AFF, // Cyan to Blue
        0x5856D6 to 0x5AC8FA, // Indigo to Cyan
    )

    /**
     * The end color of the gradient picked, or null when the pick is a single
     * color. A gradient counts only while the fixed accent is still the start
     * it was picked with ([storedStart]).
     */
    fun gradientEnd(fixedLightHex: Int, storedStart: Int, storedEnd: Int): Int? =
        storedEnd.takeIf { it in 0..0xFFFFFF && storedStart == fixedLightHex }

    /** Whether [lightHex] is one of the preset colors, not one of the user's own. */
    fun isPreset(lightHex: Int): Boolean = AppPreferences.themeColors.any { it.second == lightHex }

    /**
     * [lightHex] as dark mode draws it: a preset's own dark pair, and a color
     * of the user's own, which has none, lightened just enough to stand out on
     * the dark cards, as a control must.
     */
    fun darkHex(lightHex: Int): Int {
        if (isPreset(lightHex)) return AppPreferences.accentDarkVariant(lightHex)
        val light = rgb(lightHex)
        // Toward white in steps of a twentieth: white stands out on the cards
        // at over 10:1, so the walk always ends.
        for (step in 0..20) {
            val tone = lerp(light, Color.White, step / 20f)
            if (contrast(tone, DARK_CARD) >= MIN_CONTRAST) return tone.toArgb() and 0xFFFFFF
        }
        return 0xFFFFFF
    }

    /**
     * [lightHex] as light mode draws it: a preset as it is, and a color of the
     * user's own darkened just enough to stand out on the light cards, so
     * white picked as one's own does not leave every link and switch
     * invisible. The mirror of [darkHex].
     */
    private fun lightModeHex(lightHex: Int): Int {
        if (isPreset(lightHex)) return lightHex
        val light = rgb(lightHex)
        // Toward black in steps of a twentieth: black stands out on the cards
        // at over 15:1, so the walk always ends.
        for (step in 0..20) {
            val tone = lerp(light, Color.Black, step / 20f)
            if (contrast(tone, LIGHT_CARD) >= MIN_CONTRAST) return tone.toArgb() and 0xFFFFFF
        }
        return 0x000000
    }

    /**
     * What text and icons on the accent [lightHex] draw in, in [isDark]'s
     * theme: null for a preset, which keeps the theme's white, and for a
     * color of the user's own whichever of white and the dark text color
     * reads better on the tone that mode draws.
     */
    fun onAccent(lightHex: Int, isDark: Boolean): Color? {
        if (isPreset(lightHex)) return null
        val drawn = fixedColor(lightHex, isDark)
        return if (contrast(Color.White, drawn) >= contrast(DARK_TEXT, drawn)) Color.White else DARK_TEXT
    }

    /** The dark theme's cards, which an accent in dark mode is drawn on. */
    private val DARK_CARD = Color(0xFF3A3A3C)

    /** The light theme's cards, which an accent in light mode is drawn on. */
    private val LIGHT_CARD = Color(0xFFF2F2F7)

    /** The light theme's text color, for text on a light accent. */
    private val DARK_TEXT = Color(0xFF1C1C1E)

    /** WCAG's least contrast for a control or a graphic, which the accent is. */
    private const val MIN_CONTRAST = 3f

    private fun rgb(hex: Int) = Color(0xFF000000L or (hex.toLong() and 0xFFFFFFL))

    /** WCAG's contrast ratio between [a] and [b], from 1 to 21: the theme's, which its gradients use too. */
    internal fun contrast(a: Color, b: Color): Float = contrastRatio(a, b)

    /**
     * Whether the app's own screens draw the wallpaper's colors on Android
     * [sdkInt]: where the user picked them and the system has them.
     */
    fun appFollowsWallpaper(useWallpaperColors: Boolean, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        useWallpaperColors && sdkInt >= Build.VERSION_CODES.S
}
