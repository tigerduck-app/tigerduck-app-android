package org.ntust.app.tigerduck.wear.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
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

/** The two Settings buttons that open the padding screens, in the phone's accent. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccentButtonTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `with a gradient picked on the phone the button runs across it`() {
        rule.setContent {
            CompositionLocalProvider(
                LocalAccentColor provides Color(0xFFFF3B30),
                LocalAccentGradient provides WearAccentGradient(Color(0xFF34C8FF), AccentGradientDirection.LeftToRight),
            ) {
                MaterialTheme { AccentButton(text = "", onClick = {}, modifier = Modifier.size(200.dp, 52.dp).testTag("button")) }
            }
        }
        rule.waitForIdle()
        val button = rule.onNodeWithTag("button").captureToImage().asAndroidBitmap()
        val start = Color(button.getPixel((button.width * 0.12f).toInt(), button.height / 2))
        val end = Color(button.getPixel((button.width * 0.88f).toInt(), button.height / 2))
        assertTrue("starts red: $start", start.red > start.blue)
        assertTrue("ends blue: $end", end.blue > end.red)
    }

    @Test
    fun `with a single color the button is that color`() {
        rule.setContent {
            CompositionLocalProvider(LocalAccentColor provides Color(0xFFFF3B30)) {
                MaterialTheme { AccentButton(text = "", onClick = {}, modifier = Modifier.size(200.dp, 52.dp).testTag("button")) }
            }
        }
        rule.waitForIdle()
        val button = rule.onNodeWithTag("button").captureToImage().asAndroidBitmap()
        val end = Color(button.getPixel((button.width * 0.88f).toInt(), button.height / 2))
        assertTrue("stays red: $end", end.red > end.blue)
    }
}
