package org.ntust.app.tigerduck.widget.content

import androidx.compose.ui.graphics.Color
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.widget.CourseBlock

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

/** The room the timetable has for [course]'s block over [periods] in the [weekday] column. */
internal fun weekBlockRoom(course: Course, weekday: Int, periods: List<String>): String =
    if (periods.isEmpty()) "" else CourseBlock(course, weekday, periods).classroom
