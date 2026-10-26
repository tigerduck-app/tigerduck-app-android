package org.ntust.app.tigerduck.ui.navigation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics

/**
 * The Class Table tab's long press, which opens the class table switcher on the tab, and double
 * tap, which goes back to the class table shown before, or steps on to the next one; see
 * [org.ntust.app.tigerduck.ui.screen.classtable.ClassTableViewModel.tabDoubleTapped].
 *
 * It only watches the tab's touches, ahead of the tab itself, so a tap still switches tabs at
 * once and a tap on the tab already shown still does nothing: the double tap is recognised
 * alongside, never instead. A long press takes the rest of its touch, so letting go opens no tab
 * under the menu. A double tap counts only when the class table was showing as it began, as on
 * iOS: from another tab its first tap has just opened the class table. TalkBack offers the long
 * press as the tab's long-click action, labelled [longPressLabel].
 */
@Composable
internal fun Modifier.classTableTabGestures(
    isShowingClassTable: () -> Boolean,
    onLongPress: () -> Unit,
    onDoubleTap: () -> Unit,
    longPressLabel: String,
): Modifier {
    val showing by rememberUpdatedState(isShowingClassTable)
    val longPress by rememberUpdatedState(onLongPress)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    return this
        .semantics {
            onLongClick(label = longPressLabel) {
                longPress()
                true
            }
        }
        .pointerInput(Unit) {
            val taps = ClassTableTabTaps(viewConfiguration.doubleTapTimeoutMillis)
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                taps.down(down.uptimeMillis, showing())
                val up = try {
                    withTimeout(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation(PointerEventPass.Initial)
                    }
                } catch (_: PointerEventTimeoutCancellationException) {
                    taps.interrupted()
                    longPress()
                    consumeUntilUp()
                    return@awaitEachGesture
                }
                // Null when it moved off the tab, or something else took it.
                if (up == null) taps.interrupted() else if (taps.tapped(up.uptimeMillis)) doubleTap()
            }
        }
}

/**
 * Pairs the Class Table tab's taps into double taps: a second tap down within
 * [doubleTapTimeoutMs] of the first one's release. One counts only when the class table was
 * showing as its first tap came down.
 */
internal class ClassTableTabTaps(private val doubleTapTimeoutMs: Long) {
    /** When the last single tap was let go, or -1 with no tap to pair with. */
    private var lastTapUp = -1L
    private var isSecondTap = false
    private var startedOnClassTable = false

    /** A touch came down at [timeMs]; [showingClassTable] is whether the class table shows now. */
    fun down(timeMs: Long, showingClassTable: Boolean) {
        isSecondTap = lastTapUp >= 0 && timeMs - lastTapUp <= doubleTapTimeoutMs
        if (!isSecondTap) startedOnClassTable = showingClassTable
    }

    /** The touch was let go as a tap at [timeMs]; true when it ends a double tap that counts. */
    fun tapped(timeMs: Long): Boolean {
        if (!isSecondTap) {
            lastTapUp = timeMs
            return false
        }
        lastTapUp = -1L
        return startedOnClassTable
    }

    /** The touch became a long press, moved off the tab or was taken: no tap to pair with. */
    fun interrupted() {
        lastTapUp = -1L
    }
}

/** Takes the rest of a touch from the tab under it, so it does not take the release as a tap. */
private suspend fun AwaitPointerEventScope.consumeUntilUp() {
    do {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}
