package org.ntust.app.tigerduck.ui.component

import android.icu.text.BreakIterator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/**
 * A [Text] that never breaks inside a word without a hyphen. It is set whole words first; only
 * a text with a word wider than its line, "Classroom" in a narrow card at the app's largest
 * text size, is set again with hyphenation, as the More page's names are (see
 * `FeatureCardSize`). Android's breaker would otherwise hyphenate words that fit whole on the
 * next line, so a text that fits stays exactly as it was.
 *
 * The style carries the language the text is written in, which picks the hyphenation patterns:
 * below API 33 the default locale stays the device's while the app runs in another language.
 * A line cut short by [overflow] doesn't count; only where the lines before it end.
 */
@Composable
fun WholeWordsText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val locale = LocalConfiguration.current.locales[0]
    val whole = style.copy(localeList = LocaleList(Locale(locale.toLanguageTag())))
    val breaks = remember(locale) { BreakIterator.getLineInstance(locale) }
    val measurer = rememberTextMeasurer()
    var hyphenate by remember(text, whole, maxLines, overflow) { mutableStateOf(false) }
    Text(
        text = text,
        modifier = modifier,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
        style = if (hyphenate) whole.copy(hyphens = Hyphens.Auto) else whole,
        onTextLayout = { layout ->
            // Set hyphenated, the question is whether whole words would fit this width now.
            val wholeLayout = if (!hyphenate) {
                layout
            } else {
                measurer.measure(
                    text = text,
                    style = whole,
                    overflow = overflow,
                    maxLines = maxLines,
                    constraints = Constraints(maxWidth = layout.layoutInput.constraints.maxWidth),
                )
            }
            val needed = !wholeLayout.breaksBetweenWords(breaks)
            if (needed != hyphenate) hyphenate = needed
        },
    )
}

/** Whether every line but the last ends between words; [breaks] is a line instance. */
private fun TextLayoutResult.breaksBetweenWords(breaks: BreakIterator): Boolean {
    breaks.setText(layoutInput.text.text)
    return (0 until lineCount - 1).all { breaks.isBoundary(getLineEnd(it)) }
}
