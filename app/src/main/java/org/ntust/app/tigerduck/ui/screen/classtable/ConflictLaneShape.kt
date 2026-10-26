// The outline of one course in a cluster of three or more clashing courses, whose runs
// (ClassTableCellLayout.laneRuns) widen across the lanes where it meets no other course. A port
// of the iPhone's ConflictLaneShape (tigerduck-app#153): one rounded outline around the runs,
// with the seam between two runs bridged across the width they share, so a course that widens
// reads as one shape and keeps its seam to the courses beside it.

package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.hypot
import kotlin.math.min

internal object ConflictLaneOutline {

    /** [runs], top to bottom, with a strip bridging each seam across the width both sides share. */
    fun strips(runs: List<Rect>): List<Rect> {
        val first = runs.firstOrNull() ?: return emptyList()
        val strips = mutableListOf(first)
        for (run in runs.drop(1)) {
            val above = strips.last()
            val left = maxOf(above.left, run.left)
            val right = min(above.right, run.right)
            if (run.top > above.bottom && right > left) strips += Rect(left, above.bottom, right, run.top)
            strips += run
        }
        return strips
    }

    /**
     * The corners of the outline around [strips]: down their right edges and back up their left
     * ones, every edge level or upright, with no repeated point and none midway along an edge.
     */
    fun corners(strips: List<Rect>): List<Offset> {
        val points = mutableListOf<Offset>()
        for (strip in strips) {
            points += Offset(strip.right, strip.top)
            points += Offset(strip.right, strip.bottom)
        }
        for (strip in strips.asReversed()) {
            points += Offset(strip.left, strip.bottom)
            points += Offset(strip.left, strip.top)
        }
        var changed = true
        while (changed && points.size > 2) {
            changed = false
            for (index in points.indices) {
                val previous = points[(index + points.size - 1) % points.size]
                val point = points[index]
                val next = points[(index + 1) % points.size]
                val repeated = point == previous
                val straight = (previous.x == point.x && point.x == next.x) || (previous.y == point.y && point.y == next.y)
                if (repeated || straight) {
                    points.removeAt(index)
                    changed = true
                    break
                }
            }
        }
        return points
    }
}

/**
 * [runs] as one rounded outline, each relative to the shape's own top left. Every corner, convex
 * or not, is rounded by at most [cornerRadius] and half of either edge it joins.
 */
internal class ConflictLaneShape(private val runs: List<DpRect>, private val cornerRadius: Dp) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val rects = with(density) { runs.map { Rect(it.left.toPx(), it.top.toPx(), it.right.toPx(), it.bottom.toPx()) } }
        val corners = ConflictLaneOutline.corners(ConflictLaneOutline.strips(rects))
        val radius = with(density) { cornerRadius.toPx() }
        return Outline.Generic(roundedOutline(corners, radius))
    }

    private fun roundedOutline(corners: List<Offset>, radius: Float): Path {
        val path = Path()
        if (corners.size <= 2) return path
        val count = corners.size
        fun towards(from: Offset, to: Offset, distance: Float): Offset {
            val length = hypot(to.x - from.x, to.y - from.y)
            return if (length == 0f) from else from + (to - from) * (distance / length)
        }
        val start = (corners.last() + corners.first()) / 2f
        path.moveTo(start.x, start.y)
        for (index in 0 until count) {
            val previous = corners[(index + count - 1) % count]
            val corner = corners[index]
            val next = corners[(index + 1) % count]
            val fitted = minOf(
                radius,
                hypot(corner.x - previous.x, corner.y - previous.y) / 2f,
                hypot(next.x - corner.x, next.y - corner.y) / 2f,
            )
            val enter = towards(corner, previous, fitted)
            val leave = towards(corner, next, fitted)
            path.lineTo(enter.x, enter.y)
            // A quarter circle: every edge here is level or upright, so each corner turns by 90°.
            val c1 = enter + (corner - enter) * ARC
            val c2 = leave + (corner - leave) * ARC
            path.cubicTo(c1.x, c1.y, c2.x, c2.y, leave.x, leave.y)
        }
        path.close()
        return path
    }

    override fun equals(other: Any?): Boolean =
        other is ConflictLaneShape && other.runs == runs && other.cornerRadius == cornerRadius

    override fun hashCode(): Int = 31 * runs.hashCode() + cornerRadius.hashCode()

    private companion object {
        /** How far toward the corner a cubic's handles reach for a quarter circle. */
        const val ARC = 0.5522848f
    }
}
