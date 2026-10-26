package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The corner mark of a class table cell: a book when the course has work
 * outstanding, the book struck through when its assignments are ignored, or
 * nothing. The two never meet, since an ignored course has nothing
 * outstanding. Silent for TalkBack: the cell's own label says it.
 */
@Composable
internal fun CourseCellBadge(
    hasAssignment: Boolean,
    assignmentsIgnored: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    if (!hasAssignment && !assignmentsIgnored) return
    Icon(
        imageVector = Icons.Filled.Book,
        contentDescription = null,
        modifier = if (assignmentsIgnored) modifier.struckThrough(tint) else modifier,
        tint = tint,
    )
}

/**
 * A slash over the content, from top left to bottom right, with a cleared
 * band along it so the slash stays apart from the glyph under it. Material
 * Icons has no slashed book.
 */
private fun Modifier.struckThrough(color: Color): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val start = Offset(size.width * 0.12f, size.height * 0.12f)
        val end = Offset(size.width * 0.88f, size.height * 0.88f)
        val stroke = size.minDimension * 0.11f
        drawLine(
            color = Color.Black,
            start = start,
            end = end,
            strokeWidth = stroke * 2.5f,
            cap = StrokeCap.Round,
            blendMode = BlendMode.Clear,
        )
        drawLine(color = color, start = start, end = end, strokeWidth = stroke, cap = StrokeCap.Round)
    }
