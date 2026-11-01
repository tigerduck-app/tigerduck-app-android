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

/** A card's "room · instructor" line, a moved room marked and in [ChangeInk], as the iPhone's `ClassCard`. */
internal fun classroomLine(room: String, instructor: String, moved: Boolean): AnnotatedString = buildAnnotatedString {
    if (moved) {
        withStyle(SpanStyle(color = ChangeInk, fontWeight = FontWeight.SemiBold)) { append(roomText(room, moved = true)) }
    } else {
        append(room)
    }
    append(" · $instructor")
}
