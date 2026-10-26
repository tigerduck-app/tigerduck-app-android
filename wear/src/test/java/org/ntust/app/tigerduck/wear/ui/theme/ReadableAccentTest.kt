package org.ntust.app.tigerduck.wear.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accent where the watch draws it on its own: the padding bars and the two
 * padding buttons, on the watch's black.
 */
class ReadableAccentTest {

    private val accents = listOf(
        Color(0xFFFF6B35), Color(0xFFFFD60A), Color(0xFF1A237E), Color(0xFF5856D6), Color(0xFF007AFF), Color.White, Color.Black,
    )

    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    @Test
    fun `an accent that reads on black is kept as it is`() {
        assertEquals(Color(0xFFFF6B35), readableOnBlack(Color(0xFFFF6B35)))
        assertEquals(Color(0xFFFFD60A), readableOnBlack(Color(0xFFFFD60A)))
    }

    @Test
    fun `a dark accent is lifted until it reads on black, keeping its hue`() {
        // The iPhone's own Indigo preset: 3.7:1 on black as it is.
        val lifted = readableOnBlack(Color(0xFF5856D6))
        assertTrue(contrast(lifted, Color.Black) >= 4.5f)
        assertTrue(lifted.blue > lifted.red && lifted.blue > lifted.green)
        assertTrue(contrast(readableOnBlack(Color.Black), Color.Black) >= 4.5f)
    }

    @Test
    fun `a padding button's text reads on its accent, light accent or dark`() {
        for (accent in accents) {
            val fill = readableOnBlack(accent)
            assertTrue("on $accent", contrast(textOn(fill), fill) >= 4.5f)
        }
        assertEquals(Color.Black, textOn(readableOnBlack(Color(0xFFFFD60A))))
    }
}
