package org.ntust.app.tigerduck.ui.screen.browser

import android.app.Application
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Where a touch is for the browser's drag tracking: on screen, at the fingers' middle. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BrowserDragFocalTest {

    @Test
    fun `a touch is read on screen, so the WebView moving under a still finger is no move`() {
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 100f, 1_000f, 0)
        // The top bar shrank and the WebView moved up 30 px: the same finger is 30 px lower in its frame.
        event.offsetLocation(0f, 30f)
        assertEquals(1_000f, event.focalScreenY(), 0f)
        event.recycle()
    }

    @Test
    fun `two fingers are read at their middle, leaving out one lifting`() {
        val moving = twoFingers(MotionEvent.ACTION_MOVE)
        assertEquals(900f, moving.focalScreenY(), 0f)
        moving.recycle()
        val lifting = twoFingers(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        assertEquals(1_000f, lifting.focalScreenY(), 0f)
        lifting.recycle()
    }

    @Test
    fun `the copy for the fling's velocity is on screen, its batched history too`() {
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 100f, 900f, 0)
        event.addBatch(16L, 100f, 950f, 1f, 1f, 0)
        event.addBatch(32L, 100f, 1_000f, 1f, 1f, 0)
        // The WebView moved up 30 px on screen while the finger went down it.
        event.offsetLocation(0f, 30f)
        val copy = event.onScreen()
        assertEquals(1_000f, copy.y, 0f)
        assertEquals(2, copy.historySize)
        assertEquals(900f, copy.getHistoricalY(0), 0f)
        assertEquals(950f, copy.getHistoricalY(1), 0f)
        copy.recycle()
        event.recycle()
    }

    /** Two fingers, at y 1000 and 800. */
    private fun twoFingers(action: Int): MotionEvent {
        val properties = Array(2) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = arrayOf(1_000f, 800f).map { y -> MotionEvent.PointerCoords().apply { x = 100f; this.y = y } }.toTypedArray()
        return MotionEvent.obtain(0L, 0L, action, 2, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }
}
