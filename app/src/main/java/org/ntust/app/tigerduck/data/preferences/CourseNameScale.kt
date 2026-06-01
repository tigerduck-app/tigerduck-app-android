package org.ntust.app.tigerduck.data.preferences

import kotlin.math.roundToInt

/**
 * Multiplier applied to the course-name font size on class-table cards.
 * 1.0× = the pre-feature baseline; the slider in
 * `CourseNameSizeSettingsScreen` writes a normalized value here via
 * [AppState.courseNameScale].
 *
 * Range is intentionally narrow: below 0.8× names become unreadable
 * inside the cell, above 1.6× they overflow before Compose's clipping
 * rescues them. Snapping to 0.05× ticks keeps the displayed `1.20×`
 * label reproducible across launches.
 *
 * Scope mirrors the iOS implementation: phone class-table cells only.
 * `CurrentClassCard` (Home hero) and the watch are intentionally
 * excluded — those surfaces are sized for their containers and a
 * per-app override there would fight the layout.
 */
object CourseNameScale {
    const val MIN = 0.8f
    const val MAX = 1.6f
    const val STEP = 0.05f
    const val DEFAULT = 1.0f

    fun normalize(value: Float): Float {
        val clamped = value.coerceIn(MIN, MAX)
        val stepped = (clamped / STEP).roundToInt() * STEP
        return stepped.coerceIn(MIN, MAX)
    }
}
