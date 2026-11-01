package org.ntust.app.tigerduck.ui.screen.browser

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import kotlin.math.abs

/**
 * Which way the student's finger moves the page, for [BrowserChromeCollapse.dragged]. A touch
 * drags the page once it has gone [touchSlop] from where it started; from then on each move is
 * told, a turn back of any length included. A lift that flings the page, at [minFlingVelocity] or
 * more, is told the fling's way: a fling ending in a short turn back goes the way the finger went,
 * as the WebView flings it. With more than one finger it follows their middle, and a finger
 * landing or lifting moves nothing.
 */
internal class BrowserDragTracker(
    private val touchSlop: Float,
    /** In px a second. */
    private val minFlingVelocity: Float,
    private val dragged: (down: Boolean) -> Unit,
) {
    /** Where the touch started, for the slop. */
    private var start = 0f

    /** Where the touch was at its last move. */
    private var last = 0f

    /** Whether the touch has gone past the slop, and so drags the page. */
    private var dragging = false

    /** A first finger landed at [y]. */
    fun down(y: Float) {
        start = y
        last = y
        dragging = false
    }

    /** A finger landed or lifted; [y] is where the fingers on the page are now. */
    fun pointersChanged(y: Float) {
        last = y
        if (!dragging) start = y
    }

    /** The fingers moved to [y]. */
    fun moved(y: Float) {
        if (!dragging && abs(y - start) >= touchSlop) dragging = true
        // A finger going up moves the page down.
        if (dragging && y != last) dragged(y < last)
        last = y
    }

    /**
     * The last finger lifted, at [velocity] px a second down the screen: Android's VelocityTracker
     * on [onScreen] copies of the touch, which weighs its batched moves as the WebView's fling does.
     */
    fun up(velocity: Float) {
        if (dragging && abs(velocity) >= minFlingVelocity) dragged(velocity < 0)
        dragging = false
    }

    /** The touch was taken away. */
    fun cancel() {
        dragging = false
    }
}

/**
 * Where the touch is, for [BrowserDragTracker]: the fingers' middle, leaving out one lifting, on
 * screen, since the WebView itself moves while the bars resize.
 */
internal fun MotionEvent.focalScreenY(): Float {
    val lifting = if (actionMasked == MotionEvent.ACTION_POINTER_UP) actionIndex else -1
    var sum = 0f
    var count = 0
    for (i in 0 until pointerCount) {
        if (i == lifting) continue
        sum += getRawY(i)
        count++
    }
    return if (count == 0) rawY else sum / count
}

/**
 * A copy of this event in screen coordinates, its batched history included, for a VelocityTracker:
 * the WebView moves on screen while the bars resize. The caller recycles it.
 */
internal fun MotionEvent.onScreen(): MotionEvent =
    MotionEvent.obtain(this).also { it.offsetLocation(rawX - x, rawY - y) }

/**
 * Reads the WebView's touch events for [drags], and tells [cancelled] when the touch is taken
 * away, as by a parent that takes it over. The release velocity comes from Android's
 * VelocityTracker, fed [onScreen] copies with their batched moves; it is read at each move, of
 * the finger that move is led by, and a lift within [RELEASE_MS] of the last move flings at it, as
 * the WebView's gesture detector does: Android's own tracker gives none for a lift 40 ms after the
 * last move, and the lift repeats the last move's place, so it is not fed. A finger landing or
 * lifting starts the velocity afresh.
 */
internal class BrowserTouches(
    private val drags: BrowserDragTracker,
    private val cancelled: () -> Unit,
) {
    /** The touch's velocity, on screen; null between touches. */
    private var velocity: VelocityTracker? = null

    /** The velocity at the last move, px a second down the screen, and when that move was; null since a finger landed or lifted. */
    private var lastMove: Pair<Float, Long>? = null

    fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                lastMove = null
                feed(event)
                drags.down(event.focalScreenY())
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                feed(event)
                lastMove = null
                drags.pointersChanged(event.focalScreenY())
            }
            MotionEvent.ACTION_MOVE -> {
                feed(event)
                velocity?.let { tracker ->
                    tracker.computeCurrentVelocity(1_000)
                    lastMove = tracker.getYVelocity(event.getPointerId(0)) to event.eventTime
                }
                drags.moved(event.focalScreenY())
            }
            MotionEvent.ACTION_UP -> {
                val move = lastMove
                drags.up(if (move != null && event.eventTime - move.second <= RELEASE_MS) move.first else 0f)
                end()
            }
            MotionEvent.ACTION_CANCEL -> {
                drags.cancel()
                cancelled()
                end()
            }
        }
    }

    private fun feed(event: MotionEvent) {
        val onScreen = event.onScreen()
        velocity?.addMovement(onScreen)
        onScreen.recycle()
    }

    private fun end() {
        velocity?.recycle()
        velocity = null
        lastMove = null
    }

    companion object {
        /** How long after the last move a lift still flings, in ms, as Chromium's gesture detector. */
        const val RELEASE_MS = 80L
    }
}

/**
 * Which way [keyCode] scrolls the page, for [BrowserChromeCollapse.dragged]: true down, false up,
 * null for a key that doesn't. A key, like a mouse wheel and an accessibility scroll, moves the
 * page with no touch, which the bars would otherwise take for the page's own move.
 */
internal fun keyScrollsDown(keyCode: Int, shift: Boolean): Boolean? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MOVE_END -> true
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MOVE_HOME -> false
    KeyEvent.KEYCODE_SPACE -> !shift
    else -> null
}

/** Which way a mouse wheel's [vscroll] (`AXIS_VSCROLL`) scrolls the page: true down, null for none. */
internal fun wheelScrollsDown(vscroll: Float): Boolean? = if (vscroll == 0f) null else vscroll < 0

/** Which way an accessibility [action] scrolls the page: true down, null for one that doesn't scroll. */
internal fun accessibilityScrollsDown(action: Int): Boolean? = when (action) {
    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
    android.R.id.accessibilityActionScrollDown,
    android.R.id.accessibilityActionPageDown -> true
    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
    android.R.id.accessibilityActionScrollUp,
    android.R.id.accessibilityActionPageUp -> false
    else -> null
}

/**
 * The WebView's accessibility node provider, which accessibility scrolls (TalkBack's among them)
 * go through rather than the view's own performAccessibilityAction: a scroll tells [dragged] its
 * way before the WebView does it, and one the WebView refuses tells [refused], so that the way it
 * named is not left for the page's own moves to follow.
 */
internal class ScrollTellingProvider(
    val delegate: AccessibilityNodeProvider,
    private val dragged: (down: Boolean) -> Unit,
    private val refused: () -> Unit,
) : AccessibilityNodeProvider() {
    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? =
        delegate.createAccessibilityNodeInfo(virtualViewId)

    override fun findAccessibilityNodeInfosByText(text: String?, virtualViewId: Int): MutableList<AccessibilityNodeInfo>? =
        delegate.findAccessibilityNodeInfosByText(text, virtualViewId)

    override fun findFocus(focus: Int): AccessibilityNodeInfo? = delegate.findFocus(focus)

    override fun addExtraDataToAccessibilityNodeInfo(virtualViewId: Int, info: AccessibilityNodeInfo, extraDataKey: String, arguments: Bundle?) =
        delegate.addExtraDataToAccessibilityNodeInfo(virtualViewId, info, extraDataKey, arguments)

    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        val down = accessibilityScrollsDown(action) ?: return delegate.performAction(virtualViewId, action, arguments)
        dragged(down)
        return delegate.performAction(virtualViewId, action, arguments).also { done -> if (!done) refused() }
    }
}
