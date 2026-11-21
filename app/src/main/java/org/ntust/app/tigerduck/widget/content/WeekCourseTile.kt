package org.ntust.app.tigerduck.widget.content

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.widget.CourseBlock
import org.ntust.app.tigerduck.widget.scaled

/** A Week block in session: outlined in [accent], with a bar of it [progress] of the way along a block [width] wide. */
internal data class TileSession(val accent: Color, val progress: Float, val width: Dp)

/**
 * A course's name on its own fill, [height] high, with the 1dp above and below
 * that every cell keeps; struck through for a cancelled class, between a
 * [topLine] and a [bottomLine] where the school changed it. A block in
 * [session] is outlined, with its progress along its bottom, as iOS
 * `WeekCourseBlock`; one [spoken] is read as that.
 */
@Composable
internal fun CourseTile(
    name: String,
    fill: Color,
    textColor: Color,
    height: Dp,
    cornerRadius: Dp,
    maxLines: Int,
    courseNameScale: Float,
    struckThrough: Boolean = false,
    topLine: TileLine? = null,
    bottomLine: TileLine? = null,
    isAssessment: Boolean = false, isDark: Boolean = false,
    session: TileSession? = null,
    spoken: String? = null,
) {
    val described = spoken?.let { GlanceModifier.semantics { contentDescription = it } } ?: GlanceModifier
    val text = @Composable {
        TileText(name, textColor, maxLines, courseNameScale, struckThrough, topLine, bottomLine, isAssessment, isDark)
    }
    Box(modifier = GlanceModifier.fillMaxWidth().height(height).padding(vertical = 1.dp.scaled)) {
        if (session == null) {
            Box(
                modifier = GlanceModifier.fillMaxSize().background(ColorProvider(fill)).cornerRadius(cornerRadius)
                    .padding(2.dp.scaled).then(described),
                contentAlignment = Alignment.Center,
            ) { text() }
            return@Box
        }
        // Glance draws no border: the outline is the accent behind the inset fill.
        val inset = 1.5.dp.scaled
        Box(
            modifier = GlanceModifier.fillMaxSize().background(ColorProvider(session.accent)).cornerRadius(cornerRadius)
                .padding(inset).then(described),
        ) {
            Column(modifier = GlanceModifier.fillMaxSize().background(ColorProvider(fill)).cornerRadius(cornerRadius - inset)) {
                Box(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight().padding(2.dp.scaled),
                    contentAlignment = Alignment.Center,
                ) { text() }
                val filled = ((session.width - inset * 2).value * session.progress.coerceIn(0f, 1f)).dp
                Row(modifier = GlanceModifier.fillMaxWidth().height(3.dp.scaled)) {
                    Box(modifier = GlanceModifier.width(filled).fillMaxHeight().background(ColorProvider(session.accent))) {}
                }
            }
        }
    }
}

@Composable
private fun TileText(
    name: String,
    textColor: Color,
    maxLines: Int,
    courseNameScale: Float,
    struckThrough: Boolean,
    topLine: TileLine?,
    bottomLine: TileLine?,
    isAssessment: Boolean,
    isDark: Boolean,
) {
    val nameText = @Composable {
        WidgetCourseName(
            text = name, isAssessment = isAssessment, isDark = isDark,
            style = TextStyle(
                color = ColorProvider(textColor),
                fontSize = 10.sp.scaled * courseNameScale,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                textDecoration = if (struckThrough) TextDecoration.LineThrough else null,
            ),
            maxLines = maxLines.coerceAtLeast(1),
        )
    }
    if (topLine == null && bottomLine == null) {
        nameText()
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            topLine?.let { TileLineText(it) }
            nameText()
            bottomLine?.let { TileLineText(it) }
        }
    }
}

@Composable
private fun TileLineText(line: TileLine) {
    Text(
        text = line.text,
        style = TextStyle(
            color = ColorProvider(line.color),
            fontSize = 8.sp.scaled,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        ),
        maxLines = 1,
    )
}

/**
 * What TalkBack reads for [course]'s Week block over [periods] on [weekday]: its name, an
 * assessment, its time and its room, or that it is cancelled, as iOS `WeekCourseBlock.spoken`.
 * [change] is the block as the school changed it, [room] the timetable's.
 */
internal fun weekBlockSpoken(
    context: Context,
    course: Course,
    weekday: Int,
    periods: List<String>,
    change: CourseBlock?,
    room: String,
    online: String,
): String {
    if (periods.isEmpty()) return course.displayName
    val block = change ?: CourseBlock(course, weekday, periods)
    val time = if (block.startTime.isNotEmpty() && block.endTime.isNotEmpty()) {
        context.getString(R.string.a11y_time_range, block.startTime, block.endTime)
    } else {
        null
    }
    val where = when {
        block.isCancelled -> context.getString(R.string.course_card_cancelled)
        block.roomChanged -> block.classroom(online)
        else -> room
    }
    val assessment = if (block.isAssessment) context.getString(R.string.course_change_assessment) else null
    return listOfNotNull(course.displayName, assessment, time, where).filter { it.isNotEmpty() }.joinToString(", ")
}
