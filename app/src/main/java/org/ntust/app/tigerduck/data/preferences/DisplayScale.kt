package org.ntust.app.tigerduck.data.preferences

import android.content.SharedPreferences
import kotlin.math.abs

/** The display size steps, from crowded to roomy: small screens need the low end most. */
private val UI_STEPS = listOf(0.75f, 0.8f, 0.85f, 0.9f, 0.95f, 1f, 1.1f, 1.2f, 1.3f)

/** The text size steps, which reach further up, as Android's own font size does. */
private val TEXT_STEPS = listOf(0.8f, 0.85f, 0.9f, 0.95f, 1f, 1.1f, 1.2f, 1.3f, 1.5f)

/**
 * The sizes on the Display and text size page, each a multiplier on what the
 * phone's own Display size and Font size give: the app's, which MainActivity
 * applies to its whole configuration, and the widgets', which they multiply
 * their own dimensions and type by. 1x, the phone's sizes as they are, is the
 * default and the value of anything stored that is not one of [steps].
 *
 * Stored as the multiplier, not a step's index, so a later change of steps
 * keeps the size a user picked as near as it can.
 */
enum class DisplayScale(internal val key: String, val steps: List<Float>) {
    AppUi("appUiScale", UI_STEPS),
    AppText("appTextScale", TEXT_STEPS),
    WidgetUi("widgetUiScale", UI_STEPS),
    WidgetText("widgetTextScale", TEXT_STEPS),
    ;

    /**
     * [value] as the step nearest it, and 1x for a value outside the steps'
     * range, such as one a later build stored, or no number at all.
     */
    fun normalize(value: Float): Float {
        if (value.isNaN() || value < steps.first() || value > steps.last()) return DEFAULT
        return steps.minBy { abs(it - value) }
    }

    /** The position of [value]'s step among [steps], for the slider. */
    fun stepIndex(value: Float): Int = steps.indexOf(normalize(value))

    /** The size stored in [prefs], normalized; 1x when none is, or what is there is no number. */
    internal fun read(prefs: SharedPreferences): Float {
        if (!prefs.contains(key)) return DEFAULT
        val stored = runCatching { prefs.getFloat(key, DEFAULT) }.getOrDefault(DEFAULT)
        return normalize(stored)
    }

    companion object {
        const val DEFAULT = 1f
    }
}
