package org.ntust.app.tigerduck.ui.screen.browser

import android.app.Application
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The WebView's accessibility provider, as the browser's bars hear its scrolls. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ScrollTellingProviderTest {

    private val told = mutableListOf<String>()

    /** A WebView's provider: it records what it is asked, and does the actions [accepts] lets it. */
    private inner class WebViewProvider(private val accepts: Boolean) : AccessibilityNodeProvider() {
        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            told += "performed $action on $virtualViewId"
            return accepts
        }

        override fun findFocus(focus: Int): AccessibilityNodeInfo? {
            told += "found focus $focus"
            return null
        }
    }

    private fun provider(accepts: Boolean = true) = ScrollTellingProvider(
        WebViewProvider(accepts),
        dragged = { down -> told += "dragged down=$down" },
        refused = { told += "refused" },
    )

    @Test
    fun `an accessibility scroll tells the bars its way before the WebView scrolls`() {
        assertTrue(provider().performAction(7, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
        assertEquals(listOf("dragged down=false", "performed ${AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD} on 7"), told)
        told.clear()
        provider().performAction(7, android.R.id.accessibilityActionPageDown, null)
        assertEquals("dragged down=true", told.first())
    }

    @Test
    fun `a scroll the WebView refuses leaves no way for the page's own moves`() {
        provider(accepts = false).performAction(7, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
        assertEquals("refused", told.last())
    }

    @Test
    fun `other actions and calls go to the WebView's provider untold`() {
        val provider = provider()
        provider.performAction(7, AccessibilityNodeInfo.ACTION_CLICK, null)
        provider.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        assertEquals(
            listOf("performed ${AccessibilityNodeInfo.ACTION_CLICK} on 7", "found focus ${AccessibilityNodeInfo.FOCUS_ACCESSIBILITY}"),
            told,
        )
    }
}
