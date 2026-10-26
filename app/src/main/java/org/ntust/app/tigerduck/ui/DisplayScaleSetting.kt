package org.ntust.app.tigerduck.ui

import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.DisplayScale

/**
 * AppState's copy of the sizes on the Display and text size page, held as
 * Compose state over their preferences. MainActivity watches [app], and makes
 * itself again at a new one: the sizes apply to its configuration, which only
 * a new Activity takes. [onWidgetChange] hears each change of the widgets'
 * sizes, as the widgets redraw only when asked. Its own class because
 * [AppState] needs a full Hilt graph, and this needs only the store.
 */
internal class DisplayScaleSetting(
    private val prefs: AppPreferences,
    private val onWidgetChange: () -> Unit = {},
) {
    private val states: Map<DisplayScale, MutableFloatState> =
        DisplayScale.entries.associateWith { mutableFloatStateOf(prefs.displayScale(it)) }

    /** [scale] as picked. */
    operator fun get(scale: DisplayScale): Float = states.getValue(scale).floatValue

    /** Picks [value] for [scale], as its nearest step. */
    operator fun set(scale: DisplayScale, value: Float) {
        val normalized = scale.normalize(value)
        val state = states.getValue(scale)
        if (state.floatValue == normalized) return
        state.floatValue = normalized
        prefs.setDisplayScale(scale, normalized)
        if (scale == DisplayScale.WidgetUi || scale == DisplayScale.WidgetText) onWidgetChange()
    }

    /** The app's sizes, as MainActivity applies them. */
    val app: AppScale get() = AppScale(this[DisplayScale.AppUi], this[DisplayScale.AppText])

    /** Reads the store again, after a full reset wiped it. */
    fun reload() {
        states.forEach { (scale, state) -> state.floatValue = prefs.displayScale(scale) }
    }
}
