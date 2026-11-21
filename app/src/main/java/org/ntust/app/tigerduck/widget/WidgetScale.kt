package org.ntust.app.tigerduck.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.DisplayScale

/**
 * The widget display size and text size picked on the Display and text size
 * page. The home screen draws a widget, at its own density and the phone's
 * font size, from the dp and sp Glance hands it, so the app's own sizes
 * cannot reach it: these multiply the widgets' own dimensions instead, [ui]
 * their padding, corners, columns and icons, [text] their type. How big the
 * widget itself is stays the home screen's.
 */
data class WidgetScale(
    val ui: Float = DisplayScale.DEFAULT,
    val text: Float = DisplayScale.DEFAULT,
    /**
     * What brings the phone's font size down to [MAX_FONT_SCALE] for the widgets' type, 1 at or
     * below it; see [capped].
     */
    val fontCap: Float = 1f,
) {

    /** Of a box sized to hold a line of text: whichever of the two makes it the larger. */
    val textBox: Float get() = maxOf(ui, text)

    /**
     * These sizes with the widgets' type held at [MAX_FONT_SCALE] of the phone's [fontScale], as
     * the iPhone's widgets cap Dynamic Type at xxxLarge: the home screen draws sp at the phone's
     * font size, which past that crowds a widget's fixed cells out.
     */
    fun capped(fontScale: Float): WidgetScale = copy(fontCap = cappedFontScale(fontScale) / fontScale)

    companion object {
        /** About the iPhone's xxxLarge, and Android's largest font size before Android 14's. */
        const val MAX_FONT_SCALE = 1.3f

        /**
         * A line of the widgets' type for its size, which a widget's sizes estimate its rows by: the
         * font's padding, and room for a Chinese name's taller lines.
         */
        const val LINE_HEIGHT = 1.45f

        /** The sizes as stored in [prefs]. */
        fun of(prefs: AppPreferences) = WidgetScale(
            ui = prefs.displayScale(DisplayScale.WidgetUi),
            text = prefs.displayScale(DisplayScale.WidgetText),
        )

        /** The phone's [fontScale] as a widget's type takes it: no more than [MAX_FONT_SCALE]. */
        fun cappedFontScale(fontScale: Float): Float = if (fontScale > 0f) minOf(fontScale, MAX_FONT_SCALE) else 1f
    }
}

/** The sizes the widget being drawn is drawn at. */
val LocalWidgetScale = staticCompositionLocalOf { WidgetScale() }

/** This dimension at the widget display size picked. */
internal val Dp.scaled: Dp
    @Composable @ReadOnlyComposable get() = this * LocalWidgetScale.current.ui

/** This type size at the widget text size picked, the phone's font size held at its cap. */
internal val TextUnit.scaled: TextUnit
    @Composable @ReadOnlyComposable get() = this * (LocalWidgetScale.current.text * LocalWidgetScale.current.fontCap)
