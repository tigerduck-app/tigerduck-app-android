package org.ntust.app.tigerduck.ui.screen.browser

import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/** The ways a page scrolls with no touch, as the browser's bars read them: true down, false up. */
class BrowserScrollInputsTest {

    @Test
    fun `keys that scroll the page tell their way, and others nothing`() {
        assertEquals(true, keyScrollsDown(KeyEvent.KEYCODE_DPAD_DOWN, shift = false))
        assertEquals(true, keyScrollsDown(KeyEvent.KEYCODE_PAGE_DOWN, shift = false))
        assertEquals(true, keyScrollsDown(KeyEvent.KEYCODE_MOVE_END, shift = false))
        assertEquals(true, keyScrollsDown(KeyEvent.KEYCODE_SPACE, shift = false))
        assertEquals(false, keyScrollsDown(KeyEvent.KEYCODE_SPACE, shift = true))
        assertEquals(false, keyScrollsDown(KeyEvent.KEYCODE_DPAD_UP, shift = false))
        assertEquals(false, keyScrollsDown(KeyEvent.KEYCODE_PAGE_UP, shift = false))
        assertEquals(false, keyScrollsDown(KeyEvent.KEYCODE_MOVE_HOME, shift = false))
        assertEquals(null, keyScrollsDown(KeyEvent.KEYCODE_A, shift = false))
        assertEquals(null, keyScrollsDown(KeyEvent.KEYCODE_ENTER, shift = false))
    }

    @Test
    fun `a mouse wheel turned toward the student scrolls the page down`() {
        // AXIS_VSCROLL is negative for the wheel turned toward the user, as the page scrolls down.
        assertEquals(true, wheelScrollsDown(-1f))
        assertEquals(false, wheelScrollsDown(1f))
        assertEquals(null, wheelScrollsDown(0f))
    }

    @Test
    fun `accessibility scrolls tell their way`() {
        assertEquals(true, accessibilityScrollsDown(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
        assertEquals(true, accessibilityScrollsDown(android.R.id.accessibilityActionScrollDown))
        assertEquals(true, accessibilityScrollsDown(android.R.id.accessibilityActionPageDown))
        assertEquals(false, accessibilityScrollsDown(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
        assertEquals(false, accessibilityScrollsDown(android.R.id.accessibilityActionScrollUp))
        assertEquals(false, accessibilityScrollsDown(android.R.id.accessibilityActionPageUp))
        assertEquals(null, accessibilityScrollsDown(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
