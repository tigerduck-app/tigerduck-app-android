package org.ntust.app.tigerduck.ui.screen.browser

import android.app.Application
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The WebView's touch events as the browser's bars read them, through Android's VelocityTracker. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BrowserTouchesTest {

    /** What was told: true for the page dragged down, toward its end. */
    private val told = mutableListOf<Boolean>()
    private var cancels = 0
    private val touches = BrowserTouches(
        drags = BrowserDragTracker(touchSlop = 8f, minFlingVelocity = 50f) { down -> told += down },
        cancelled = { cancels++ },
    )

    private fun one(action: Int, timeMs: Long, y: Float) {
        val event = MotionEvent.obtain(0L, timeMs, action, 100f, y, 0)
        touches.onTouch(event)
        event.recycle()
    }

    /** Two fingers, the first at [y0] and the second at [y1]. */
    private fun two(action: Int, timeMs: Long, y0: Float, y1: Float) {
        val properties = Array(2) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = arrayOf(y0, y1).map { y -> MotionEvent.PointerCoords().apply { x = 100f; this.y = y } }.toTypedArray()
        val event = MotionEvent.obtain(0L, timeMs, action, 2, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        touches.onTouch(event)
        event.recycle()
    }

    /** A fast drag up the screen, the page down, 32 px a frame from y 1000. */
    private fun flingUpTheScreen(): Long {
        one(MotionEvent.ACTION_DOWN, 0L, 1_000f)
        var t = 0L
        for (y in listOf(968f, 936f, 904f, 872f, 840f, 808f)) {
            t += 16
            one(MotionEvent.ACTION_MOVE, t, y)
        }
        return t
    }

    @Test
    fun `a fling released at once flings the way the finger went`() {
        val t = flingUpTheScreen()
        told.clear()
        one(MotionEvent.ACTION_UP, t + 8, 808f)
        assertEquals(listOf(true), told)
    }

    @Test
    fun `a fling released 60 ms after the last move still flings, as the WebView's does`() {
        val t = flingUpTheScreen()
        told.clear()
        one(MotionEvent.ACTION_UP, t + 60, 808f)
        assertEquals(listOf(true), told)
    }

    @Test
    fun `a finger held still past the release window flings nothing`() {
        val t = flingUpTheScreen()
        told.clear()
        one(MotionEvent.ACTION_UP, t + 120, 808f)
        assertEquals(emptyList<Boolean>(), told)
    }

    @Test
    fun `the finger left after the first one lifts gives the fling`() {
        one(MotionEvent.ACTION_DOWN, 0L, 1_000f)
        // The first finger drags up the screen, fast.
        for ((i, y) in listOf(968f, 936f, 904f).withIndex()) one(MotionEvent.ACTION_MOVE, 16L * (i + 1), y)
        two(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 64L, 904f, 600f)
        // The first lifts; the second, left alone, drags down the screen, fast.
        two(MotionEvent.ACTION_POINTER_UP, 80L, 904f, 600f)
        var t = 80L
        for (y in listOf(632f, 664f, 696f, 728f)) {
            t += 16
            val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 100f; this.y = y })
            val event = MotionEvent.obtain(0L, t, MotionEvent.ACTION_MOVE, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            touches.onTouch(event)
            event.recycle()
        }
        told.clear()
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 100f; y = 728f })
        val up = MotionEvent.obtain(0L, t + 8, MotionEvent.ACTION_UP, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        touches.onTouch(up)
        up.recycle()
        assertEquals(listOf(false), told)
    }

    @Test
    fun `a touch taken away tells the bars, and flings nothing`() {
        val t = flingUpTheScreen()
        told.clear()
        one(MotionEvent.ACTION_CANCEL, t + 8, 808f)
        assertEquals(emptyList<Boolean>(), told)
        assertTrue(cancels == 1)
    }
}
