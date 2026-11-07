package org.ntust.app.tigerduck.shared

/**
 * The way a linear accent gradient ([AccentGradient]) runs, from its first stop to its last, in
 * the order the phone's gradient editor offers them: row by row in its two rows of four, each
 * direction above its reverse. Here in `:shared` because the watch draws the same gradient.
 *
 * [wireName] is the iPhone's name for it, which the gradient's JSON carries. [code] is what the
 * release before gradients of several stops stored and sent the watch, read back from either by
 * [AccentGradient.legacy]: fixed per direction, so an old pick reads as the direction it was.
 *
 * The start and end sit at fractions of the shape's width and height. They are the screen's own
 * sides, not the reading direction's: left to right starts at the left edge in a right-to-left
 * language too, as the arrow that shows it does.
 */
enum class AccentGradientDirection(
    val code: Int,
    val wireName: String,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
) {
    TopToBottom(0, "topToBottom", 0f, 0f, 0f, 1f),
    LeftToRight(2, "leftToRight", 0f, 0f, 1f, 0f),
    BottomLeftToTopRight(6, "bottomLeftToTopRight", 0f, 1f, 1f, 0f),
    TopLeftToBottomRight(5, "topLeftToBottomRight", 0f, 0f, 1f, 1f),
    BottomToTop(1, "bottomToTop", 0f, 1f, 0f, 0f),
    RightToLeft(3, "rightToLeft", 1f, 0f, 0f, 0f),
    TopRightToBottomLeft(4, "topRightToBottomLeft", 1f, 0f, 0f, 1f),
    BottomRightToTopLeft(7, "bottomRightToTopLeft", 1f, 1f, 0f, 0f);

    companion object {
        /** The iPhone's default, and a gradient's before any direction is picked: top left to bottom right. */
        val DEFAULT = TopLeftToBottomRight

        /** The direction stored as [code]; one this build does not know reads as [DEFAULT]. */
        fun fromCode(code: Int): AccentGradientDirection = entries.firstOrNull { it.code == code } ?: DEFAULT

        /** The direction the iPhone calls [name], or null for a name this build does not know. */
        fun fromWireName(name: String): AccentGradientDirection? = entries.firstOrNull { it.wireName == name }
    }
}
