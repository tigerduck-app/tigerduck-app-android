package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity

/**
 * The scrolling content of a bottom sheet, kept inside the sheet. Put [modifier] before the
 * content's `verticalScroll(scroll)`.
 *
 * A sheet takes whatever a downward drag or fling leaves over once the content reaches its top, as
 * Material3's does and [DetentSheet] does after it: a hard fling from the bottom scrolled the
 * content up in a moment and then pulled the sheet down with the rest. Here a gesture that starts
 * with the content scrolled away from its top stops at the top, and only the next one, started
 * there, moves the sheet.
 */
class SheetContentScroll(private val scroll: ScrollState) {

    /** Whether the gesture under way began with the content at its top. */
    private var startedAtTop = true

    private val connection = object : NestedScrollConnection {
        // The finger's drag only: a fling's own steps, left over at the top, stay unconsumed, so the
        // fling ends there and hands its velocity to onPostFling rather than running on unseen.
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (!startedAtTop && source == NestedScrollSource.UserInput && available.y > 0f) {
                Offset(0f, available.y)
            } else {
                Offset.Zero
            }

        // Up, left over at the bottom, too: the sheet is at its top by then (an upward fling below
        // it moves the sheet, not the content), and has nowhere to go. Material's sheet bounced
        // there, and while a sheet animates it takes the next touch outright, so a quick fling
        // back down moved the sheet and left the content where it was.
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
            if (available.y < 0f || !startedAtTop && available.y > 0f) {
                Velocity(0f, available.y)
            } else {
                Velocity.Zero
            }
    }

    val modifier: Modifier = Modifier
        .pointerInput(this) {
            awaitEachGesture {
                // Seen on the way in, before the content scrolls, and left for the content to use.
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                startedAtTop = !scroll.canScrollBackward
            }
        }
        .nestedScroll(connection)
}
