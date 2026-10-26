package org.ntust.app.tigerduck.ui.screen.more

import android.icu.text.BreakIterator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal val FeatureGridPadding = 16.dp
internal val FeatureCardSpacing = 12.dp
internal val FeatureCardPadding = 14.dp
internal val FeatureIconSize = 32.dp
internal const val FeatureLabelMaxLines = 3

/** The least room between the icon and a name that has made the cards taller. */
private val IconLabelGap = 8.dp
private const val CardAspectRatio = 1.6f

/**
 * The size every card on the More screen shares.
 *
 * A card's height used to follow its width alone, which left one line under the icon for the
 * name, so a larger font or display size, or a language with long names, cut the second line
 * off. [height] is now what the tallest name needs, and never less than the 1.6:1 card it was,
 * so the grid stays even. Every name is set at the one size, in a box [labelWidth] wide, with
 * [labelStyle], exactly as it was measured here.
 */
internal class FeatureCardSize(
    val height: Dp,
    val labelWidth: Dp,
    /** The names that only fit their lines by breaking inside a word, which are hyphenated. */
    val hyphenatedLabels: Set<String>,
) {
    /**
     * [style] as [label] is set. A word too long for its line, "Announcements" in a phone's card,
     * breaks with a hyphen, as a book would, rather than wherever it runs out of room. Only such
     * a name is hyphenated: Android's breaker would otherwise split a word that fits whole on the
     * next line.
     */
    fun labelStyle(label: String, style: TextStyle): TextStyle =
        if (label in hyphenatedLabels) style.hyphenated() else style
}

private fun TextStyle.hyphenated(): TextStyle = copy(hyphens = Hyphens.Auto)

internal fun featureGridColumns(width: Dp): Int = when {
    width >= 840.dp -> 4
    width >= 600.dp -> 3
    else -> 2
}

/**
 * The names' style, in the language they are written in. That picks the hyphenation patterns,
 * and below API 33 the default locale stays the device's while the app runs in another language
 * (see `AppLanguageManager.localizedContext`).
 */
@Composable
@ReadOnlyComposable
internal fun featureLabelStyle(): TextStyle = MaterialTheme.typography.titleLarge.copy(
    fontWeight = FontWeight.SemiBold,
    localeList = LocalConfiguration.current.locales[0]?.let { LocaleList(Locale(it.toLanguageTag())) },
)

/** The [FeatureCardSize] of a grid [gridWidthPx] wide in [columns], for cards named [labels]. */
@Composable
internal fun rememberFeatureCardSize(labels: List<String>, gridWidthPx: Int, columns: Int): FeatureCardSize {
    val measurer = rememberTextMeasurer()
    val style = featureLabelStyle()
    val density = LocalDensity.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(measurer, style, density, locale, labels, gridWidthPx, columns) {
        with(density) {
            // Rounded as the padding and spacing modifiers round them, and floored where the row
            // shares its width out, so no card is narrower than the one measured here.
            val cellWidth = (gridWidthPx - 2 * FeatureGridPadding.roundToPx() -
                (columns - 1) * FeatureCardSpacing.roundToPx()) / columns
            val labelWidth = (cellWidth - 2 * FeatureCardPadding.roundToPx()).coerceAtLeast(0)
            val breaks = BreakIterator.getLineInstance(locale)
            val measure = { label: String, labelStyle: TextStyle ->
                measurer.measure(
                    text = label,
                    style = labelStyle,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = FeatureLabelMaxLines,
                    constraints = Constraints(maxWidth = labelWidth),
                )
            }
            val hyphenated = mutableSetOf<String>()
            val tallestLabel = labels.maxOfOrNull { label ->
                val whole = measure(label, style)
                val layout = if (whole.fitsWholeWords(breaks)) {
                    whole
                } else {
                    hyphenated += label
                    measure(label, style.hyphenated())
                }
                layout.size.height
            } ?: 0
            val content = 2 * FeatureCardPadding.roundToPx() + FeatureIconSize.roundToPx() +
                IconLabelGap.roundToPx() + tallestLabel
            FeatureCardSize(
                height = maxOf((cellWidth / CardAspectRatio).roundToInt(), content).toDp(),
                labelWidth = labelWidth.toDp(),
                hyphenatedLabels = hyphenated,
            )
        }
    }
}

/**
 * Whether this layout fits its name in its lines without breaking inside a word, which Android
 * does to a word too long for its line: "Announcements" in a phone's card, or "Университетская"
 * at a larger font. [breaks] is a line instance, reused across every name.
 */
private fun TextLayoutResult.fitsWholeWords(breaks: BreakIterator): Boolean {
    if (hasVisualOverflow || isLineEllipsized(lineCount - 1)) return false
    breaks.setText(layoutInput.text.text)
    return (0 until lineCount - 1).all { breaks.isBoundary(getLineEnd(it)) }
}
