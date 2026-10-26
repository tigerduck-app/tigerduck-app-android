package org.ntust.app.tigerduck.ui.theme

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.LocaleList
import android.text.TextPaint
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Raises text so CJK glyphs sit in the middle of their line, as Latin ones do.
 *
 * The system's CJK font leaves more room above its glyphs than below them. A
 * line that holds CJK text is as tall as that font, whatever the style's line
 * height (recent Android never makes a line shorter than its fonts), so the
 * glyphs land about 1 dp low: Settings' rows, buttons and every other piece of
 * Chinese text sit a little below the icons and switches beside them.
 *
 * A [BaselineShift] raises every line's glyphs, a fraction of the font's own
 * ascent, so it holds at any size a style is later given. It lifts a
 * Latin-only line too, by about half as much, since that line's own height
 * shares the shift out: the raise is the one that leaves the larger of the two
 * the smallest. It's measured on the device, per style, since phones ship
 * different CJK fonts; where it wouldn't help, the style is left as it is.
 */
internal object CjkCentering {

    /** What the measurements need from a laid-out line, in px. */
    data class Line(val baseline: Float, val height: Float) {
        /** The baseline from the middle of the line, where a row centers it. */
        val fromMiddle: Float get() = baseline - height / 2f
    }

    /**
     * Lays [text] out on one line, as Text would, in [style] raised by [shift]
     * ([raised]). The device's own is a [TextMeasurer]; tests give a model of one.
     */
    fun interface LineLayout {
        fun line(text: String, style: TextStyle, shift: Float): Line
    }

    /** The glyph metrics the measurements need, in px, for a style's size and weight. */
    interface Glyphs {
        /** The primary font's ascent, positive, which a [BaselineShift] is a fraction of. */
        fun ascent(style: TextStyle): Float

        /** The middle of [text]'s ink, from the baseline: negative is above it. */
        fun inkMiddle(text: String, style: TextStyle): Float

        /** Whether a font on the device draws [text]. */
        fun draws(text: String, style: TextStyle): Boolean
    }

    /** A style's [BaselineShift]. */
    data class Correction(val shift: Float)

    const val CJK_SAMPLE = "中文字"
    const val LATIN_SAMPLE = "Hxg"

    /**
     * How far to raise [style]'s glyphs so [CJK_SAMPLE]'s ink sits in the
     * middle of its line, with Latin-only lines kept as near where they were;
     * null to leave the style as it is.
     */
    fun correction(style: TextStyle, layout: LineLayout, glyphs: Glyphs): Correction? {
        // No font draws it (or, under Robolectric's default graphics, nothing is measured for real).
        if (!glyphs.draws(CJK_SAMPLE, style)) return null
        val low = cjkOffset(style, 0f, layout, glyphs)
        val most = low.roundToInt()
        if (most <= 0) return null
        val ascent = glyphs.ascent(style)
        if (ascent <= 0f) return null
        // Where a Latin line's glyphs sit, from the middle of the line: rows center their text.
        val latinBefore = layout.line(LATIN_SAMPLE, style, 0f).fromMiddle

        var best: Correction? = null
        var bestOff = abs(low)
        // One px past the offset too: a line that grows with the shift lets glyphs rise less.
        for (raise in (most + 1) downTo 1) {
            // ceil(-ascent * shift) is the shift in whole px Compose applies: `raise` px up.
            val shift = (raise + 0.5f) / ascent
            val latinMoved = layout.line(LATIN_SAMPLE, style, shift).fromMiddle + shiftPx(ascent, shift) - latinBefore
            // Where the device lays CJK lines out some other way, the shift may not help.
            val cjkLow = cjkOffset(style, shift, layout, glyphs)
            val off = maxOf(abs(cjkLow), abs(latinMoved))
            if (off < bestOff) {
                best = Correction(shift)
                bestOff = off
            }
            // Latin barely moved: a smaller raise would only leave CJK lower.
            if (abs(latinMoved) <= 0.5f) break
        }
        return best
    }

    /** [style], corrected; see [correction]. */
    fun centered(style: TextStyle, layout: LineLayout, glyphs: Glyphs): TextStyle =
        correction(style, layout, glyphs)?.let { style.raised(it.shift) } ?: style

    /** How far [CJK_SAMPLE]'s ink sits below the middle of its line, in px. */
    fun cjkOffset(style: TextStyle, shift: Float, layout: LineLayout, glyphs: Glyphs): Float {
        val line = layout.line(CJK_SAMPLE, style, shift)
        return line.fromMiddle + shiftPx(glyphs.ascent(style), shift) + glyphs.inkMiddle(CJK_SAMPLE, style)
    }

    /** The px a [BaselineShift] of [shift] moves glyphs by, as Compose rounds it: negative is up. */
    fun shiftPx(ascent: Float, shift: Float): Float = ceil(-ascent * shift)

    /** [this] with its glyphs raised by [shift]. */
    fun TextStyle.raised(shift: Float): TextStyle = copy(baselineShift = BaselineShift(shift))

    /** The styles of a [Typography], in the order [corrections] lists them. */
    private val Typography.styles: List<TextStyle>
        get() = listOf(
            displayLarge, displayMedium, displaySmall,
            headlineLarge, headlineMedium, headlineSmall,
            titleLarge, titleMedium, titleSmall,
            bodyLarge, bodyMedium, bodySmall,
            labelLarge, labelMedium, labelSmall,
        )

    /** A [correction] for each style of [typography]. */
    fun corrections(typography: Typography, layout: LineLayout, glyphs: Glyphs): List<Correction?> =
        typography.styles.map { correction(it, layout, glyphs) }

    /** [typography] with [corrections], from [corrections], applied. */
    fun applied(typography: Typography, corrections: List<Correction?>): Typography {
        val styles = typography.styles.zip(corrections) { style, c -> c?.let { style.raised(it.shift) } ?: style }
        return typography.copy(
            displayLarge = styles[0], displayMedium = styles[1], displaySmall = styles[2],
            headlineLarge = styles[3], headlineMedium = styles[4], headlineSmall = styles[5],
            titleLarge = styles[6], titleMedium = styles[7], titleSmall = styles[8],
            bodyLarge = styles[9], bodyMedium = styles[10], bodySmall = styles[11],
            labelLarge = styles[12], labelMedium = styles[13], labelSmall = styles[14],
        )
    }

    /** [corrections] as they're kept: the shift per style, `-` for none, `;` between. */
    fun encode(corrections: List<Correction?>): String =
        corrections.joinToString(";") { c -> c?.shift?.toString() ?: "-" }

    /** What [encode] wrote, or null when it isn't [count] styles' worth. */
    fun decode(kept: String, count: Int = 15): List<Correction?>? {
        val parts = kept.split(";")
        if (parts.size != count) return null
        return parts.map { part ->
            if (part == "-") return@map null
            val shift = part.toFloatOrNull()?.takeIf { it > 0f && it < 1f } ?: return null
            Correction(shift)
        }
    }
}

/** The device's lines, laid out by Compose itself. */
private class MeasuredLines(private val measurer: TextMeasurer) : CjkCentering.LineLayout {
    override fun line(text: String, style: TextStyle, shift: Float): CjkCentering.Line {
        val result = with(CjkCentering) { measurer.measure(text, style.raised(shift)) }
        return CjkCentering.Line(result.firstBaseline, result.size.height.toFloat())
    }
}

/**
 * The device's glyphs, through a paint with the typeface Compose resolves for
 * the style: through [fonts], so a weight the system adjusts ("Bold text") is
 * the one measured.
 */
private class PaintGlyphs(
    private val density: Density,
    private val locales: LocaleList,
    private val fonts: FontFamily.Resolver,
) : CjkCentering.Glyphs {
    private val bounds = Rect()

    private fun paint(style: TextStyle): TextPaint {
        // Text's own default, where a style has no size.
        val size = with(density) { (if (style.fontSize.isSpecified) style.fontSize else 14.sp).toPx() }
        val weight = style.fontWeight ?: FontWeight.Normal
        val resolved = fonts.resolve(style.fontFamily ?: FontFamily.Default, weight, style.fontStyle ?: FontStyle.Normal).value
        return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = resolved as? Typeface ?: Typeface.create(Typeface.DEFAULT, weight.weight, false)
            textLocales = locales
        }
    }

    override fun ascent(style: TextStyle): Float = -paint(style).ascent()

    override fun inkMiddle(text: String, style: TextStyle): Float {
        paint(style).getTextBounds(text, 0, text.length, bounds)
        return (bounds.top + bounds.bottom) / 2f
    }

    // Per character: for a longer string, hasGlyph asks for a single ligature glyph.
    override fun draws(text: String, style: TextStyle): Boolean {
        val paint = paint(style)
        return text.codePoints().allMatch { paint.hasGlyph(String(Character.toChars(it))) }
    }
}

/**
 * The last measurement, kept across launches so a cold start draws centered
 * text from its first frame. Measured again when the app, the system (and so
 * its fonts), the density, the font scale, the font weight adjustment or the
 * languages change.
 */
private object KeptCorrections {
    const val FILE = "cjk_centering"

    fun key(versionCode: Long, density: Density, fontWeightAdjustment: Int, locales: LocaleList) = listOf(
        versionCode, Build.FINGERPRINT, density.density, density.fontScale,
        fontWeightAdjustment, locales.toLanguageTags(),
    ).joinToString("|")

    /** [versionCode] once looked up; it cannot change while the process lives. */
    @Volatile
    private var installedVersionCode: Long? = null

    /**
     * The app's versionCode, from its installed package: a library's BuildConfig
     * has none. The number the app's own BuildConfig.VERSION_CODE holds, so the
     * key a launch before this module existed kept still matches. Looked up once,
     * as the app starts ([preloadKeptCjkCorrections]), so the first frame finds it.
     */
    fun versionCode(context: Context): Long =
        installedVersionCode ?: lookUpVersionCode(context).also { installedVersionCode = it }

    private fun lookUpVersionCode(context: Context): Long {
        val manager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(context.packageName, 0)
        }
        return PackageInfoCompat.getLongVersionCode(info)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(context: Context, key: String): List<CjkCentering.Correction?>? {
        val prefs = prefs(context)
        if (prefs.getString("key", null) != key) return null
        return prefs.getString("corrections", null)?.let(CjkCentering::decode)
    }

    fun write(context: Context, key: String, corrections: List<CjkCentering.Correction?>) {
        prefs(context).edit().putString("key", key).putString("corrections", CjkCentering.encode(corrections)).apply()
    }
}

/**
 * Starts loading the kept measurement from disk, and looks up the versionCode
 * that keys it, so the theme's first read of it waits for neither. Called as
 * the app starts.
 */
fun preloadKeptCjkCorrections(context: Context) {
    context.getSharedPreferences(KeptCorrections.FILE, Context.MODE_PRIVATE)
    KeptCorrections.versionCode(context)
}

/** How a measurement is taken: on the device, or a test's stand-in. */
internal fun interface CjkMeasuring {
    fun corrections(base: Typography, density: Density, locales: LocaleList, fonts: FontFamily.Resolver): List<CjkCentering.Correction?>
}

private val onDevice = CjkMeasuring { base, density, locales, fonts ->
    val measurer = TextMeasurer(fonts, density, LayoutDirection.Ltr, cacheSize = 0)
    CjkCentering.corrections(base, MeasuredLines(measurer), PaintGlyphs(density, locales, fonts))
}

/**
 * [base] with each style [CjkCentering.correction]-ed for this device, its
 * density and its languages. Measuring takes a while, so it runs off the main
 * thread, once per device and setting ([KeptCorrections]); until it's done,
 * the styles are as they were.
 */
@Composable
internal fun rememberCjkCenteredTypography(base: Typography, measuring: CjkMeasuring = onDevice): Typography {
    val context = LocalContext.current.applicationContext
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val locales = configuration.locales
    val fonts = LocalFontFamilyResolver.current
    val weightAdjustment = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) configuration.fontWeightAdjustment else 0
    val versionCode = remember(context) { KeptCorrections.versionCode(context) }
    val key = remember(versionCode, density, weightAdjustment, locales) {
        KeptCorrections.key(versionCode, density, weightAdjustment, locales)
    }
    // A fresh state for each key: another density or language starts from what's kept for
    // it, not from the last one's.
    val corrections = remember(key, base) { mutableStateOf(KeptCorrections.read(context, key)) }
    LaunchedEffect(key, base) {
        if (corrections.value != null) return@LaunchedEffect
        corrections.value = withContext(Dispatchers.Default) { measuring.corrections(base, density, locales, fonts) }
            .also { KeptCorrections.write(context, key, it) }
    }
    val current = corrections.value
    // Nothing to raise: the base itself, so nothing downstream sees a new typography.
    return remember(base, current) { current?.takeIf { it.any { c -> c != null } }?.let { CjkCentering.applied(base, it) } ?: base }
}
