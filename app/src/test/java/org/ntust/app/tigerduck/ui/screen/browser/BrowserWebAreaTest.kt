package org.ntust.app.tigerduck.ui.screen.browser

import android.app.Application
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.browser.BrowserAppearance
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A WebView with nothing drawn yet fills its whole canvas with its background. Unclipped, that
 * covered the bars too: on a phone in night mode the browser opened all black but for the divider
 * above the toolbar, which draws after the page area.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h640dp-xhdpi")
class BrowserWebAreaTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** Paints its whole canvas, as a WebView does before a page's first frame. */
    private class FillsCanvas(context: Context) : View(context) {
        override fun onDraw(canvas: Canvas) = canvas.drawColor(Color.BLACK)
    }

    @Test
    fun `a page area that paints its whole canvas leaves the bars as they are`() {
        val state = BrowserPageState().apply { url = "https://moodle2.ntust.edu.tw/user/edit.php"; title = "Edit profile" }
        rule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                InAppBrowserScreen(state, BrowserAppearance.LIGHT, emptyList(), BrowserActions()) { modifier ->
                    AndroidView(factory = ::FillsCanvas, modifier = modifier)
                }
            }
        }

        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val density = rule.activity.resources.displayMetrics.density
        // The top bar's middle, and the bottom toolbar's middle.
        assertNotEquals("the top bar is painted over", Color.BLACK, image.getPixel(image.width / 2, (28 * density).toInt()))
        assertNotEquals("the toolbar is painted over", Color.BLACK, image.getPixel(image.width / 2, image.height - (20 * density).toInt()))
    }
}
