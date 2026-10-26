// Draws a DetentSheet over a red page and reads the pixels back: a see-through sheet shows the page
// through its own background and through the scrim, so a change to the page can be watched.

package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class, qualifiers = "en-w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetentSheetSeeThroughTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var seeThrough by mutableStateOf(false)
    private lateinit var state: DetentSheetState

    /** A white sheet, 300 dp of it, over a red page. */
    private fun show() {
        composeRule.setContent {
            state = rememberDetentSheetState()
            Box(Modifier.fillMaxSize().background(Color.Red)) {
                DetentSheetLayer(
                    state = state,
                    onDismissed = {},
                    containerColor = Color.White,
                    seeThrough = seeThrough,
                ) {
                    Box(Modifier.fillMaxWidth().height(300.dp))
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** The color at [y] dp down the screen, halfway across. */
    private fun colorAt(y: Float): Color {
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        val density = composeRule.density.density
        return pixels[pixels.width / 2, (y * density).toInt()]
    }

    private val inSheet = 640f - 120f
    private val aboveSheet = 100f

    @Test
    fun `a sheet that isn't see-through hides the page and dims it`() {
        show()
        assertEquals(Color.White, colorAt(inSheet))
        assertNotEquals("not dimmed", Color.Red, colorAt(aboveSheet))
    }

    @Test
    fun `a see-through sheet shows the page through its background and the scrim`() {
        show()
        seeThrough = true
        composeRule.waitForIdle()

        assertEquals(Color.Red, colorAt(inSheet))
        assertEquals(Color.Red, colorAt(aboveSheet))
    }

    @Test
    fun `it comes back when it stops being see-through`() {
        show()
        seeThrough = true
        composeRule.waitForIdle()
        seeThrough = false
        composeRule.waitForIdle()

        assertEquals(Color.White, colorAt(inSheet))
        assertNotEquals(Color.Red, colorAt(aboveSheet))
    }
}
