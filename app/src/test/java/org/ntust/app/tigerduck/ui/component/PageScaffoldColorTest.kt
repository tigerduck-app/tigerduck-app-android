package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The colors a page gives the shell reach the bar and the page behind it, read back from pixels. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageScaffoldColorTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val density get() = composeRule.activity.resources.displayMetrics.density

    @Test
    fun `barColors paint the bar and containerColor paints the page`() {
        val bar = Color(0xFFFF0000)
        val page = Color(0xFF0000FF)
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme(background = Color.White, surface = Color.White)) {
                Box(Modifier.size(300.dp)) {
                    PageScaffold(
                        title = "",
                        barColors = TopAppBarDefaults.topAppBarColors(containerColor = bar),
                        containerColor = page,
                    ) {}
                }
            }
        }
        composeRule.waitForIdle()
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        assertEquals("inside the bar", bar, pixels[10, (28 * density).toInt()])
        assertEquals("under the bar", page, pixels[10, (100 * density).toInt()])
    }
}
