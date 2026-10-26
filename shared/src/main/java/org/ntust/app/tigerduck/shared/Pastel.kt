package org.ntust.app.tigerduck.shared

/**
 * The TAT look's pastel, as the phone's class table and the watch's course cards
 * draw it: a course color mixed with white into an opaque pastel, with dark text
 * on it. Plain channel math, so the phone and the watch draw the same pastel
 * from it with their own color types.
 */
object Pastel {
    /** How far each sRGB channel moves toward white: at least 65%, where [TEXT_ARGB] reads at better than 7:1. */
    const val WHITE_SHARE = 0.65f

    /** A card that steps back, such as a class already over: paler, as a TigerDuck card's tint is fainter. */
    const val MUTED_WHITE_SHARE = 0.82f

    /** How far a pastel's outline moves toward black: better than 3:1 on its own pastel, whatever the color. */
    const val OUTLINE_BLACK_SHARE = 0.5f

    /** Text on a pastel, which stays light in either theme. */
    const val TEXT_ARGB: Int = 0xFF1A1A1A.toInt()

    /** Room hints, times and marks on a pastel: [TEXT_ARGB] at this alpha; at 70% it fell under 4.5:1. */
    const val SECONDARY_TEXT_ALPHA = 0.8f

    /** One sRGB channel, 0 to 1, moved [whiteShare] of the way to white, which keeps the hue. */
    fun channel(value: Float, whiteShare: Float): Float = value + (1f - value) * whiteShare
}
