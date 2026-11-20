package org.ntust.app.tigerduck.widget.content

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.widget.CourseBlock
import org.ntust.app.tigerduck.widget.WidgetScale

/** A short line on a Week block besides its name, such as its changed time or room. */
internal data class TileLine(val text: String, val color: Color)

/** What a Week block says of [change] above its name and below it. */
internal data class WeekBlockLines(val top: TileLine?, val bottom: TileLine?) {
    val count: Int get() = listOfNotNull(top, bottom).size
}

/**
 * The lines a Week block adds besides its name, as iOS `WeekGridView.courseBlock`:
 * a changed time above the name in [ink]; below it the cancellation in
 * [stepBack], a moved room after `⇄` in [ink], else the timetable's [room] in
 * [textColor], the name's own. No line for an empty [room].
 */
internal fun weekBlockLines(
    change: CourseBlock?,
    room: String,
    cancelledLabel: String,
    ink: Color,
    stepBack: Color,
    textColor: Color,
): WeekBlockLines {
    if (change?.isCancelled == true) return WeekBlockLines(null, TileLine(cancelledLabel, stepBack))
    val bottom = when {
        change?.roomChanged == true -> TileLine(change.roomLabel, ink)
        room.isNotEmpty() -> TileLine(room, textColor)
        else -> null
    }
    return WeekBlockLines(top = change?.changedTimeLabel?.let { TileLine(it, ink) }, bottom = bottom)
}

/**
 * What a Week block of [length] periods says besides its name: [weekBlockLines] of its [change]
 * and timetable [room], where [fits] says whether that many lines fit the block.
 *
 * A block of one period has no room for a changed time; below its name it names the cancellation,
 * the room the school moved it to or the timetable's, as iOS does on every block. Those go on a
 * one-period block only where [fits], as does the timetable's room on a longer one; a longer
 * block's own change always shows.
 */
internal fun weekBlockLinesFor(
    change: CourseBlock?,
    room: String,
    length: Int,
    cancelledLabel: String,
    ink: Color,
    stepBack: Color,
    textColor: Color,
    fits: (lineCount: Int) -> Boolean,
): WeekBlockLines {
    val shown = if (length > 1) change else change?.copy(changedStart = null, changedEnd = null)
    val withRoom = weekBlockLines(shown, room.takeIf { change?.isCancelled != true }.orEmpty(), cancelledLabel, ink, stepBack, textColor)
    val changeOnly = if (length > 1) weekBlockLines(shown, "", cancelledLabel, ink, stepBack, textColor) else WeekBlockLines(null, null)
    return if (withRoom == changeOnly || fits(withRoom.count)) withRoom else changeOnly
}

/**
 * How many lines of course name a Week block [height] high holds beside [lineCount] lines of
 * [weekBlockLines]'s smaller text, after the padding [CourseTile] keeps; 0 where not even one
 * does. As [tileNameLines]. The block caps its name at this, so a long name that wraps never
 * pushes the room out of the block.
 */
internal fun blockNameLines(height: Dp, lineCount: Int, courseNameScale: Float, fontScale: Float, scale: WidgetScale = WidgetScale()): Int {
    val textHeight = height.value - (2 * 1f + 2 * 2f) * scale.ui
    val nameLine = 10f * courseNameScale * fontScale * scale.text * 1.45f
    val extraLine = 8f * fontScale * scale.text * 1.45f
    return ((textHeight - lineCount * extraLine) / nameLine).toInt().coerceAtLeast(0)
}

/** The room the timetable has for [course]'s block over [periods] in the [weekday] column. */
internal fun weekBlockRoom(course: Course, weekday: Int, periods: List<String>): String =
    if (periods.isEmpty()) "" else CourseBlock(course, weekday, periods).classroom
