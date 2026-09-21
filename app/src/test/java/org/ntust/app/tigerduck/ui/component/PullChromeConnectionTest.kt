@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [PullChromeConnection]'s own rules, on the JVM.
 *
 * Nothing in here needs a device: the connection was hoisted out of [TigerPullToRefresh]'s
 * composition precisely so that it could be built from a plain constructor, and [Animatable],
 * [AppBarState] and [SearchRevealState] are all pure Kotlin. The one thing that genuinely needed
 * a `Context` -- the threshold haptic -- is a lambda, so here it is a counter.
 *
 * The scope handed to the connection is the test's own, which runs on a `StandardTestDispatcher`.
 * That is deliberate: `onPreScroll` launches its `snapTo` and then reads `dragY.value` again in
 * the same call, and on a standard dispatcher the launch is queued rather than run eagerly --
 * which is exactly what the Android UI dispatcher does. An unconfined dispatcher would resume the
 * `snapTo` mid-call and quietly measure something the app never does.
 */
class PullChromeConnectionTest {

    private class Rig(
        scope: CoroutineScope,
        val appBar: AppBarState? = null,
        val drawer: SearchRevealState? = null,
        val thresholdPx: Float = 100f,
        val maxPx: Float = 200f,
    ) {
        val dragY = Animatable(0f)
        val fingerDown = mutableStateOf(false)
        val isUserPulling = mutableStateOf(false)
        val releaseHandledByFling = mutableStateOf(false)
        var refreshing = false
        var refreshCalls = 0
        var hapticCalls = 0

        val connection = PullChromeConnection(
            dragY = dragY,
            fingerDown = fingerDown,
            isUserPulling = isUserPulling,
            releaseHandledByFling = releaseHandledByFling,
            thresholdPx = thresholdPx,
            maxPx = maxPx,
            appBar = appBar,
            searchReveal = drawer,
            scope = scope,
            isRefreshing = { refreshing },
            onRefresh = { refreshCalls++ },
            onThresholdHaptic = { hapticCalls++ },
        )

        fun pull(delta: Float, source: NestedScrollSource = NestedScrollSource.UserInput): Offset =
            connection.onPostScroll(Offset.Zero, Offset(0f, delta), source)

        fun scroll(delta: Float, source: NestedScrollSource = NestedScrollSource.UserInput): Offset =
            connection.onPreScroll(Offset(0f, delta), source)

        /** What the list reports after its own turn: [consumed] it moved, [available] it could not. */
        fun afterList(
            consumed: Float,
            available: Float = 0f,
            source: NestedScrollSource = NestedScrollSource.UserInput,
        ): Offset = connection.onPostScroll(Offset(0f, consumed), Offset(0f, available), source)
    }

    private fun bar(height: Float = 200f) = AppBarState().apply { heightPx = height }

    private fun drawer(maxPx: Float = 56f, open: Float = 0f) =
        SearchRevealState(maxPx).apply { consume(open) }

    // --- the hang fix ------------------------------------------------------------------------

    @Test
    fun `the pull grows only while a finger is actually down`() = runTest {
        // This is the whole of the refresh hang: dragY used to be reset in exactly one place --
        // onPreFling -- so anything that grew it without ending in a fling (a bring-into-view
        // under a focused search field, say) left the content translated down with nothing to
        // bring it back. Requiring a finger closes that class, and this is the only thing that
        // pins the requirement.
        val rig = Rig(this)

        assertEquals(Offset.Zero, rig.pull(50f))
        advanceUntilIdle()
        assertEquals(0f, rig.dragY.value, 0.01f)
        assertFalse(rig.isUserPulling.value)

        rig.fingerDown.value = true
        assertEquals(50f, rig.pull(50f).y, 0.01f)
        advanceUntilIdle()
        assertEquals(50f, rig.dragY.value, 0.01f)
        assertTrue(rig.isUserPulling.value)
    }

    @Test
    fun `a fingerless scroll leaves the whole delta to the list`() = runTest {
        // The return value matters as much as dragY: reporting the delta consumed while refusing
        // to act on it would stall the list instead of the chrome.
        val rig = Rig(this, drawer = drawer())
        assertEquals(Offset.Zero, rig.pull(80f))
        advanceUntilIdle()
        assertEquals(0f, rig.drawer!!.revealPx, 0.01f)
    }

    // --- the source gate ---------------------------------------------------------------------

    @Test
    fun `only a user's own scroll reaches the pull, in either direction`() = runTest {
        val appBar = bar()
        val rig = Rig(this, appBar = appBar)
        rig.fingerDown.value = true

        // Downward, during a fling: nothing arms.
        assertEquals(Offset.Zero, rig.pull(60f, NestedScrollSource.SideEffect))
        advanceUntilIdle()
        assertEquals(0f, rig.dragY.value, 0.01f)

        // Upward, during a fling: the pull is left alone -- but the bar deliberately still
        // follows the list, which is what keeps the header with content that is still travelling.
        rig.dragY.snapTo(80f)
        val used = rig.scroll(-30f, NestedScrollSource.SideEffect)
        rig.afterList(consumed = -30f, source = NestedScrollSource.SideEffect)
        advanceUntilIdle()
        assertEquals(80f, rig.dragY.value, 0.01f)
        assertEquals(0f, used.y, 0.01f)
        assertEquals(-30f, appBar.offsetPx, 0.01f)
    }

    // --- the unwind order --------------------------------------------------------------------

    @Test
    fun `an upward scroll unwinds the pull, then the drawer, then moves the list and bar together`() = runTest {
        // LIFO: the pull is the last thing the finger raised, so it is the first thing given
        // back. Draining the drawer first would make the gesture irreversible -- the field would
        // shut while the content still hung below the bar.
        val appBar = bar()
        val open = drawer(open = 56f)
        val rig = Rig(this, appBar = appBar, drawer = open)
        rig.fingerDown.value = true
        rig.dragY.snapTo(40f)

        val used = rig.scroll(-120f)
        advanceUntilIdle()

        assertEquals(-96f, used.y, 0.01f)
        assertEquals(0f, rig.dragY.value, 0.01f)     // the first 40
        assertEquals(0f, open.revealPx, 0.01f)       // the next 56
        assertEquals(0f, appBar.offsetPx, 0.01f)     // the bar waits for the list

        rig.afterList(consumed = -24f)               // which scrolls the last 24
        assertEquals(-24f, appBar.offsetPx, 0.01f)   // and the bar goes with it
    }

    // --- the bar follows the content, and only the content ---------------------------------

    @Test
    fun `an upward drag on a page that cannot scroll leaves the bar put`() = runTest {
        // An empty page: the finger travels, the list has nothing to move, so neither does the bar.
        val appBar = bar()
        val rig = Rig(this, appBar = appBar)
        rig.fingerDown.value = true

        assertEquals(0f, rig.scroll(-80f).y, 0.01f)
        rig.afterList(consumed = 0f, available = -80f)
        advanceUntilIdle()

        assertEquals(0f, appBar.offsetPx, 0.01f)
    }

    @Test
    fun `at the end of the list the bar stops when the content does`() = runTest {
        // Ten pixels of list left and a fifty pixel drag: the bar goes ten and no further.
        val appBar = bar()
        val rig = Rig(this, appBar = appBar)
        rig.fingerDown.value = true

        rig.scroll(-50f)
        rig.afterList(consumed = -10f, available = -40f)
        rig.scroll(-50f)
        rig.afterList(consumed = 0f, available = -50f)
        advanceUntilIdle()

        assertEquals(-10f, appBar.offsetPx, 0.01f)
    }

    @Test
    fun `a short upward scroll comes out of the pull alone`() = runTest {
        val appBar = bar()
        val open = drawer(open = 56f)
        val rig = Rig(this, appBar = appBar, drawer = open)
        rig.fingerDown.value = true
        rig.dragY.snapTo(40f)

        val used = rig.scroll(-20f)
        advanceUntilIdle()

        assertEquals(-20f, used.y, 0.01f)
        assertEquals(20f, rig.dragY.value, 0.01f)
        assertEquals(56f, open.revealPx, 0.01f)      // untouched
        assertEquals(0f, appBar.offsetPx, 0.01f)     // untouched
    }

    // --- the consumption invariant -----------------------------------------------------------

    @Test
    fun `onPreScroll never reports more than it was offered, whatever the state`() = runTest {
        // The contract the list rests on: what comes back is a share of what was offered, with
        // the same sign, so the caller can report it consumed and every unclaimed pixel still
        // reaches the content. Over-reporting stalls the list; a sign flip scrolls it backwards.
        for (available in listOf(-500f, -120f, -37f, -1f, 0f, 1f, 60f, 500f)) {
            for (drag in listOf(0f, 25f, 140f)) {
                for (open in listOf(0f, 30f, 56f)) {
                    for (finger in listOf(true, false)) {
                        for (source in listOf(NestedScrollSource.UserInput, NestedScrollSource.SideEffect)) {
                            val rig = Rig(this, appBar = bar(), drawer = drawer(open = open))
                            rig.fingerDown.value = finger
                            rig.dragY.snapTo(drag)

                            val used = rig.scroll(available, source)
                            advanceUntilIdle()

                            val where = "available=$available drag=$drag open=$open finger=$finger source=$source"
                            assertEquals(where, 0f, used.x, 0f)
                            assertTrue(where, abs(used.y) <= abs(available) + 0.01f)
                            assertTrue(where, used.y == 0f || (used.y > 0f) == (available > 0f))
                        }
                    }
                }
            }
        }
    }

    // --- the drawer-first split ----------------------------------------------------------------

    @Test
    fun `at the top the drawer fills before any of it arms the refresh`() = runTest {
        // The staged gesture: a short pull opens search, a longer one goes on to arm a refresh.
        val open = drawer()
        val rig = Rig(this, drawer = open)
        rig.fingerDown.value = true

        assertEquals(40f, rig.pull(40f).y, 0.01f)
        advanceUntilIdle()
        assertEquals(40f, open.revealPx, 0.01f)
        assertEquals(0f, rig.dragY.value, 0.01f)

        assertEquals(40f, rig.pull(40f).y, 0.01f)
        advanceUntilIdle()
        assertEquals(56f, open.revealPx, 0.01f)      // the drawer takes the 16 it had left
        assertEquals(24f, rig.dragY.value, 0.01f)    // and the rest goes to the pull
    }

    @Test
    fun `the whole downward delta is reported consumed, drawer or no drawer`() = runTest {
        // onPostScroll reports available.y whatever the split was: the list is already at the
        // top, so nothing below has any use for it.
        val rig = Rig(this)
        rig.fingerDown.value = true
        assertEquals(70f, rig.pull(70f).y, 0.01f)
    }

    // --- the threshold haptic ------------------------------------------------------------------

    @Test
    fun `crossing the threshold buzzes once, and can be re-armed by letting it back up`() = runTest {
        val rig = Rig(this)
        rig.fingerDown.value = true

        rig.pull(120f)
        advanceUntilIdle()
        assertTrue(rig.connection.crossedThreshold)
        assertEquals(1, rig.hapticCalls)

        rig.pull(10f)
        advanceUntilIdle()
        assertEquals(1, rig.hapticCalls)

        rig.scroll(-rig.dragY.value)
        advanceUntilIdle()
        assertFalse(rig.connection.crossedThreshold)
        assertEquals(0f, rig.dragY.value, 0.01f)

        rig.pull(120f)
        advanceUntilIdle()
        assertEquals(2, rig.hapticCalls)
    }
}
