package org.ntust.app.tigerduck.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity

/**
 * Where a lifted finger leaves the chrome: past half way it goes the rest of the way, short of it
 * it goes back. The same tipping point Material's own "enterAlways" bar settles on.
 */
private const val SettleFraction = 0.5f

/**
 * Critically damped, at the stiffness the refresh pull rebounds with. No bounce, deliberately: an
 * overshoot would draw a band of background above the bar, or a gap below the search field.
 */
private val SettleSpec =
    spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

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
    private var _heightPx by mutableFloatStateOf(0f)

    /**
     * How tall the chrome measures, written by the screen.
     *
     * Setting it re-clamps [offsetPx], because the chrome changes height while it is up: an
     * auth-failed banner appears, a "local results only" note arrives, a filter row turns up with
     * its taxonomy. A bar that was *fully hidden* stays fully hidden -- without that, growing the
     * chrome would drop its new bottom band over the list unasked, on top of whatever was being
     * read. A bar that had shrunk is pulled back into range, or it would silently eat the
     * difference off the next downward scroll before anything moved.
     *
     * A bar only *part* of the way up keeps its offset, and so does show more of itself as the
     * chrome grows. That one is deliberate: it is not hidden, it is mid-gesture, and the extra
     * band belongs to the position the user's finger left it in.
     */
    var heightPx: Float
        get() = _heightPx
        // Unobserved reads: this runs inside the chrome's own measure pass, and a measure that
        // observed the offset would be invalidated by every frame of every scroll. The writes
        // still notify whoever reads these from composition.
        set(value) = Snapshot.withoutReadObservation {
            val wasFullyHidden = _heightPx > 0f && offsetPx <= -_heightPx
            _heightPx = value
            offsetPx = if (wasFullyHidden) -value else offsetPx.coerceIn(-value, 0f)
        }

    var offsetPx by mutableFloatStateOf(0f)
        private set

    /** Applies [delta] and returns how much of it was used, for the caller to report as consumed. */
    fun onScroll(delta: Float): Float {
        if (_heightPx <= 0f) return 0f
        val before = offsetPx
        offsetPx = (offsetPx + delta).coerceIn(-_heightPx, 0f)
        return offsetPx - before
    }

    /**
     * Sends the bar to whichever end it is nearer, for a finger that let go half way through
     * hiding it. Nothing but a gesture moves [offsetPx], so without this the header simply stays
     * cropped in half until the next scroll. Cancelling the call leaves the bar wherever the
     * animation had reached, which is what the next touch wants.
     *
     * Under a [MutatorMutex], so a second settle -- a fling, a quick drag, another fling, all
     * inside the first spring's third of a second -- cancels the first outright rather than two
     * animations writing this one offset on alternate frames.
     */
    suspend fun settle() {
        settleMutex.mutate {
            val target = appBarSettleTarget(offsetPx, _heightPx)
            if (offsetPx == target) return@mutate
            animate(offsetPx, target, animationSpec = SettleSpec) { value, _ ->
                offsetPx = value.coerceIn(-_heightPx, 0f)
            }
        }
    }

    private val settleMutex = MutatorMutex()
}

/** Nearer end wins. A bar of unmeasured height has nowhere to go, so it stays put. */
internal fun appBarSettleTarget(offsetPx: Float, heightPx: Float): Float = when {
    heightPx <= 0f -> offsetPx
    offsetPx <= -heightPx * SettleFraction -> -heightPx
    else -> 0f
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
 * [pinned] holds [revealPx] at [maxPx] while the field is focused or carries text, so no scroll
 * can shut the drawer under someone mid-edit.
 *
 * It holds the drawer's *height*, not the field's position on screen. A pinned [consume] returns
 * 0, and `chromeConsumption` then hands that whole upward delta to [AppBarState.onScroll]
 * instead -- so the chrome overlay, drawer and focused field and live IME included, still
 * translates up and off with the bar. The field keeps its text, its focus and its full height
 * throughout, and one downward scroll brings it straight back.
 */
@Stable
class SearchRevealState(initialMaxPx: Float = 0f) {
    private var _maxPx by mutableFloatStateOf(initialMaxPx)
    private var _pinned by mutableStateOf(false)

    var revealPx by mutableFloatStateOf(0f)
        private set

    /**
     * How far the drawer opens: the field's own measured height, written by [SearchDrawer] rather
     * than guessed at from a constant. A text field grows with the font scale while its padding
     * does not, so any fixed number is wrong for somebody -- measured, the field is whole at every
     * scale and the finger moves it one to one. A pinned drawer follows the measurement.
     */
    var maxPx: Float
        get() = _maxPx
        // Unobserved, and for the same reason as [AppBarState.heightPx]: the write arrives from
        // the drawer's own measure pass, which must not come to depend on how far it is open.
        set(value) = Snapshot.withoutReadObservation {
            _maxPx = value
            revealPx = if (_pinned) value else revealPx.coerceIn(0f, value)
        }

    /**
     * Holds the drawer open while the field is focused or carries text.
     *
     * Pinning *opens* the drawer as well as holding it, because [consume] refuses to move a pinned
     * drawer: a field that gains focus while the drawer is shut -- Tab traversal from a hardware
     * keyboard, an accessibility focus action, or a search the view model was still carrying when
     * the screen composed -- would otherwise pin itself invisible, with no gesture able to reach
     * it and a live filter on the list nobody could see or clear.
     */
    var pinned: Boolean
        get() = _pinned
        // Both screens assign this straight from their composition body, on every recomposition.
        // Observing the old value there would make each screen's restart scope depend on it, so
        // every flip -- focus in, focus out, the search text becoming empty -- would cost a second
        // whole pass over the screen body just to find the write had become a no-op.
        set(value) = Snapshot.withoutReadObservation {
            val wasPinned = _pinned
            _pinned = value
            if (value && !wasPinned) revealPx = _maxPx
        }

    /** Applies [delta] and returns how much of it was used. Positive opens, negative closes. */
    fun consume(delta: Float): Float {
        if (_pinned) return 0f
        val before = revealPx
        revealPx = (revealPx + delta).coerceIn(0f, _maxPx)
        return revealPx - before
    }

    /**
     * Opens or shuts the drawer the rest of the way, for a finger that let go part way through the
     * reveal -- about a centimetre of travel is all it takes, and a drawer left there draws a
     * horizontal slice of the field that reads as broken. Cancelling the call leaves the drawer
     * wherever the animation had reached.
     *
     * Under a [MutatorMutex], for the same reason as [AppBarState.settle]: overlapping settles
     * cancel each other rather than fighting over one value.
     */
    suspend fun settle() {
        settleMutex.mutate {
            val target = searchDrawerSettleTarget(revealPx, _maxPx, _pinned)
            if (revealPx == target) return@mutate
            try {
                animate(revealPx, target, animationSpec = SettleSpec) { value, _ ->
                    // Anything that pins mid-flight -- a tap landing on the half-open field --
                    // wins outright, and the spring is abandoned rather than merely muted. A
                    // spring left running would pick the drawer up again the moment the pin came
                    // off, from wherever its curve had reached by then, and shut a field someone
                    // had just opened by touching it.
                    if (_pinned) throw PinnedMidSettle()
                    revealPx = value.coerceIn(0f, _maxPx)
                }
            } catch (_: PinnedMidSettle) {
                // Stopping the animation is the whole of it; the pin has placed the drawer.
            }
        }
    }

    private val settleMutex = MutatorMutex()
}

/** Control flow only: [SearchRevealState.settle] throws it at itself to abandon its animation. */
private class PinnedMidSettle : RuntimeException(null, null, false, false)

/**
 * Nearer end wins, except for a pinned drawer, which is already where it belongs, and an
 * unmeasured one, which has nowhere to go.
 */
internal fun searchDrawerSettleTarget(revealPx: Float, maxPx: Float, pinned: Boolean): Float = when {
    pinned || maxPx <= 0f -> revealPx
    revealPx >= maxPx * SettleFraction -> maxPx
    else -> 0f
}

@Composable
fun rememberSearchRevealState(): SearchRevealState = remember { SearchRevealState() }

/**
 * The search field, clipped to however far the drawer is open. A height of 0 means it is not
 * there: nothing is drawn, and the clip takes it out of hit testing too, so it cannot catch a tap
 * meant for the list.
 *
 * [content] is never removed from the composition, only hidden, so the field keeps its text and
 * its IME focus however far the drawer has closed.
 *
 * It must be something that has a height of its own: it is measured with no upper bound, so a
 * `fillMaxHeight`, a `weight`, or a `LazyColumn` inside it would report a nonsense height and the
 * drawer would take that for how far it has to open. A text field, which is what this is for, is
 * exactly right.
 */
@Composable
fun SearchDrawer(state: SearchRevealState, content: @Composable () -> Unit) {
    val height = with(LocalDensity.current) { state.revealPx.toDp() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds(),
    ) {
        // `unbounded` is what measures the field at its own full height inside a box that may be
        // no height at all, so the drawer clips it rather than squashing it flat and stretching it
        // open again. Anchored to the bottom, so it slides out from under the chrome above it.
        Box(Modifier.wrapContentHeight(align = Alignment.Bottom, unbounded = true)) {
            // A layout of its own, measured under those same unbounded constraints, so its size is
            // the field's natural height -- which is exactly how far the drawer has to open.
            Box(Modifier.onSizeChanged { state.maxPx = it.height.toFloat() }) { content() }
        }
    }
}
