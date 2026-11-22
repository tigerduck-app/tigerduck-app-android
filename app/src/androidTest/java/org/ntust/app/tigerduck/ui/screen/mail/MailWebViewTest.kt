package org.ntust.app.tigerduck.ui.screen.mail

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * The mail's page in a real WebView, under a scrolling column as the message screen's: the text
 * size reflows it within the screen's width and its height follows both ways, and a tap on an
 * image outside a link opens it while one inside a link stays the link's (tigerduck-app#1047).
 * Robolectric has no WebView to lay a page out, so these run on a device.
 */
@RunWith(AndroidJUnit4::class)
class MailWebViewTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val paragraph = "<p>" + "各位同學好，期末考試將於一月五日至一月九日舉行，請留意考試時間與教室。".repeat(12) + "</p>"

    private val zoom = mutableIntStateOf(100)
    private val html = mutableStateOf(paragraph)
    private val links = mutableListOf<Int>()
    private val images = mutableListOf<String>()

    private fun show(linkCount: Int = 0) {
        rule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MailWebView(
                    document = MailHtmlDocument.build(html.value, emptyMap(), allowRemoteImages = false, theme = MailHtmlTheme.LIGHT).html,
                    allowedRemoteUrls = emptySet(),
                    linkCount = linkCount,
                    backgroundColor = Color.WHITE,
                    onLink = { links += it },
                    textZoom = zoom.intValue,
                    onImage = { images += it },
                )
            }
        }
    }

    private fun webView(): WebView = rule.activity.window.decorView.webView()!!

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) {
            var held = false
            rule.runOnUiThread { held = condition() }
            if (held) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /** The WebView's height once it has held still for half a second. */
    private fun settledHeight(): Int {
        var last = -1
        var still = 0
        while (still < 10) {
            Thread.sleep(50)
            var height = 0
            rule.runOnUiThread { height = webView().height }
            still = if (height == last) still + 1 else 0
            last = height
        }
        return last
    }

    @Test
    fun theTextSizeReflowsThePageWithinItsWidthAndTheHeightFollowsBothWays() {
        show()
        waitUntil("the page") { webView().height > 100 }
        val standard = settledHeight()

        rule.runOnUiThread { zoom.intValue = 200 }
        waitUntil("a taller page") { webView().height > standard * 1.6 }
        rule.runOnUiThread {
            assertFalse("no sideways scroll", webView().canScrollHorizontally(1))
            assertEquals(rule.activity.window.decorView.width, webView().width)
        }

        rule.runOnUiThread { zoom.intValue = 50 }
        waitUntil("a shorter page") { webView().height < standard * 0.7 }
        assertTrue(settledHeight() < standard * 0.7)
    }

    @Test
    fun aShorterPageBringsTheHeightDown() {
        html.value = paragraph + paragraph
        show()
        waitUntil("the page") { webView().height > 100 }
        val long = settledHeight()

        rule.runOnUiThread { html.value = "<p>短</p>" }
        waitUntil("a shorter page") { webView().height < long / 4 }
    }

    @Test
    fun aTapOnAnImageOpensItAndOneInsideALinkStaysTheLinks() {
        val png = pngAddress()
        // The light page's inset is 16 CSS pixels, a dp each: the first image's center is at
        // (76, 56) dp, and the linked one's 200 dp below it.
        html.value = "<img src=\"$png\" width=\"120\" height=\"80\" style=\"display:block\">" +
            "<div style=\"height:120px\"></div>" +
            "<a href=\"https://link.invalid/0\"><img src=\"$png\" width=\"120\" height=\"80\" style=\"display:block\"></a>" +
            paragraph
        show(linkCount = 1)
        waitUntil("the page") { webView().height > 300 }
        settledHeight()

        tapUntil(76f, 56f) { images.isNotEmpty() }
        assertEquals(listOf(png), images)

        tapUntil(76f, 256f) { links.isNotEmpty() }
        assertEquals(listOf(0), links)
        assertEquals("the linked image opened nothing of its own", 1, images.size)
    }

    /** Taps at ([xDp], [yDp]) in the WebView until [done] holds: a first touch can be lost while a window settles. */
    private fun tapUntil(xDp: Float, yDp: Float, done: () -> Boolean) {
        repeat(5) {
            tap(xDp, yDp)
            val until = SystemClock.uptimeMillis() + 3_000
            while (SystemClock.uptimeMillis() < until) {
                var held = false
                rule.runOnUiThread { held = done() }
                if (held) return
                Thread.sleep(50)
            }
        }
        throw AssertionError("the tap at ($xDp, $yDp) did nothing")
    }

    private fun tap(xDp: Float, yDp: Float) {
        val location = IntArray(2)
        var density = 1f
        rule.runOnUiThread {
            webView().getLocationOnScreen(location)
            density = webView().resources.displayMetrics.density
        }
        val x = location[0] + xDp * density
        val y = location[1] + yDp * density
        val down = SystemClock.uptimeMillis()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun pngAddress(): String {
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun View.webView(): WebView? = when (this) {
        is WebView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).webView() }
        else -> null
    }
}
