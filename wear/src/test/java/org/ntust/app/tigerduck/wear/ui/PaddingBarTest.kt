package org.ntust.app.tigerduck.wear.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.wear.ui.theme.LocalAccentColor
import org.ntust.app.tigerduck.wear.ui.theme.LocalAccentGradient
import org.ntust.app.tigerduck.wear.ui.theme.WearAccentGradient
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The bars on the two padding screens: they show the value's own place, from
 * empty at the least padding to full at the most, as it changes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaddingBarTest {

    @get:Rule
    val rule = createComposeRule()

    private fun bar(): Bitmap = rule.onNodeWithTag("bar").captureToImage().asAndroidBitmap()

    private fun Bitmap.at(fraction: Float): Color = Color(getPixel((width * fraction).toInt(), height / 2))

    private fun Color.near(other: Color) =
        abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue) < 0.15f

    @Test
    fun `the bar follows the value to either end, not only its first`() {
        var dp by mutableIntStateOf(12)
        rule.setContent {
            MaterialTheme { PaddingBar(dp, minDp = 0, maxDp = 24, modifier = Modifier.size(200.dp, 8.dp).testTag("bar")) }
        }

        dp = 24
        rule.waitForIdle()
        val full = bar()
        val fill = full.at(0.05f)
        assertTrue("filled at 85% when full", full.at(0.85f).near(fill))

        dp = 0
        rule.waitForIdle()
        assertFalse("not filled at 40% when empty", bar().at(0.4f).near(fill))
    }

    @Test
    fun `with a gradient picked on the phone the bar runs across it`() {
        rule.setContent {
            CompositionLocalProvider(
                LocalAccentColor provides Color(0xFFFF3B30),
                LocalAccentGradient provides WearAccentGradient(Color(0xFF34C8FF), AccentGradientDirection.LeftToRight),
            ) {
                MaterialTheme { PaddingBar(24, minDp = 0, maxDp = 24, modifier = Modifier.size(200.dp, 8.dp).testTag("bar")) }
            }
        }
        rule.waitForIdle()
        val full = bar()
        val start = full.at(0.05f)
        val end = full.at(0.9f)
        assertTrue("starts red: $start", start.red > start.blue)
        assertTrue("ends blue: $end", end.blue > end.red)
    }

    @Test
    fun `in a right-to-left language the bar's gradient keeps to the screen's own sides`() {
        // Wear's indicator mirrors its whole canvas there; the phone's gradient runs left to right.
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalAccentColor provides Color(0xFFFF3B30),
                LocalAccentGradient provides WearAccentGradient(Color(0xFF34C8FF), AccentGradientDirection.LeftToRight),
            ) {
                MaterialTheme { PaddingBar(24, minDp = 0, maxDp = 24, modifier = Modifier.size(200.dp, 8.dp).testTag("bar")) }
            }
        }
        rule.waitForIdle()
        val full = bar()
        val left = full.at(0.1f)
        val right = full.at(0.9f)
        assertTrue("red on the left: $left", left.red > left.blue)
        assertTrue("blue on the right: $right", right.blue > right.red)
    }

    @Test
    fun `an indicator handed a plain number follows it as it changes`() {
        // Now & Next's bar through a class: a value recomputed each minute, not a state of its own.
        var fraction by mutableFloatStateOf(0.5f)
        rule.setContent {
            MaterialTheme { FollowingProgressIndicator(fraction, modifier = Modifier.size(200.dp, 8.dp).testTag("bar")) }
        }

        fraction = 1f
        rule.waitForIdle()
        val full = bar()
        assertTrue("filled at 85% when full", full.at(0.85f).near(full.at(0.05f)))
    }

    @Test
    fun `the value's place runs from 0 to 1 and stops at the ends`() {
        assertEquals(0f, paddingFraction(0, 0, 24), 0f)
        assertEquals(0.5f, paddingFraction(12, 0, 24), 0f)
        assertEquals(1f, paddingFraction(24, 0, 24), 0f)
        assertEquals(1f, paddingFraction(30, 0, 24), 0f)
        assertEquals(0f, paddingFraction(5, 5, 5), 0f)
    }
}
