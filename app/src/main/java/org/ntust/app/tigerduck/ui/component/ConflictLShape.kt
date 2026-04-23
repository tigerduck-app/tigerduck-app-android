package org.ntust.app.tigerduck.ui.component

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

enum class ConflictLOrientation { TopBarRightTail, LeftTailBottomBar }

/**
 * Two interlocking L-shapes that tile the conflict cell without overlap.
 *
 * Every corner is rounded. The two inner interlocking corners at y = bar/tail
 * boundary use MATCHING arcs — same circle, opposite sweep direction — so
 * Γ and mirror-L tile pixel-perfectly there with no gap. Corners that lie on
 * the overlap's outer top/bottom edge (where both shapes have convex corners
 * and the exterior is beyond the Box) get independent rounding; this leaves
 * a tiny wedge-shaped notch at the very top and bottom of the overlap, which
 * we accept as unavoidable without introducing a genuinely-shared curved
 * dividing line.
 */
data class ConflictLShape(
    val orientation: ConflictLOrientation,
    val soloAboveFraction: Float = 0f,
    val soloBelowFraction: Float = 0f,
    val tailWidthFraction: Float = 0.28f,
    val outerRadius: Dp = 6.dp,
    /**
     * In pure overlap both shapes share a convex corner at the overlap's top
     * edge. Rounding both produces a wedge-shaped gap; leaving both sharp
     * makes them touch seamlessly. Set true when BOTH courses have no
     * solo-above rows (i.e. they start on the same row).
     */
    val sharpTopOuter: Boolean = false,
    /** Symmetric to [sharpTopOuter] for the overlap's bottom edge. */
    val sharpBottomOuter: Boolean = false,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val w = size.width
        val h = size.height
        val r = with(density) { outerRadius.toPx() }
            .coerceAtMost(minOf(w, h) / 4f)

        val overlapFraction = (1f - soloAboveFraction - soloBelowFraction).coerceAtLeast(0.01f)
        val overlapTopY = h * soloAboveFraction
        val overlapEndY = h * (soloAboveFraction + overlapFraction)
        val tailW = w * tailWidthFraction

        val path = when (orientation) {
            ConflictLOrientation.TopBarRightTail ->
                gammaPath(w, h, r, tailW, overlapTopY, overlapEndY,
                    sharpTopOuter, sharpBottomOuter)
            ConflictLOrientation.LeftTailBottomBar ->
                mirrorLPath(w, h, r, tailW, overlapTopY, overlapEndY,
                    sharpTopOuter, sharpBottomOuter)
        }
        return Outline.Generic(path)
    }

    /**
     * Γ: top bar (inset on left by [tailW]) + right tail. Clockwise trace
     * starts at the top-left of the shape, after the top-left corner arc.
     */
    private fun gammaPath(
        w: Float, h: Float, r: Float,
        tailW: Float,
        overlapTopY: Float,
        overlapEndY: Float,
        sharpTopOuter: Boolean,
        sharpBottomOuter: Boolean,
    ): Path {
        val barBottomY = overlapTopY + (overlapEndY - overlapTopY) * 0.5f
        val tailLeftX = w - tailW
        val hasSoloAbove = overlapTopY > 0.5f
        val hasSoloBelow = overlapEndY < h - 0.5f
        val topY = if (hasSoloAbove) 0f else overlapTopY
        val bottomY = if (hasSoloBelow) h else overlapEndY

        return Path().apply {
            // --- Start after top-left arc (or at the sharp corner) ---
            if (hasSoloAbove) {
                moveTo(r, 0f)
            } else if (sharpTopOuter) {
                // Pure overlap at top: sharp 90° corner, no recede.
                moveTo(tailW, overlapTopY)
            } else {
                moveTo(tailW + r, overlapTopY)
            }

            // --- Top edge + top-right corner (CW convex) ---
            lineTo(w - r, topY)
            arcTo(
                rect = Rect(w - 2 * r, topY, w, topY + 2 * r),
                startAngleDegrees = -90f, sweepAngleDegrees = 90f, forceMoveTo = false,
            )

            // --- Right edge + bottom-right corner (CW convex) ---
            lineTo(w, bottomY - r)
            arcTo(
                rect = Rect(w - 2 * r, bottomY - 2 * r, w, bottomY),
                startAngleDegrees = 0f, sweepAngleDegrees = 90f, forceMoveTo = false,
            )

            // --- Bottom edge (branching on solo-below) ---
            if (hasSoloBelow) {
                lineTo(r, h)
                arcTo(
                    rect = Rect(0f, h - 2 * r, 2 * r, h),
                    startAngleDegrees = 90f, sweepAngleDegrees = 90f, forceMoveTo = false,
                )
                lineTo(0f, overlapEndY + r)
                // Convex step-out at (0, overlapEndY): shape widens as we enter solo-below
                arcTo(
                    rect = Rect(0f, overlapEndY, 2 * r, overlapEndY + 2 * r),
                    startAngleDegrees = 180f, sweepAngleDegrees = 90f, forceMoveTo = false,
                )
                lineTo(tailLeftX - r, overlapEndY)
                // Concave step at (tailLeftX, overlapEndY): shape narrows to tail going up
                arcTo(
                    rect = Rect(tailLeftX - 2 * r, overlapEndY - 2 * r, tailLeftX, overlapEndY),
                    startAngleDegrees = 90f, sweepAngleDegrees = -90f, forceMoveTo = false,
                )
            } else if (sharpBottomOuter) {
                // Pure overlap at bottom: sharp 90° corner.
                lineTo(tailLeftX, overlapEndY)
            } else {
                lineTo(tailLeftX + r, overlapEndY)
                // Convex corner at (tailLeftX, overlapEndY) — tail's bottom-left
                arcTo(
                    rect = Rect(tailLeftX, overlapEndY - 2 * r, tailLeftX + 2 * r, overlapEndY),
                    startAngleDegrees = 90f, sweepAngleDegrees = 90f, forceMoveTo = false,
                )
            }

            // --- Up tail's left edge to the inner concave corner ---
            lineTo(tailLeftX, barBottomY + r)
            // Concave at (tailLeftX, barBottomY) — MATCHES mirror-L's convex
            arcTo(
                rect = Rect(tailLeftX - 2 * r, barBottomY, tailLeftX, barBottomY + 2 * r),
                startAngleDegrees = 0f, sweepAngleDegrees = -90f, forceMoveTo = false,
            )

            // --- Across bar bottom to the inner convex corner ---
            lineTo(tailW + r, barBottomY)
            // Convex at (tailW, barBottomY) — MATCHES mirror-L's concave
            arcTo(
                rect = Rect(tailW, barBottomY - 2 * r, tailW + 2 * r, barBottomY),
                startAngleDegrees = 90f, sweepAngleDegrees = 90f, forceMoveTo = false,
            )

            // --- Up bar's left edge + top-left corner (branching on solo-above) ---
            lineTo(tailW, overlapTopY + r)
            if (hasSoloAbove) {
                // Concave step at (tailW, overlapTopY): shape widens going up into solo-above
                arcTo(
                    rect = Rect(tailW - 2 * r, overlapTopY, tailW, overlapTopY + 2 * r),
                    startAngleDegrees = 0f, sweepAngleDegrees = -90f, forceMoveTo = false,
                )
                lineTo(r, overlapTopY)
                // Convex step at (0, overlapTopY)
                arcTo(
                    rect = Rect(0f, overlapTopY - 2 * r, 2 * r, overlapTopY),
                    startAngleDegrees = 90f, sweepAngleDegrees = 90f, forceMoveTo = false,
                )
                lineTo(0f, r)
                // Top-left cell corner
                arcTo(
                    rect = Rect(0f, 0f, 2 * r, 2 * r),
                    startAngleDegrees = 180f, sweepAngleDegrees = 90f, forceMoveTo = false,
                )
            } else if (sharpTopOuter) {
                // Pure overlap at top: sharp 90° corner, close straight back to start.
                lineTo(tailW, overlapTopY)
            } else {
                // Top-left of bar: convex, independent rounding.
                arcTo(
                    rect = Rect(tailW, overlapTopY, tailW + 2 * r, overlapTopY + 2 * r),
                    startAngleDegrees = 180f, sweepAngleDegrees = 90f, forceMoveTo = false,
                )
            }

            close()
        }
    }

    /**
     * Mirror-L: left tail + bottom bar (inset on right by [tailW]).
     */
    private fun mirrorLPath(
        w: Float, h: Float, r: Float,
        tailW: Float,
        overlapTopY: Float,
        overlapEndY: Float,
        sharpTopOuter: Boolean,
        sharpBottomOuter: Boolean,
    ): Path {
        val barTopY = overlapTopY + (overlapEndY - overlapTopY) * 0.5f
        val tailRightX = tailW
        val barRightX = w - tailW
        val hasSoloAbove = overlapTopY > 0.5f
        val hasSoloBelow = overlapEndY < h - 0.5f
        val topY = if (hasSoloAbove) 0f else overlapTopY
        val bottomY = if (hasSoloBelow) h else overlapEndY

        return Path().apply {
            // --- Start after top-left arc (always on left cell edge) ---
            moveTo(r, topY)

            // --- Top edge + top-right (branching on solo-above) ---
            if (hasSoloAbove) {
                lineTo(w - r, 0f)
                arcTo(
                    rect = Rect(w - 2 * r, 0f, w, 2 * r),
                    startAngleDegrees = -90f, sweepAngleDegrees = 90f, forceMoveTo = false,
                )
                lineTo(w, overlapTopY - r)
                // Convex step at (w, overlapTopY)
                arcTo(
                    rect = Rect(w - 2 * r, overlapTopY - 2 * r, w, overlapTopY),
                    startAngleDegrees = 0f, sweepAngleDegrees = 90f, forceMoveTo = false,
                )
                lineTo(tailRightX + r, overlapTopY)
                // Concave step at (tailRightX, overlapTopY): shape narrows to tail going down
                arcTo(
                    rect = Rect(tailRightX, overlapTopY, tailRightX + 2 * r, overlapTopY + 2 * r),
                    startAngleDegrees = 270f, sweepAngleDegrees = -90f, forceMoveTo = false,
                )
            } else if (sharpTopOuter) {
                // Pure overlap at top: sharp 90° corner.
                lineTo(tailRightX, overlapTopY)
            } else {
                lineTo(tailRightX - r, overlapTopY)
                // Convex at (tailRightX, overlapTopY) — tail's top-right
                arcTo(
                    rect = Rect(tailRightX - 2 * r, overlapTopY, tailRightX, overlapTopY + 2 * r),
                    startAngleDegrees = -90f, sweepAngleDegrees = 90f, forceMoveTo = false,
                )
            }

            // --- Down tail's right edge to inner concave ---
            lineTo(tailW, barTopY - r)
            // Concave at (tailW, barTopY) — MATCHES Γ's convex
            arcTo(
                rect = Rect(tailW, barTopY - 2 * r, tailW + 2 * r, barTopY),
                startAngleDegrees = 180f, sweepAngleDegrees = -90f, forceMoveTo = false,
            )

            // --- Across bar top to inner convex ---
            lineTo(barRightX - r, barTopY)
            // Convex at (barRightX, barTopY) — MATCHES Γ's concave
            arcTo(
                rect = Rect(barRightX - 2 * r, barTopY, barRightX, barTopY + 2 * r),
                startAngleDegrees = -90f, sweepAngleDegrees = 90f, forceMoveTo = false,
            )

            // --- Down bar's right edge ---
            lineTo(barRightX, overlapEndY - r)

            // --- Bottom-right of bar + bottom edge (branching on solo-below) ---
            if (hasSoloBelow) {
                // Concave step at (barRightX, overlapEndY): shape widens entering solo-below
                arcTo(
                    rect = Rect(barRightX, overlapEndY - 2 * r, barRightX + 2 * r, overlapEndY),
                    startAngleDegrees = 180f, sweepAngleDegrees = -90f, forceMoveTo = false,
                )
                lineTo(w - r, overlapEndY)
                // Convex step at (w, overlapEndY)
                arcTo(
                    rect = Rect(w - 2 * r, overlapEndY, w, overlapEndY + 2 * r),
                    startAngleDegrees = 270f, sweepAngleDegrees = 90f, forceMoveTo = false,
                )
                lineTo(w, h - r)
                arcTo(
                    rect = Rect(w - 2 * r, h - 2 * r, w, h),
                    startAngleDegrees = 0f, sweepAngleDegrees = 90f, forceMoveTo = false,
                )
                lineTo(r, h)
                arcTo(
                    rect = Rect(0f, h - 2 * r, 2 * r, h),
                    startAngleDegrees = 90f, sweepAngleDegrees = 90f, forceMoveTo = false,
                )
            } else if (sharpBottomOuter) {
                // Pure overlap at bottom: sharp 90° corner at bar's bottom-right.
                lineTo(barRightX, overlapEndY)
                // Continue along bottom edge to left cell corner
                lineTo(r, overlapEndY)
                arcTo(
                    rect = Rect(0f, overlapEndY - 2 * r, 2 * r, overlapEndY),
                    startAngleDegrees = 90f, sweepAngleDegrees = 90f, forceMoveTo = false,
                )
            } else {
                // No solo-below: bar bottom is shape bottom, rounded
                arcTo(
                    rect = Rect(barRightX - 2 * r, overlapEndY - 2 * r, barRightX, overlapEndY),
                    startAngleDegrees = 0f, sweepAngleDegrees = 90f, forceMoveTo = false,
                )
                lineTo(r, overlapEndY)
                arcTo(
                    rect = Rect(0f, overlapEndY - 2 * r, 2 * r, overlapEndY),
                    startAngleDegrees = 90f, sweepAngleDegrees = 90f, forceMoveTo = false,
                )
            }

            // --- Up left edge + top-left corner ---
            lineTo(0f, topY + r)
            arcTo(
                rect = Rect(0f, topY, 2 * r, topY + 2 * r),
                startAngleDegrees = 180f, sweepAngleDegrees = 90f, forceMoveTo = false,
            )

            close()
        }
    }
}
