package org.ntust.app.tigerduck.widget.content

import androidx.compose.runtime.Composable
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.background
import androidx.glance.layout.ContentScale
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.AssessmentInk

/**
 * A course name in a class widget. For a class with an assessment ([isAssessment]), the nearest a
 * widget comes to the app's dashed underline: a widget's text takes no underline of a color or
 * dashes of its own, so the name's background is a dashed line in [AssessmentInk] ([isDark] picks
 * which) along its bottom, as wide as the name. TalkBack reads `course_change_assessment` right
 * after the name. As the iPhone widgets' `CourseName`.
 *
 * Glance draws a background image as a box around the text, sized by the text, so the name takes
 * no size of its own here, or the dashes would take that size.
 */
@Composable
internal fun WidgetCourseName(
    text: String,
    isAssessment: Boolean,
    isDark: Boolean,
    style: TextStyle,
    maxLines: Int = Int.MAX_VALUE,
) {
    val marked = if (isAssessment) {
        val spoken = "$text, ${LocalContext.current.getString(R.string.course_change_assessment)}"
        GlanceModifier
            .background(
                ImageProvider(R.drawable.widget_assessment_dashes),
                ContentScale.FillBounds,
                ColorFilter.tint(ColorProvider(AssessmentInk.of(isDark))),
            )
            .semantics { contentDescription = spoken }
    } else {
        GlanceModifier
    }
    Text(text = text, modifier = marked, style = style, maxLines = maxLines)
}

/** `course_change_online`, which a widget writes in place of the room of a class held online. */
@Composable
internal fun onlineRoomWord(): String = LocalContext.current.getString(R.string.course_change_online)
