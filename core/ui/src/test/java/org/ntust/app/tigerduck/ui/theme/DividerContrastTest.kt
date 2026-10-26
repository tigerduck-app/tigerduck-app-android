package org.ntust.app.tigerduck.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * Material 3's HorizontalDivider draws in outlineVariant, and the app's dividers sit on cards,
 * which are surface. In the dark scheme the two were the same colour, so every divider inside
 * a card vanished there while it showed in the light one.
 */
class DividerContrastTest {

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return max(la, lb) / min(la, lb)
    }

    private val ColorScheme.dividerOnCard get() = contrast(outlineVariant, surface)

    // Bulletin cards, mail rows and rule rows are surfaceVariant fills.
    private val ColorScheme.dividerOnFill get() = contrast(outlineVariant, surfaceVariant)

    @Test
    fun `a divider on a card shows in the dark theme at least as clearly as in the light one`() {
        assertTrue(
            "dark ${DarkColorScheme.dividerOnCard} vs light ${LightColorScheme.dividerOnCard}",
            DarkColorScheme.dividerOnCard >= LightColorScheme.dividerOnCard,
        )
    }

    @Test
    fun `a divider stays quieter than an outline`() {
        listOf(LightColorScheme, DarkColorScheme).forEach { scheme ->
            assertTrue(scheme.dividerOnCard < contrast(scheme.outline, scheme.surface))
        }
    }

    @Test
    fun `a divider on a surfaceVariant fill shows in the dark theme at least as clearly as in the light one`() {
        // Once the same colour as the dark fill, which hid any divider or chip outline on it.
        assertTrue(
            "dark ${DarkColorScheme.dividerOnFill} vs light ${LightColorScheme.dividerOnFill}",
            DarkColorScheme.dividerOnFill >= LightColorScheme.dividerOnFill,
        )
    }
}
