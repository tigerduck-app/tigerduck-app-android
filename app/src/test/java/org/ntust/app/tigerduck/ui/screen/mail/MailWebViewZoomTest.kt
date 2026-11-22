package org.ntust.app.tigerduck.ui.screen.mail

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The mail's text size, which the WebView applies, and no zoom of its own (tigerduck-app#1047). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MailWebViewZoomTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the text size is the WebView's text zoom, and follows a new size`() {
        val zoom = mutableIntStateOf(100)
        composeRule.setContent {
            MailWebView("<p>hi</p>", emptySet(), linkCount = 0, backgroundColor = 0, onLink = {}, textZoom = zoom.intValue)
        }
        val webView = composeRule.activity.window.decorView.webView()!!
        composeRule.runOnIdle { assertEquals(100, webView.settings.textZoom) }

        composeRule.runOnUiThread { zoom.intValue = 170 }
        composeRule.runOnIdle { assertEquals(170, webView.settings.textZoom) }
    }

    @Test
    fun `the mail cannot be pinched or double-tapped into a zoom`() {
        composeRule.setContent { MailWebView("<p>hi</p>", emptySet(), linkCount = 0, backgroundColor = 0, onLink = {}) }
        val settings = composeRule.activity.window.decorView.webView()!!.settings
        composeRule.runOnIdle {
            assertFalse(settings.supportZoom())
            assertFalse(settings.builtInZoomControls)
        }
    }

    @Test
    fun `a tap opens an image only outside a link`() {
        val png = "data:image/png;base64,iVBORw0KGgo="
        assertEquals(png, tappedImage(WebView.HitTestResult.IMAGE_TYPE, png))
        assertEquals("https://a.example/p.png", tappedImage(WebView.HitTestResult.IMAGE_TYPE, "https://a.example/p.png"))
        // An image inside a link is the link's: its tap goes to the link's check.
        assertNull(tappedImage(WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE, png))
        assertNull(tappedImage(WebView.HitTestResult.SRC_ANCHOR_TYPE, "https://link.invalid/0"))
        assertNull(tappedImage(WebView.HitTestResult.UNKNOWN_TYPE, null))
        assertNull(tappedImage(WebView.HitTestResult.IMAGE_TYPE, ""))
    }

    private fun View.webView(): WebView? = when (this) {
        is WebView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).webView() }
        else -> null
    }
}
