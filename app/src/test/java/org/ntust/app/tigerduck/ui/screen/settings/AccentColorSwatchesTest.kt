package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.core.strings.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The accent row in Settings, the names it reads, and how the accent page's swatches lay out. */
@RunWith(RobolectricTestRunner::class)
// A window wider than any card these tests lay out, so none is cut by the window.
@Config(application = Application::class, qualifiers = "w480dp-h800dp")
class AccentColorSwatchesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val blue = 0x007AFF
    private val purple = 0xAF52DE

    private fun text(id: Int) = composeRule.activity.getString(id)

    /** Nine swatches, as many as the preset colors and the custom one, in a card [width] wide. */
    private fun showSwatches(width: Dp) {
        composeRule.setContent {
            Box(Modifier.requiredWidth(width).testTag(CARD)) {
                EvenLines(count = 9) {
                    repeat(9) {
                        AccentSwatch(
                            fill = SolidColor(Color.Blue),
                            name = "swatch $it",
                            selected = false,
                            checkTint = Color.White,
                            onClick = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Each swatch's line, by the swatches on it. */
    private fun swatchLines(): List<Int> = composeRule
        .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        .fetchSemanticsNodes()
        .groupBy { it.boundsInRoot.top }
        .toSortedMap()
        .values
        .map { it.size }

    private fun assertEverySwatchWholeInsideTheCard() {
        val card = composeRule.onNodeWithTag(CARD).fetchSemanticsNode().boundsInRoot
        val side = with(composeRule.density) { AccentSwatchSize.toPx() }
        val swatches = composeRule
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .fetchSemanticsNodes()
        assertEquals(9, swatches.size)
        swatches.forEach {
            val name = it.config[SemanticsProperties.ContentDescription].joinToString()
            val bounds = it.boundsInRoot
            assertEquals("$name is cut", side, bounds.width, 0.5f)
            assertEquals("$name is cut", side, bounds.height, 0.5f)
            assertTrue("$name is outside the card", bounds.left >= card.left - 0.5f && bounds.right <= card.right + 0.5f)
        }
    }

    // A 360dp phone's card: the screen less the card's margins and its padding.
    @Test
    fun `every swatch is whole and inside a narrow phone's card`() {
        showSwatches(width = 296.dp)

        assertEverySwatchWholeInsideTheCard()
        assertEquals(listOf(5, 4), swatchLines())
    }

    // A 411dp phone's card fits six swatches to a line: the nine go five and
    // four, not six and three.
    @Test
    fun `swatches that do not fit on one line wrap into even lines`() {
        showSwatches(width = 347.dp)

        assertEverySwatchWholeInsideTheCard()
        assertEquals(listOf(5, 4), swatchLines())
    }

    // 456dp is nine swatches to the dp, but at 2.625x the line rounds each gap
    // up to 32px, and the ninth no longer fits beside the other eight.
    @Config(qualifiers = "w480dp-h800dp-420dpi")
    @Test
    fun `lines are counted in the pixels the row lays them out in`() {
        showSwatches(width = 456.dp)

        assertEverySwatchWholeInsideTheCard()
        assertEquals(listOf(5, 4), swatchLines())
    }

    @Test
    fun `swatches that fit on one line stay on one line`() {
        showSwatches(width = 456.dp)

        assertEverySwatchWholeInsideTheCard()
        assertEquals(listOf(9), swatchLines())
    }

    @Test
    fun `the Settings row reads as one button, its name and the accent picked`() {
        var opened = 0
        composeRule.setContent {
            AccentColorRow(preview = SolidColor(Color.Blue), pickName = "Blue", onClick = { opened++ })
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Blue")
            .assert(hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher("names the row") {
                it.config.getOrNull(SemanticsProperties.Text)?.joinToString() == text(R.string.settings_accent_color)
            })
            .performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `the accent picked is called by its preset, its gradient, the custom ones or the wallpaper`() {
        val names = mutableListOf<String>()
        composeRule.setContent {
            names += accentPickName(fixedHex = blue, gradientEnd = null, followsWallpaper = false)
            names += accentPickName(fixedHex = 0x123456, gradientEnd = null, followsWallpaper = false)
            names += accentPickName(fixedHex = blue, gradientEnd = purple, followsWallpaper = false)
            // Two presets, but not a ready-made gradient: the user's own.
            names += accentPickName(fixedHex = purple, gradientEnd = blue, followsWallpaper = false)
            names += accentPickName(fixedHex = blue, gradientEnd = purple, followsWallpaper = true)
        }
        composeRule.waitForIdle()

        assertEquals(
            listOf(
                "Blue",
                text(R.string.course_color_picker_custom_label),
                "Blue to Purple",
                text(R.string.settings_accent_gradient_custom),
                text(R.string.color_name_wallpaper),
            ),
            names.take(5),
        )
    }

    // Gray into black: at the middle, under the check, dark is all but black.
    @Test
    fun `a gradient's check reads on its middle, not only on its start`() {
        assertEquals(Color.White, checkOnGradient(0x808080, 0x000000, isDark = false))
    }

    private companion object {
        const val CARD = "card"
    }
}
