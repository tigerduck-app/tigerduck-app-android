package org.ntust.app.tigerduck.browser

import android.content.Context
import android.content.SharedPreferences

/** Page zoom in the TigerDuck browser: a percentage of the page's text, in [STEP] steps. */
object BrowserZoom {
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
}

/**
 * What the TigerDuck browser keeps: the Appearance picked, for the app, and
 * each site's zoom and desktop-site choice, by host.
 *
 * Plain preferences in a file of their own. The per-site choices are a
 * student's browsing, so [clearSites] runs at every sign-out with the
 * browser's other data; the Appearance is a setting, as the app's Theme is,
 * and stays.
 */
class BrowserSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    var appearance: BrowserAppearance
        get() = BrowserAppearance.fromCode(prefs.getString(KEY_APPEARANCE, null))
        set(value) = prefs.edit().putString(KEY_APPEARANCE, value.code).apply()

    fun zoom(host: String): Int = BrowserZoom.clamp(prefs.getInt(ZOOM_PREFIX + host.lowercase(), BrowserZoom.DEFAULT))

    fun setZoom(host: String, percent: Int) {
        val key = ZOOM_PREFIX + host.lowercase()
        val p = BrowserZoom.clamp(percent)
        prefs.edit().apply { if (p == BrowserZoom.DEFAULT) remove(key) else putInt(key, p) }.apply()
    }

    fun desktopSite(host: String): Boolean = prefs.getBoolean(DESKTOP_PREFIX + host.lowercase(), false)

    fun setDesktopSite(host: String, desktop: Boolean) {
        val key = DESKTOP_PREFIX + host.lowercase()
        prefs.edit().apply { if (desktop) putBoolean(key, true) else remove(key) }.apply()
    }

    /** Forgets every site's zoom and desktop-site choice; the Appearance stays. */
    fun clearSites() {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith(ZOOM_PREFIX) || it.startsWith(DESKTOP_PREFIX) }.forEach(edit::remove)
        edit.commit()
    }

    companion object {
        const val PREFS_FILE = "tigerduck_browser_settings"
        private const val KEY_APPEARANCE = "appearance"
        private const val ZOOM_PREFIX = "zoom/"
        private const val DESKTOP_PREFIX = "desktop/"
    }
}
