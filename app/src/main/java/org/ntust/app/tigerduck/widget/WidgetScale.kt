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
data class WidgetScale(val ui: Float = DisplayScale.DEFAULT, val text: Float = DisplayScale.DEFAULT) {

    /** Of a box sized to hold a line of text: whichever of the two makes it the larger. */
    val textBox: Float get() = maxOf(ui, text)

    companion object {
        /** The sizes as stored in [prefs]. */
        fun of(prefs: AppPreferences) = WidgetScale(
            ui = prefs.displayScale(DisplayScale.WidgetUi),
            text = prefs.displayScale(DisplayScale.WidgetText),
        )
    }
}

/** The sizes the widget being drawn is drawn at. */
val LocalWidgetScale = staticCompositionLocalOf { WidgetScale() }

/** This dimension at the widget display size picked. */
internal val Dp.scaled: Dp
    @Composable @ReadOnlyComposable get() = this * LocalWidgetScale.current.ui

/** This type size at the widget text size picked. */
internal val TextUnit.scaled: TextUnit
    @Composable @ReadOnlyComposable get() = this * LocalWidgetScale.current.text
