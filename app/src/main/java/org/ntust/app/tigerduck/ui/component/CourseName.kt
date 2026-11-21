package org.ntust.app.tigerduck.ui.component

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme

/**
 * The dashed underline of a class with an assessment, as the iPhone's `AssessmentInk`: a muted red,
 * sRGB, whatever the course's color, 4 on and 2 off.
 */
object AssessmentInk {
    const val LIGHT = 0xFFA8473F
    const val DARK = 0xFFEFAAA2

    val dash = 4.dp
    val gap = 2.dp

    /** iOS draws the system underline three times, 0.4 pt apart, for about this much. */
    val thickness = 1.8.dp

    /** How far below a line's baseline the dashes start. */
    val drop = 1.5.dp

    fun of(isDark: Boolean): Color = Color(if (isDark) DARK else LIGHT)
}

/** [AssessmentInk] for the app's theme. */
fun assessmentInk(): Color = AssessmentInk.of(TigerDuckTheme.isDarkMode)

/**
 * A course name; for a class with an assessment ([isAssessment]), a dashed line in [AssessmentInk]
 * under each of its lines, and TalkBack reads `course_change_assessment` right after it, adding
 * no other text to the screen. As the iPhone's `CourseName`.
 */
@Composable
fun CourseName(
    text: String,
    isAssessment: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    fontSize: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    textDecoration: TextDecoration? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    // Read while drawing, so a new layout redraws the dashes without composing again.
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val marked = if (isAssessment) {
        val spoken = "$text, ${stringResource(R.string.course_change_assessment)}"
        val ink = assessmentInk()
        modifier
            .semantics { contentDescription = spoken }
            .drawWithContent {
                drawContent()
                layout.value?.let { drawAssessmentDashes(it, ink) }
            }
    } else {
        modifier
    }
    Text(
        text = text,
        modifier = marked,
        color = color,
        style = style,
        fontSize = fontSize,
        textAlign = textAlign,
        textDecoration = textDecoration,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = {
            layout.value = it
            onTextLayout(it)
        },
    )
}

/** [ink]'s dashes under each line [text] shows, from the line's start to its end, within the text's width. */
internal fun DrawScope.drawAssessmentDashes(text: TextLayoutResult, ink: Color) {
    val dash = AssessmentInk.dash.toPx()
    val step = dash + AssessmentInk.gap.toPx()
    val thickness = AssessmentInk.thickness.toPx()
    for (line in 0 until text.lineCount) {
        val left = text.getLineLeft(line).coerceAtLeast(0f)
        val right = text.getLineRight(line).coerceAtMost(size.width)
        val top = text.getLineBaseline(line) + AssessmentInk.drop.toPx()
        var x = left
        while (x < right) {
            drawRect(ink, Offset(x, top), Size(minOf(dash, right - x), thickness))
            x += step
        }
    }
}
