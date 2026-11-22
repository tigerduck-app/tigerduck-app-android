package org.ntust.app.tigerduck.util

import java.text.NumberFormat
import java.util.Locale

/**
 * A page's text size, in percent of the page's own, and the steps it moves through: the
 * TigerDuck browser's zoom and School Mail's text size, as the iPhone's `PageZoom`
 * (tigerduck-app#1047). Both apply it as the WebView's text zoom, which reflows the text to the
 * page's width.
 */
object PageZoom {
    const val DEFAULT = 100
    const val MIN = 50
    const val MAX = 200
    const val STEP = 10

    /** The next step up from [percent], or [MAX]. Off-step values go to the step above. */
    fun zoomIn(percent: Int): Int = ((clamp(percent) / STEP + 1) * STEP).coerceAtMost(MAX)

    /** The next step down from [percent], or [MIN]. Off-step values go to the step below. */
    fun zoomOut(percent: Int): Int {
        val p = clamp(percent)
        val below = if (p % STEP == 0) p - STEP else p / STEP * STEP
        return below.coerceAtLeast(MIN)
    }

    fun canZoomIn(percent: Int): Boolean = clamp(percent) < MAX
    fun canZoomOut(percent: Int): Boolean = clamp(percent) > MIN

    fun clamp(percent: Int): Int = percent.coerceIn(MIN, MAX)

    /** [percent] as [locale] writes a percentage: "110%", or "110 %" where a space goes before the sign. */
    fun label(percent: Int, locale: Locale): String = NumberFormat.getPercentInstance(locale).format(percent / 100.0)
}
