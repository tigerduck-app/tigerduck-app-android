// Runs OffscreenCapture the way the class table export does and reads the
// image it hands back. The capture box reports no size to the screen and sits
// left of the window, while the layer records inside it at the card's own
// measured size; this pins that the image is the card — its size at the
// export's fixed 3x, and what it drew — so moving the drawWithContent ahead
// of the layout modifier, where it would record a 0 x 0 area, fails here.

package org.ntust.app.tigerduck.ui.screen.classtable

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
// Real drawing, so the recorded layer turns into actual pixels.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OffscreenCaptureTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the captured image is the card at the export density, not the empty box around it`() {
        var captured: ImageBitmap? = null
        var failed = false
        composeRule.setContent {
            // A screen around it, as on the class table: the capture itself
            // takes up no room, and a window with none has nothing to draw.
            Box(Modifier.fillMaxSize()) {
                OffscreenCapture(
                    onCaptured = { captured = it },
                    onFailed = { failed = true },
                ) {
                    Box(Modifier.size(width = 40.dp, height = 20.dp).background(Color.Red))
                }
            }
        }
        // Robolectric draws a window only when asked to. On a phone the
        // first frame does this, and the capture's own wait takes over.
        composeRule.onRoot().captureToImage()
        composeRule.waitUntil(timeoutMillis = 5_000) { captured != null || failed }

        assertFalse("capture reported a failure", failed)
        val image = checkNotNull(captured)
        // 40 x 20 dp at the fixed 3x, whatever the test device's own density.
        assertEquals(120, image.width)
        assertEquals(60, image.height)
        assertEquals(Color.Red, image.toPixelMap()[60, 30])
    }
}
