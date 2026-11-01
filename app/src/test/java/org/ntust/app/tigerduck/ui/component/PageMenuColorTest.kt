package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The three dots are one color on every page, in the bar and in the content, and dim when the menu
 * is disabled. Read back from pixels, as a tint on the glyph would also pass a check on the helper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageMenuColorTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val variant = Color(0xFFFF0000)
    private val onSurface = Color(0xFF0000FF)
    private val onBackground = Color(0xFF00FF00)

    private fun Color.near(other: Color) =
        abs(red - other.red) < 0.04f && abs(green - other.green) < 0.04f && abs(blue - other.blue) < 0.04f

    private fun count(color: Color): Int {
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        var found = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (pixels[x, y].near(color)) found++
        return found
    }

    private fun show(position: PageHeaderPosition, enabled: Boolean = true) {
        composeRule.setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    background = Color.White,
                    surface = Color.White,
                    onSurfaceVariant = variant,
                    onSurface = onSurface,
                    onBackground = onBackground,
                ),
            ) {
                Box(Modifier.size(300.dp).background(Color.White)) {
                    Page(position, enabled)
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun Page(position: PageHeaderPosition, enabled: Boolean) {
        PageScaffold(
            title = "",
            headerPosition = position,
            menu = PageMenu(label = "Actions", enabled = enabled) {},
        ) { if (position == PageHeaderPosition.InContent) Header() }
    }

    @Test
    fun `in a bar the dots are the bar's onSurfaceVariant`() {
        show(PageHeaderPosition.Pinned)
        assertTrue(count(variant) > 20)
        assertEquals(0, count(onBackground))
        assertEquals(0, count(onSurface))
    }

    @Test
    fun `in the content the dots are the same color, not the page's onBackground`() {
        show(PageHeaderPosition.InContent)
        assertTrue(count(variant) > 20)
        assertEquals(0, count(onBackground))
        assertEquals(0, count(onSurface))
    }

    private fun assertDims(position: PageHeaderPosition) {
        show(position, enabled = false)
        assertEquals(0, count(variant))
        val dimmed = Color(1f, 1f - 0.38f, 1f - 0.38f)
        assertTrue(count(dimmed) > 20)
    }

    @Test
    fun `a disabled menu's dots dim in a bar`() = assertDims(PageHeaderPosition.Pinned)

    @Test
    fun `a disabled menu's dots dim in the content`() = assertDims(PageHeaderPosition.InContent)
}
