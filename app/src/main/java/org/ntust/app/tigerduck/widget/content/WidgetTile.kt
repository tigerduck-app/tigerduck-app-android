package org.ntust.app.tigerduck.widget.content

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy.CourseColorStyle
import org.ntust.app.tigerduck.ui.theme.pastel
import org.ntust.app.tigerduck.ui.theme.pastelOutline
import org.ntust.app.tigerduck.shared.Pastel
import org.ntust.app.tigerduck.widget.CourseBlock
import org.ntust.app.tigerduck.widget.WidgetColors
import org.ntust.app.tigerduck.widget.scaled
import org.ntust.app.tigerduck.widget.widgetChangeInk
import org.ntust.app.tigerduck.widget.widgetCourseColor

/**
 * A class's tile on the Today and Next Class widgets, as iOS `WidgetTile`: its [fill], opaque, the
 * name's [ink], the times' and labels' [secondaryInk], the [accent] that outlines a class in
 * session and fills its progress, over a [track], and the [changeInk] of what the school changed.
 * [onDark] is whether the fill is a dark one, for the assessment's dashes.
 */
internal data class WidgetTile(
    val fill: Color,
    val ink: Color,
    val secondaryInk: Color,
    val accent: Color,
    val track: Color,
    val changeInk: Color,
    val onDark: Boolean,
)

/**
 * [course]'s tile in the interface style, as Home's course cards draw it: under TigerDuck the
 * course's color laid over the widget, with the theme's text; under the pastel style its pastel,
 * light in either theme, with dark text. One that [stepsBack], a class over or cancelled, is
 * fainter and its name in the secondary ink, as on iOS. The fill is laid over [colors]'s
 * background, so an outline drawn behind it does not show through.
 */
internal fun widgetTile(
    course: Course,
    courseColors: Map<String, Color>,
    colors: WidgetColors,
    style: VisualStylePolicy,
    stepsBack: Boolean = false,
): WidgetTile {
    val vivid = widgetCourseColor(course, courseColors, isDark = false)
    return when (style.courseColorStyle) {
        CourseColorStyle.Pastel -> WidgetTile(
            fill = vivid.pastel(if (stepsBack) Pastel.MUTED_WHITE_SHARE else Pastel.WHITE_SHARE),
            ink = if (stepsBack) VisualStylePolicy.TextOnPastelSecondary else VisualStylePolicy.TextOnPastel,
            secondaryInk = VisualStylePolicy.TextOnPastelSecondary,
            accent = vivid.pastelOutline(),
            track = VisualStylePolicy.TextOnPastel.copy(alpha = 0.14f),
            changeInk = widgetChangeInk(isDark = false),
            onDark = false,
        )
        CourseColorStyle.Tint -> {
            val alpha = when {
                stepsBack -> if (colors.isDark) 0.40f else 0.35f
                else -> if (colors.isDark) 0.55f else 0.50f
            }
            WidgetTile(
                fill = vivid.copy(alpha = alpha).compositeOver(colors.background),
                ink = if (stepsBack) colors.onSurfaceVariant else colors.onSurface,
                secondaryInk = colors.onSurfaceVariant,
                accent = vivid,
                track = colors.onSurface.copy(alpha = 0.15f),
                changeInk = widgetChangeInk(colors.isDark),
                onDark = colors.isDark,
            )
        }
    }
}

/** How wide the outline of a class in session is. */
internal val TILE_OUTLINE = 2.dp

/**
 * A tile [radius] round in [tile]'s fill, outlined in its accent while [outlined]: Glance draws no
 * border, so the outline is the accent behind a fill inset by [TILE_OUTLINE].
 */
@Composable
internal fun ClassTile(
    tile: WidgetTile,
    outlined: Boolean,
    radius: Dp,
    modifier: GlanceModifier,
    content: @Composable () -> Unit,
) {
    if (!outlined) {
        Box(modifier = modifier.background(ColorProvider(tile.fill)).cornerRadius(radius)) { content() }
        return
    }
    val inset = TILE_OUTLINE.scaled
    Box(modifier = modifier.background(ColorProvider(tile.accent)).cornerRadius(radius).padding(inset)) {
        Box(modifier = GlanceModifier.fillMaxSize().background(ColorProvider(tile.fill)).cornerRadius(radius - inset)) {
            content()
        }
    }
}

/** How far a class in session is, [progress] of [width], in [tile]'s accent over its track, [height] high. */
@Composable
internal fun TileProgress(progress: Float, tile: WidgetTile, width: Dp, height: Dp, track: Boolean = true) {
    val filled = (width.value * progress.coerceIn(0f, 1f)).dp
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(height)
            .let { if (track) it.background(ColorProvider(tile.track)).cornerRadius(height / 2) else it },
    ) {
        Box(
            modifier = GlanceModifier.width(filled).fillMaxHeight()
                .background(ColorProvider(tile.accent)).cornerRadius(height / 2),
        ) {}
    }
}

/** The room [NameRoomLine] writes after [block]'s name, [online] for one held online: none for no room or a cancelled class. */
internal fun shownRoom(block: CourseBlock, online: String): String =
    if (block.classroom(online).isEmpty() || block.isCancelled) "" else block.roomLabel(online)

/**
 * A course name and its room on one line, as iOS `NameRoomLine`: the room is never cut, so the
 * name takes what is left and is cut there. The room is [block]'s, after `⇄` in [WidgetTile.changeInk]
 * where the school moved it or holds the class online; none for a block with no room, nor for a
 * cancelled one, whose room no one goes to.
 */
@Composable
internal fun NameRoomLine(
    block: CourseBlock,
    tile: WidgetTile,
    online: String,
    nameSize: TextUnit,
    roomSize: TextUnit,
    nameWeight: FontWeight = FontWeight.Medium,
    /** What TalkBack reads after the name, as [WidgetCourseName] takes it. */
    nameNote: String? = null,
) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Box(modifier = GlanceModifier.defaultWeight()) {
            WidgetCourseName(
                text = block.course.displayName, isAssessment = block.isAssessment, isDark = tile.onDark,
                style = TextStyle(
                    color = ColorProvider(tile.ink),
                    fontSize = nameSize,
                    fontWeight = nameWeight,
                    textDecoration = if (block.isCancelled) TextDecoration.LineThrough else null,
                ),
                maxLines = 1,
                note = nameNote,
            )
        }
        val room = shownRoom(block, online)
        if (room.isNotEmpty()) {
            Text(
                text = room,
                style = TextStyle(
                    color = ColorProvider(if (block.roomChanged) tile.changeInk else tile.ink),
                    fontSize = roomSize,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
                modifier = GlanceModifier.padding(start = 6.dp.scaled),
            )
        }
    }
}
