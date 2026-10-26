package org.ntust.app.tigerduck.browser

import android.graphics.Color
import android.webkit.WebSettings
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.pow

/** The ⋮ menu's Appearance: the browser's colors follow the page, or are light or dark. */
enum class BrowserAppearance(val code: String) {
    AUTO("auto"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        /** What the browser starts in until the student picks another, and what an unknown stored code reads as. */
        val DEFAULT = LIGHT

        fun fromCode(code: String?): BrowserAppearance = entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}

/**
 * How a page told a new color scheme follows it: at once, where the WebView
 * passes the change to the loaded page, or from the page's next load. The one
 * place this is set; see `BrowserPageScheme`.
 */
enum class PageSchemeFollow { LIVE, NEXT_LOAD }

object BrowserColors {
    /**
     * Lets the WebView darken a page that has no dark style of its own while
     * the page is told dark ([pageToldDark], through the theme's
     * `isLightTheme`): under Dark, and under Automatic while the app is dark.
     * AndroidX WebKit's algorithmic darkening, where the WebView has it
     * ([supported]; WebView 105 and later have it on any Android this app runs
     * on). A page that uses `prefers-color-scheme` keeps its own dark style,
     * and one that says `color-scheme: only light` stays light. The darkening
     * is in how the page is drawn on screen, so a print keeps the site's
     * colors. True once allowed; where the WebView cannot, pages show as they are.
     */
    fun allowDarkening(
        settings: WebSettings,
        supported: Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING),
    ): Boolean {
        if (!supported) return false
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
        return true
    }

    /**
     * Whether the browser's bars are dark: as [appearance] says, or in Auto as
     * the page is ([pageDark], from [pageTone]), and light until the page is
     * known. Most pages the browser opens are light, so a dark app would
     * otherwise flash dark bars over a page about to come up light.
     */
    fun barsDark(appearance: BrowserAppearance, pageDark: Boolean?): Boolean = when (appearance) {
        BrowserAppearance.LIGHT -> false
        BrowserAppearance.DARK -> true
        BrowserAppearance.AUTO -> pageDark ?: false
    }

    /**
     * The WebView's own background: what shows before a page's first frame,
     * and behind a page that paints none. White or black as the bars are
     * ([barsDark]), so light until the page is known. Left to itself, a WebView
     * on a phone in night mode painted black until the page's first frame, and
     * every open flashed black before a light page.
     */
    fun webBackground(appearance: BrowserAppearance, pageDark: Boolean?): Int =
        if (barsDark(appearance, pageDark)) Color.BLACK else Color.WHITE

    /**
     * Which scheme the page is told (`prefers-color-scheme`): Light or Dark
     * as picked, and in Auto the app's own theme, [appDark], as its Theme
     * setting resolves it.
     */
    fun pageToldDark(appearance: BrowserAppearance, appDark: Boolean): Boolean = when (appearance) {
        BrowserAppearance.LIGHT -> false
        BrowserAppearance.DARK -> true
        BrowserAppearance.AUTO -> appDark
    }

    /**
     * The script the browser runs on a page to tell how dark it is: the
     * background of the body and of the page, the `theme-color` it names and
     * its `color-scheme`, as its style and its `color-scheme` meta say, and
     * whether its style sheets ask `prefers-color-scheme` (those it may read:
     * another site's sheet keeps its rules to itself). Reads only; the page
     * sees nothing.
     */
    const val TONE_PROBE = """(function() {
  function bg(el) { return el ? getComputedStyle(el).backgroundColor : null; }
  var asks = /prefers-color-scheme/i;
  function ruleAsks(list, depth) {
    for (var i = 0; i < list.length; i++) {
      var rule = list[i];
      if (rule.media && asks.test(rule.media.mediaText)) return true;
      if (depth < 4 && rule.cssRules && ruleAsks(rule.cssRules, depth + 1)) return true;
      if (depth < 4 && rule.styleSheet && sheetAsks(rule.styleSheet, depth + 1)) return true;
    }
    return false;
  }
  function sheetAsks(sheet, depth) {
    if (sheet.media && asks.test(sheet.media.mediaText)) return true;
    try { return ruleAsks(sheet.cssRules, depth); } catch (e) { return false; }
  }
  function prefersScheme() {
    for (var i = 0; i < document.styleSheets.length; i++) if (sheetAsks(document.styleSheets[i], 0)) return true;
    return false;
  }
  var root = document.documentElement;
  var meta = document.querySelector('meta[name="theme-color"]');
  var scheme = document.querySelector('meta[name="color-scheme"]');
  return {
    body: bg(document.body),
    root: bg(root),
    theme: meta ? meta.getAttribute('content') : null,
    scheme: root ? getComputedStyle(root).colorScheme || null : null,
    meta: scheme ? scheme.getAttribute('content') : null,
    prefersScheme: prefersScheme()
  };
})();"""

    /**
     * Whether the page is dark, from [probeResult], what `evaluateJavascript`
     * gave [TONE_PROBE] back; null when it gave nothing to go by.
     *
     * The first background that is drawn decides, the body's then the
     * page's; else the `theme-color`; else the `color-scheme`, where a page
     * that offers both takes the one it was told ([toldDark]). A page that
     * draws nothing and names nothing is the WebView's white, unless the
     * WebView darkens it.
     *
     * Where the WebView darkens pages ([darkened], see [allowDarkening]), a
     * light page told dark is dark when it names no dark scheme of its own,
     * does not keep itself light (`only light`) and has no style that asks
     * `prefers-color-scheme`, which the WebView takes as a dark style of its
     * own: the darkening is in how it is drawn, not in the colors its styles
     * give. Its scheme is its root's `color-scheme` style, or its meta where
     * the style says nothing.
     */
    fun pageTone(probeResult: String?, toldDark: Boolean, darkened: Boolean = false): Boolean? {
        val probe = runCatching { JsonParser.parseString(probeResult ?: return null) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val own = ownTone(probe, toldDark)
        if (own || !darkened || !toldDark || probe.flag("prefersScheme")) return own
        val scheme = probe.pageScheme()
        return "dark" !in scheme && "only" !in scheme
    }

    private fun ownTone(probe: JsonObject, toldDark: Boolean): Boolean {
        listOf("body", "root").forEach { name ->
            parseColor(probe.text(name))?.takeIf { it.alpha > 0.5 }?.let { return it.isDark }
        }
        parseColor(probe.text("theme"))?.takeIf { it.alpha > 0.5 }?.let { return it.isDark }
        val scheme = probe.schemes("scheme")
        return when {
            "dark" in scheme && "light" in scheme -> toldDark
            "dark" in scheme -> true
            else -> false
        }
    }

    /**
     * The page's color scheme as the WebView reads it: its root's `color-scheme` style, or its
     * meta where the style is `normal`. Words apart by any white space, as CSS parses them; a value
     * with a comma is none at all.
     */
    private fun JsonObject.pageScheme(): List<String> {
        fun words(name: String): List<String> {
            val value = text(name)?.trim()?.lowercase().orEmpty()
            if (value.isEmpty() || ',' in value) return emptyList()
            return value.split(WHITE_SPACE).filter { it != "normal" }
        }
        return words("scheme").ifEmpty { words("meta") }
    }

    private val WHITE_SPACE = Regex("""\s+""")

    private fun JsonObject.schemes(name: String): List<String> =
        text(name)?.lowercase().orEmpty().split(' ', ',').filter { it.isNotBlank() }

    private fun JsonObject.text(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.flag(name: String): Boolean =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true

    /** An sRGB color with its alpha, 0 to 1. */
    data class Rgba(val red: Int, val green: Int, val blue: Int, val alpha: Double) {
        /** Whether white reads better on it than black: its relative luminance under 0.179. */
        val isDark: Boolean get() = luminance < 0.179

        val luminance: Double
            get() {
                fun linear(c: Int): Double = (c / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
                return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
            }
    }

    private val FUNCTIONAL = Regex("""rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)(?:\s*[,/]\s*([\d.]+%?))?\s*\)""")
    private val HEX = Regex("""#([0-9a-fA-F]{3,8})""")

    /** A CSS color as `getComputedStyle` or a `theme-color` writes it: `rgb()`, `rgba()` or `#hex`; null for anything else. */
    fun parseColor(text: String?): Rgba? {
        val value = text?.trim() ?: return null
        FUNCTIONAL.matchEntire(value)?.let { m ->
            // The pattern lets "." and "1.2.3" through; a page's color is whatever it wrote.
            fun number(text: String): Double? = text.toDoubleOrNull()?.takeIf { it.isFinite() }
            val (r, g, b) = (1..3).map { number(m.groupValues[it])?.toInt()?.coerceIn(0, 255) ?: return null }
            val alphaText = m.groupValues[4]
            val alpha = when {
                alphaText.isEmpty() -> 1.0
                alphaText.endsWith("%") -> (number(alphaText.removeSuffix("%")) ?: return null) / 100
                else -> number(alphaText) ?: return null
            }
            return Rgba(r, g, b, alpha.coerceIn(0.0, 1.0))
        }
        HEX.matchEntire(value)?.let { m ->
            val hex = m.groupValues[1]
            val full = when (hex.length) {
                3, 4 -> hex.map { "$it$it" }.joinToString("")
                6, 8 -> hex
                else -> return null
            }
            val r = full.substring(0, 2).toInt(16)
            val g = full.substring(2, 4).toInt(16)
            val b = full.substring(4, 6).toInt(16)
            val a = if (full.length == 8) full.substring(6, 8).toInt(16) / 255.0 else 1.0
            return Rgba(r, g, b, a)
        }
        return null
    }
}
