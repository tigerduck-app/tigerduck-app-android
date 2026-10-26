package org.ntust.app.tigerduck.ui

import android.content.Context
import android.content.res.Configuration
import kotlin.math.roundToInt
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.DisplayScale

/**
 * The app's display size and text size from the Display and text size page,
 * as MainActivity applies them: over the phone's own, to the Activity's
 * whole configuration, as the phone's Display size and Font size are
 * applied. Compose, its dialogs and sheets, Views and WebViews therefore all
 * follow, which a density handed to Compose alone would not give the windows
 * a dialog or sheet opens. At the default sizes the Activity is left exactly
 * as the phone made it.
 *
 * The application's own context keeps the phone's sizes, so notifications,
 * toasts and the widgets, which have sizes of their own, are not touched.
 */
data class AppScale(val ui: Float, val text: Float) {

    /**
     * Whether the display size moves the screen's size in dp off the phone's,
     * which a rotation or a resized window then makes stale: MainActivity
     * handles those itself, so it recreates itself for them.
     */
    val resizesScreen: Boolean get() = ui != DisplayScale.DEFAULT

    /**
     * What to lay over [system], the phone's configuration, for these sizes;
     * null at the default sizes, so nothing is laid over it.
     *
     * The density is the phone's times [ui], and the screen's width, height
     * and smallest width in dp are its pixels at that density, as the phone's
     * own Display size recomputes them. The font scale is [fontScale]'s.
     */
    fun overlay(system: Configuration): Configuration? {
        if (ui == DisplayScale.DEFAULT && text == DisplayScale.DEFAULT) return null
        return Configuration().apply {
            fontScale = fontScale(system.fontScale)
            if (resizesScreen && system.densityDpi > 0) {
                densityDpi = (system.densityDpi * ui).roundToInt()
                screenWidthDp = rescaled(system.screenWidthDp, system.densityDpi, densityDpi)
                screenHeightDp = rescaled(system.screenHeightDp, system.densityDpi, densityDpi)
                smallestScreenWidthDp = rescaled(system.smallestScreenWidthDp, system.densityDpi, densityDpi)
            }
        }
    }

    /**
     * The phone's font scale [system] times [text], kept to [MAX_FONT_SCALE],
     * the most the phone's own setting gives, unless the phone's alone is
     * already above it.
     */
    fun fontScale(system: Float): Float = (system * text).coerceAtMost(maxOf(system, MAX_FONT_SCALE))

    /**
     * Whether [now], a configuration change the Activity handled itself,
     * leaves the screen size laid over [attached], the phone's configuration
     * it was made from, stale: a rotation, a resized window or a fold.
     */
    fun isStale(attached: Configuration, now: Configuration): Boolean = resizesScreen && (
        attached.orientation != now.orientation ||
            attached.screenWidthDp != now.screenWidthDp ||
            attached.screenHeightDp != now.screenHeightDp ||
            attached.smallestScreenWidthDp != now.smallestScreenWidthDp
        )

    /** [base] at these sizes, for Activity.attachBaseContext; [base] itself at the defaults. */
    fun wrap(base: Context): Context =
        overlay(base.resources.configuration)?.let(base::createConfigurationContext) ?: base

    companion object {
        val DEFAULT = AppScale(DisplayScale.DEFAULT, DisplayScale.DEFAULT)

        /** The most Android's own Font size scales text by. */
        const val MAX_FONT_SCALE = 2f

        /** The sizes stored, read from the file itself; see [AppPreferences.storedDisplayScale]. */
        fun stored(context: Context): AppScale = AppScale(
            ui = AppPreferences.storedDisplayScale(context, DisplayScale.AppUi),
            text = AppPreferences.storedDisplayScale(context, DisplayScale.AppText),
        )

        /** [dp] of a screen at [fromDpi] as the same pixels at [toDpi]; an undefined 0 stays 0. */
        private fun rescaled(dp: Int, fromDpi: Int, toDpi: Int): Int =
            if (dp <= 0 || toDpi <= 0) dp else (dp.toLong() * fromDpi / toDpi).toInt()
    }
}
