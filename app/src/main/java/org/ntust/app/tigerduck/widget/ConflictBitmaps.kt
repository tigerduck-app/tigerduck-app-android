package org.ntust.app.tigerduck.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.ntust.app.tigerduck.ui.component.ConflictLOrientation
import org.ntust.app.tigerduck.ui.component.ConflictLShape

/**
 * Resolved solo fractions + sharp-corner flags for a conflict cluster. Computed
 * once, used to draw both halves.
 */
private data class ConflictGeometry(
    val soloAboveA: Float,
    val soloBelowA: Float,
    val soloAboveB: Float,
    val soloBelowB: Float,
    val sharpTop: Boolean,
    val sharpBottom: Boolean,
) {
    companion object {
        fun from(cell: ScheduleCell.Conflict): ConflictGeometry {
            val overlapStart = maxOf(cell.offsetA, cell.offsetB)
            val overlapEnd = minOf(cell.offsetA + cell.spanA, cell.offsetB + cell.spanB)
            fun soloAbove(offset: Int, span: Int) =
                (overlapStart - offset).coerceAtLeast(0).toFloat() / span
            fun soloBelow(offset: Int, span: Int) =
                (offset + span - overlapEnd).coerceAtLeast(0).toFloat() / span
            val aAbove = soloAbove(cell.offsetA, cell.spanA)
            val aBelow = soloBelow(cell.offsetA, cell.spanA)
            val bAbove = soloAbove(cell.offsetB, cell.spanB)
            val bBelow = soloBelow(cell.offsetB, cell.spanB)
            return ConflictGeometry(
                soloAboveA = aAbove,
                soloBelowA = aBelow,
                soloAboveB = bAbove,
                soloBelowB = bBelow,
                sharpTop = aAbove == 0f && bAbove == 0f,
                sharpBottom = aBelow == 0f && bBelow == 0f,
            )
        }
    }
}

/**
 * Renders one of the two interlocking L-shapes as a bitmap sized to the full
 * conflict cluster (so both bitmaps share coordinates and can be stacked in a
 * Glance Box with fillMaxSize). The shape itself fills only the course's own
 * sub-region; the rest is transparent.
 *
 * Uses the app's [ConflictLShape] path code unchanged, guaranteeing pixel-for-
 * pixel parity between the widget's conflict rendering and the in-app grid.
 */
internal fun renderConflictLayer(
    clusterWidthPx: Int,
    clusterHeightPx: Int,
    densityFactor: Float,
    cell: ScheduleCell.Conflict,
    course: LayerCourse,
    fillColor: Color,
): Bitmap {
    val bitmap = Bitmap.createBitmap(
        clusterWidthPx.coerceAtLeast(1),
        clusterHeightPx.coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(bitmap)
    val paint = Paint().apply {
        color = fillColor.toArgb()
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    val geom = ConflictGeometry.from(cell)
    val rowPx = clusterHeightPx.toFloat() / cell.combinedSpan

    val (orientation, soloAbove, soloBelow, offset, span) = when (course) {
        LayerCourse.A -> LayerParams(
            ConflictLOrientation.TopBarRightTail,
            geom.soloAboveA, geom.soloBelowA, cell.offsetA, cell.spanA,
        )
        LayerCourse.B -> LayerParams(
            ConflictLOrientation.LeftTailBottomBar,
            geom.soloAboveB, geom.soloBelowB, cell.offsetB, cell.spanB,
        )
    }

    val shape = ConflictLShape(
        orientation = orientation,
        soloAboveFraction = soloAbove,
        soloBelowFraction = soloBelow,
        sharpTopOuter = geom.sharpTop,
        sharpBottomOuter = geom.sharpBottom,
    )

    val density = object : Density {
        override val density: Float = densityFactor
        override val fontScale: Float = 1f
    }

    val courseBoxHeightPx = rowPx * span
    val courseBoxTopPx = rowPx * offset
    val size = Size(clusterWidthPx.toFloat(), courseBoxHeightPx)
    val outline = shape.createOutline(size, LayoutDirection.Ltr, density) as Outline.Generic

    canvas.save()
    canvas.translate(0f, courseBoxTopPx)
    canvas.drawPath(outline.path.asAndroidPath(), paint)
    canvas.restore()

    return bitmap
}

internal enum class LayerCourse { A, B }

private data class LayerParams(
    val orientation: ConflictLOrientation,
    val soloAbove: Float,
    val soloBelow: Float,
    val offset: Int,
    val span: Int,
)
