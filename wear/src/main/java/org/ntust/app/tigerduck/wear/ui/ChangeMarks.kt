package org.ntust.app.tigerduck.wear.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * What marks a time or room the school changed on Now and Next, on every card style, as the
 * iPhone's watch `Color.changeInk` does.
 */
val ChangeInk = Color(0xFFFDBA74)

/** Put before a moved room, as the iPhone's `arrow.left.arrow.right`. */
private const val MOVED_MARK = "⇄"

/** [room] after the moved mark when the school moved the class there; as it is otherwise. */
internal fun roomText(room: String, moved: Boolean): String = if (moved) "$MOVED_MARK $room" else room

/**
 * A Now & Next card's room line, as the iPhone's `ClassCard`: the room alone, bold, in
 * [courseInk], or marked and in [ChangeInk] when the school moved the class there (an online
 * class among them, its room "Online"). None for a class with no room.
 */
internal fun roomLine(room: String, moved: Boolean, courseInk: Color): AnnotatedString? {
    if (room.isBlank() && !moved) return null
    return buildAnnotatedString {
        withStyle(SpanStyle(color = if (moved) ChangeInk else courseInk, fontWeight = FontWeight.Bold)) {
            append(roomText(room, moved))
        }
    }
}
