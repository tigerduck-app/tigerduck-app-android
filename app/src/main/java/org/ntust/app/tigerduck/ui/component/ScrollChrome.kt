package org.ntust.app.tigerduck.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Where the page chrome sits: 0 fully shown, `-heightPx` fully hidden.
 *
 * "enterAlways", deliberately, not "exitUntilCollapsed": scrolling up hides the whole bar, and
 * *any* downward scroll brings it straight back, wherever the list happens to be. The user should
 * never have to travel back to the top to reach the folder chips or the compose button.
 *
 * [heightPx] is measured by the screen and written back here; until it is, [onScroll] consumes
 * nothing, so the first frame can never eat a scroll on behalf of a bar of unknown size.
 */
@Stable
class AppBarState {
    var heightPx by mutableFloatStateOf(0f)

    var offsetPx by mutableFloatStateOf(0f)
        private set

    /** Applies [delta] and returns how much of it was used, for the caller to report as consumed. */
    fun onScroll(delta: Float): Float {
        if (heightPx <= 0f) return 0f
        val before = offsetPx
        offsetPx = (offsetPx + delta).coerceIn(-heightPx, 0f)
        return offsetPx - before
    }
}

@Composable
fun rememberAppBarState(): AppBarState = remember { AppBarState() }

/**
 * How far the search drawer is open, 0..[maxPx].
 *
 * Overscroll at the very top fills this *before* any of it reaches the refresh pull, which is what
 * gives the staged gesture the iOS search drawer has: a short pull opens search, a longer one goes
 * on to arm a refresh. `TigerPullToRefresh` owns that hand-off.
 *
 * [pinned] holds the drawer open while the field is focused or carries text -- a scroll must not
 * yank the field out from under someone mid-edit.
 */
@Stable
class SearchRevealState(val maxPx: Float) {
    var revealPx by mutableFloatStateOf(0f)
        private set

    var pinned by mutableStateOf(false)

    /** Applies [delta] and returns how much of it was used. Positive opens, negative closes. */
    fun consume(delta: Float): Float {
        if (pinned) return 0f
        val before = revealPx
        revealPx = (revealPx + delta).coerceIn(0f, maxPx)
        return revealPx - before
    }
}

@Composable
fun rememberSearchRevealState(maxPx: Float): SearchRevealState =
    remember(maxPx) { SearchRevealState(maxPx) }
