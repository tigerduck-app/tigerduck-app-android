package org.ntust.app.tigerduck.ui.screen.more

import android.icu.text.BreakIterator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal val FeatureGridPadding = 16.dp
internal val FeatureCardSpacing = 12.dp
internal val FeatureCardPadding = 14.dp
internal val FeatureIconSize = 32.dp
internal const val FeatureLabelMaxLines = 2

/** The least room between the icon and a name that has made the cards taller. */
private val IconLabelGap = 8.dp
private const val CardAspectRatio = 1.6f

/** The smallest a name is set, so fitting it never undoes much of a larger font. */
private val LabelMinFontSize = 16.sp
private const val LabelFontSizeStep = 1f

/**
 * The size every card on the More screen shares.
 *
 * A card's height used to follow its width alone, which left one line under the icon for the
 * name, so a larger font or display size, or a language with long names, cut the second line
 * off. [height] is now what the tallest name needs, and never less than the 1.6:1 card it was,
 * so the grid stays even. Each name is set at its [labelFontSizes] entry in a box [labelWidth]
 * wide, exactly as it was measured here.
 */
internal class FeatureCardSize(
    val height: Dp,
    val labelWidth: Dp,
    /** Each name's size, from [fitLabel]: the style's own, or smaller where that one does not fit. */
    val labelFontSizes: Map<String, TextUnit>,
)

internal fun featureGridColumns(width: Dp): Int = when {
    width >= 840.dp -> 4
    width >= 600.dp -> 3
    else -> 2
}

@Composable
@ReadOnlyComposable
internal fun featureLabelStyle(): TextStyle = MaterialTheme.typography.titleLarge.let {
    it.copy(
        fontWeight = FontWeight.SemiBold,
        // In proportion, so a name set smaller to fit closes its lines up too.
        lineHeight = (it.lineHeight.value / it.fontSize.value).em,
    )
}

/** The [FeatureCardSize] of a grid [gridWidthPx] wide in [columns], for cards named [labels]. */
@Composable
internal fun rememberFeatureCardSize(labels: List<String>, gridWidthPx: Int, columns: Int): FeatureCardSize {
    val measurer = rememberTextMeasurer()
    val style = featureLabelStyle()
    val density = LocalDensity.current
    return remember(measurer, style, density, labels, gridWidthPx, columns) {
        with(density) {
            // Rounded as the padding and spacing modifiers round them, and floored where the row
            // shares its width out, so no card is narrower than the one measured here.
            val cellWidth = (gridWidthPx - 2 * FeatureGridPadding.roundToPx() -
                (columns - 1) * FeatureCardSpacing.roundToPx()) / columns
            val labelWidth = (cellWidth - 2 * FeatureCardPadding.roundToPx()).coerceAtLeast(0)
            val breaks = BreakIterator.getLineInstance()
            val fitted = labels.associateWith { label ->
                val layout = { fontSize: TextUnit ->
                    measurer.measure(
                        text = label,
                        style = style.copy(fontSize = fontSize),
                        overflow = TextOverflow.Ellipsis,
                        maxLines = FeatureLabelMaxLines,
                        constraints = Constraints(maxWidth = labelWidth),
                    )
                }
                val fontSize = fitLabel(style.fontSize, breaks, layout)
                fontSize to layout(fontSize).size.height
            }
            val tallestLabel = fitted.values.maxOfOrNull { (_, height) -> height } ?: 0
            val content = 2 * FeatureCardPadding.roundToPx() + FeatureIconSize.roundToPx() +
                IconLabelGap.roundToPx() + tallestLabel
            FeatureCardSize(
                height = maxOf((cellWidth / CardAspectRatio).roundToInt(), content).toDp(),
                labelWidth = labelWidth.toDp(),
                labelFontSizes = fitted.mapValues { (_, sized) -> sized.first },
            )
        }
    }
}

/**
 * The largest size from [maxFontSize] down to [LabelMinFontSize] at which [layout] fits a name in
 * its lines without breaking a word, or the smallest if none does; the name is then cut short.
 *
 * Unlike [androidx.compose.foundation.text.TextAutoSize.StepBased], this also shrinks a name that
 * would only fit by breaking inside a word, which Android does to a word too long for its line:
 * "Announcements" in a phone's card, or "Университетская" at a larger font. [breaks] is a line
 * instance, reused across every name and size.
 */
private inline fun fitLabel(
    maxFontSize: TextUnit,
    breaks: BreakIterator,
    layout: (TextUnit) -> TextLayoutResult,
): TextUnit {
    var size = maxFontSize.value
    while (size > LabelMinFontSize.value && !layout(size.sp).fitsWholeWords(breaks)) {
        size = maxOf(size - LabelFontSizeStep, LabelMinFontSize.value)
    }
    return size.sp
}

private fun TextLayoutResult.fitsWholeWords(breaks: BreakIterator): Boolean {
    if (hasVisualOverflow || isLineEllipsized(lineCount - 1)) return false
    breaks.setText(layoutInput.text.text)
    return (0 until lineCount - 1).all { breaks.isBoundary(getLineEnd(it)) }
}
