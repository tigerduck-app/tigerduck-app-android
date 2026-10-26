package org.ntust.app.tigerduck.ui.theme

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * On the device's own fonts: Chinese text sits in the middle of its line in
 * the app's theme, and Latin text stays where the plain type scale puts it.
 */
@RunWith(AndroidJUnit4::class)
class CjkCenteringDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    private val styles: List<Pair<String, (Typography) -> TextStyle>> = listOf(
        "titleMedium" to { it.titleMedium },
        "bodyLarge" to { it.bodyLarge },
        "bodyMedium" to { it.bodyMedium },
        "bodySmall" to { it.bodySmall },
        "labelLarge" to { it.labelLarge },
    )

    @Composable
    private fun Lines(prefix: String) {
        val typography = MaterialTheme.typography
        Column(Modifier.background(Color.White)) {
            for ((name, style) in styles) {
                Text("中文設定", Modifier.testTag("$prefix cjk $name"), style = style(typography), color = Color.Black)
                Text("Settings", Modifier.testTag("$prefix latin $name"), style = style(typography), color = Color.Black)
            }
        }
    }

    /** How far the ink of the node tagged [tag] sits below the middle of its line, in px. */
    private fun inkBelowMiddle(tag: String): Float {
        val bitmap = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width)
        var top = -1
        var bottom = -1
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (pixels.any { (it and 0xFF) < 128 }) {
                if (top < 0) top = y
                bottom = y
            }
        }
        check(top >= 0) { "$tag drew nothing" }
        return (top + bottom + 1) / 2f - bitmap.height / 2f
    }

    @Before
    fun measureAfresh() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("cjk_centering", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** The theme measures off the main thread; until it's done, the styles are as they were. */
    private fun waitForMeasuring() {
        rule.waitUntil(10_000) { abs(inkBelowMiddle("app cjk titleMedium")) <= 1f }
    }

    @Test
    fun chineseTextSitsInTheMiddleOfItsLine() {
        rule.setContent { TigerDuckAppTheme(darkTheme = false) { Lines("app") } }
        waitForMeasuring()

        for ((name, _) in styles) {
            val low = inkBelowMiddle("app cjk $name")
            assertTrue("$name: Chinese ink $low px below the middle", abs(low) <= 1f)
        }
    }

    @Test
    fun latinTextStaysWhereThePlainScalePutsIt() {
        rule.setContent {
            Column {
                MaterialTheme(typography = TigerDuckTypography) { Lines("plain") }
                TigerDuckAppTheme(darkTheme = false) { Lines("app") }
            }
        }
        waitForMeasuring()

        for ((name, _) in styles) {
            assertEquals("$name: Latin moved", inkBelowMiddle("plain latin $name"), inkBelowMiddle("app latin $name"), 1f)
        }
    }
}
