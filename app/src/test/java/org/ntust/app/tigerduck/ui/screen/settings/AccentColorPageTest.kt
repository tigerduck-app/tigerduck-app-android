package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The accent color page: the wallpaper's colors, the preset colors and one
 * of the user's own, and the gradients with the way they run. The default
 * SDK, 33, has the wallpaper's colors.
 */
@RunWith(RobolectricTestRunner::class)
// A phone's width, and tall enough that the whole page is on screen.
@Config(application = Application::class, qualifiers = "w360dp-h1600dp")
class AccentColorPageTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val blue = 0x007AFF
    private val purple = 0xAF52DE
    private val own = 0x123456
    private val ownEnd = 0x654321

    private var picked: Int? = null
    private var pickedGradient: Pair<Int, Int>? = null
    private var pickedDirection: AccentGradientDirection? = null
    private var wallpaperPicks = 0

    private fun text(id: Int) = composeRule.activity.getString(id)
    private val wallpaper get() = text(R.string.color_name_wallpaper)
    private val custom get() = text(R.string.course_color_picker_custom_label)
    private val customGradient get() = text(R.string.settings_accent_gradient_custom)
    private val directionLabel get() = text(R.string.settings_accent_gradient_direction)

    private fun show(
        fixedHex: Int = blue,
        gradientEnd: Int? = null,
        direction: AccentGradientDirection = AccentGradientDirection.TopLeftToBottomRight,
        followsWallpaper: Boolean = false,
        isDark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AccentColorPage(
                    fixedHex = fixedHex,
                    gradientEnd = gradientEnd,
                    direction = direction,
                    followsWallpaper = followsWallpaper,
                    isDark = isDark,
                    onPick = { picked = it },
                    onPickGradient = { start, end -> pickedGradient = start to end },
                    onPickDirection = { pickedDirection = it },
                    onPickWallpaper = { wallpaperPicks++ },
                )
            }
        }
        composeRule.waitForIdle()
    }

    /** The directions' names, in the page's order: row by row, four to a row. */
    private val directionNames = listOf(
        R.string.settings_accent_gradient_direction_top_to_bottom,
        R.string.settings_accent_gradient_direction_left_to_right,
        R.string.settings_accent_gradient_direction_bottom_left_to_top_right,
        R.string.settings_accent_gradient_direction_top_left_to_bottom_right,
        R.string.settings_accent_gradient_direction_bottom_to_top,
        R.string.settings_accent_gradient_direction_right_to_left,
        R.string.settings_accent_gradient_direction_top_right_to_bottom_left,
        R.string.settings_accent_gradient_direction_bottom_right_to_top_left,
    )

    private fun directionTiles(): List<Rect> = directionNames.map {
        composeRule.onNodeWithContentDescription(text(it)).fetchSemanticsNode().boundsInRoot
    }

    /** Two rows of four, in [directionNames]' order, each tile above its reverse and big enough to tap. */
    private fun assertDirectionGrid(tiles: List<Rect>) {
        val (top, bottom) = tiles.take(4) to tiles.drop(4)
        val tap = with(composeRule.density) { 48.dp.toPx() }
        top.forEach { assertEquals(top[0].top, it.top, 0.5f) }
        bottom.forEach { assertEquals(bottom[0].top, it.top, 0.5f) }
        assertTrue("the second row is under the first", bottom[0].top >= top[0].bottom)
        top.zip(bottom).forEach { (above, below) -> assertEquals(above.left, below.left, 0.5f) }
        top.zipWithNext().forEach { (a, b) -> assertTrue("$b follows $a", b.left >= a.right) }
        tiles.forEach { assertTrue("$it is too small to tap", it.width >= tap - 0.5f && it.height >= tap - 0.5f) }
    }

    @Test
    fun `the page names each section, and says what the wallpaper's colors do`() {
        show()

        composeRule.onNodeWithText(wallpaper).assertExists()
        composeRule.onNodeWithText(text(R.string.settings_accent_wallpaper_description), substring = true).assertExists()
        composeRule.onNodeWithText(text(R.string.color_picker_presets_note)).assertExists()
        composeRule.onNodeWithText(text(R.string.settings_accent_gradients)).assertExists()
    }

    @Config(sdk = [30])
    @Test
    fun `the wallpaper's colors are not offered on Android 11 and earlier`() {
        show()

        composeRule.onNodeWithText(wallpaper).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(wallpaper).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Blue").assertIsSelected()
    }

    @Test
    fun `the preset picked is the one selected`() {
        show(fixedHex = blue)

        composeRule.onNodeWithContentDescription("Blue").assertIsSelected()
        composeRule.onNodeWithContentDescription("Indigo").assertIsNotSelected()
        composeRule.onNodeWithContentDescription(custom).assertIsNotSelected()
        composeRule.onNodeWithContentDescription("Blue to Purple").assertIsNotSelected()
        composeRule.onNodeWithContentDescription(wallpaper).assertIsNotSelected()
    }

    @Test
    fun `choosing a preset in dark mode stores its light color`() {
        show(isDark = true)

        composeRule.onNodeWithContentDescription("Cyan").performClick()

        assertEquals(0x5AC8FA, picked)
    }

    @Test
    fun `a color of the user's own is shown on the custom swatch, selected`() {
        show(fixedHex = own)

        composeRule.onNodeWithContentDescription(custom).assertIsSelected()
        composeRule.onNodeWithContentDescription("Blue").assertIsNotSelected()
    }

    @Test
    fun `the custom swatch opens the picker on the color picked, and applying it picks that color`() {
        show(fixedHex = own)

        composeRule.onNodeWithContentDescription(custom).performClick()
        composeRule.onNodeWithText(text(R.string.color_picker_title)).assertExists()
        composeRule.onNodeWithText("#123456").assertExists()
        composeRule.onNodeWithText(text(R.string.color_picker_apply)).performClick()

        assertEquals(own, picked)
        composeRule.onNodeWithText(text(R.string.color_picker_title)).assertDoesNotExist()
    }

    @Test
    fun `cancelling the picker picks nothing`() {
        show(fixedHex = own)

        composeRule.onNodeWithContentDescription(custom).performClick()
        composeRule.onNodeWithText(text(R.string.action_cancel)).performClick()

        assertNull(picked)
    }

    @Test
    fun `choosing a ready-made gradient picks its two colors`() {
        show()

        composeRule.onNodeWithContentDescription("Blue to Purple").performClick()

        assertEquals(blue to purple, pickedGradient)
    }

    @Test
    fun `the gradient picked is the one selected, and no single color is`() {
        show(fixedHex = blue, gradientEnd = purple)

        composeRule.onNodeWithContentDescription("Blue to Purple").assertIsSelected()
        composeRule.onNodeWithContentDescription("Blue").assertIsNotSelected()
        composeRule.onNodeWithContentDescription(customGradient).assertIsNotSelected()
    }

    @Test
    fun `a gradient of the user's own selects the custom gradient`() {
        show(fixedHex = own, gradientEnd = ownEnd)

        composeRule.onNodeWithContentDescription(customGradient).assertIsSelected()
        composeRule.onNodeWithContentDescription(custom).assertIsNotSelected()
    }

    @Test
    fun `the custom gradient opens the picker with a start and an end, and applying picks both`() {
        show(fixedHex = blue)

        composeRule.onNodeWithContentDescription(customGradient).performClick()
        composeRule.onNodeWithText(text(R.string.settings_accent_gradient_start)).assertExists()
        composeRule.onNodeWithText(text(R.string.settings_accent_gradient_end)).assertExists()
        composeRule.onNodeWithText(text(R.string.color_picker_apply)).performClick()

        // It opens from the color picked to the end of its ready-made gradient.
        assertEquals(blue to purple, pickedGradient)
    }

    @Test
    fun `the directions show only while a gradient is picked`() {
        show(fixedHex = blue)

        composeRule.onNodeWithText(directionLabel).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(text(R.string.settings_accent_gradient_direction_top_to_bottom))
            .assertDoesNotExist()
    }

    @Test
    fun `with a gradient picked each direction is offered by name, the one picked selected`() {
        show(fixedHex = blue, gradientEnd = purple, direction = AccentGradientDirection.RightToLeft)

        composeRule.onNodeWithText(directionLabel).assertExists()
        (directionNames - R.string.settings_accent_gradient_direction_right_to_left)
            .forEach { composeRule.onNodeWithContentDescription(text(it)).assertIsNotSelected() }
        composeRule.onNodeWithContentDescription(text(R.string.settings_accent_gradient_direction_right_to_left))
            .assertIsSelected()
    }

    @Test
    fun `the directions are two rows of four, each above its reverse`() {
        show(fixedHex = blue, gradientEnd = purple)

        assertDirectionGrid(directionTiles())
    }

    // A 360dp phone at the app's 130% display size lays out as a 277dp one,
    // and its 150% text size is a font scale of 1.5.
    @Config(qualifiers = "w277dp-h1600dp")
    @Test
    fun `at the largest sizes on a small phone the directions stay two rows of four, whole and big enough to tap`() {
        show(fixedHex = blue, gradientEnd = purple, fontScale = 1.5f)

        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        // Inside the card's content: its margin and its own padding, 16dp each.
        val inset = with(composeRule.density) { 32.dp.toPx() }
        val tiles = directionTiles()
        assertDirectionGrid(tiles)
        tiles.forEach {
            assertTrue("$it runs into the card's padding", it.left >= root.left + inset - 0.5f && it.right <= root.right - inset + 0.5f)
        }
    }

    @Test
    fun `choosing a direction from a bottom corner picks it`() {
        show(fixedHex = blue, gradientEnd = purple)

        composeRule.onNodeWithContentDescription(text(R.string.settings_accent_gradient_direction_bottom_right_to_top_left))
            .performClick()

        assertEquals(AccentGradientDirection.BottomRightToTopLeft, pickedDirection)
    }

    @Test
    fun `choosing a direction picks it`() {
        show(fixedHex = blue, gradientEnd = purple)

        composeRule.onNodeWithContentDescription(text(R.string.settings_accent_gradient_direction_bottom_to_top))
            .performClick()

        assertEquals(AccentGradientDirection.BottomToTop, pickedDirection)
    }

    @Test
    fun `while the wallpaper is picked it is the one selected, and no gradient's directions show`() {
        show(fixedHex = blue, gradientEnd = purple, followsWallpaper = true)

        composeRule.onNodeWithContentDescription(wallpaper).assertIsSelected()
        composeRule.onNodeWithContentDescription("Blue to Purple").assertIsNotSelected()
        composeRule.onNodeWithText(directionLabel).assertDoesNotExist()
    }

    @Test
    fun `choosing the wallpaper picks it and nothing else`() {
        show(fixedHex = blue)

        composeRule.onNodeWithContentDescription(wallpaper).performClick()

        assertEquals(1, wallpaperPicks)
        assertNull(picked)
        assertNull(pickedGradient)
    }

    @Test
    fun `every swatch and direction is whole on a phone's screen`() {
        show(fixedHex = blue, gradientEnd = purple)

        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val nodes = composeRule
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .fetchSemanticsNodes()
        // The wallpaper, eight presets and the custom color, six gradients and
        // the custom one, and the eight directions.
        assertEquals(1 + 9 + 7 + 8, nodes.size)
        nodes.forEach {
            val name = it.config[SemanticsProperties.ContentDescription].joinToString()
            assertTrue("$name is cut", it.boundsInRoot.left >= root.left && it.boundsInRoot.right <= root.right)
        }
    }

    // A phone on its side: the picker's controls do not all fit, and Apply
    // must still be there to press.
    @Config(qualifiers = "w640dp-h360dp")
    @Test
    fun `on a short screen the picker can still be applied`() {
        show(fixedHex = own)

        composeRule.onNodeWithContentDescription(custom).performScrollTo().performClick()
        composeRule.onNodeWithText(text(R.string.color_picker_apply)).performClick()

        assertEquals(own, picked)
    }

    @Test
    fun `a color can be typed by its code, as TalkBack can`() {
        show(fixedHex = blue)

        composeRule.onNodeWithContentDescription(custom).performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("#336699")
        composeRule.onNodeWithText(text(R.string.color_picker_apply)).performClick()

        assertEquals(0x336699, picked)
    }

    @Test
    fun `a code that is not a color changes nothing`() {
        show(fixedHex = own)

        composeRule.onNodeWithContentDescription(custom).performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("#12")
        composeRule.onNodeWithText(text(R.string.color_picker_apply)).performClick()

        assertEquals(own, picked)
    }

    @Test
    fun `a gradient's end can be typed once the end is chosen`() {
        show(fixedHex = blue)

        composeRule.onNodeWithContentDescription(customGradient).performClick()
        composeRule.onNodeWithText(text(R.string.settings_accent_gradient_end)).performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("112233")
        composeRule.onNodeWithText(text(R.string.color_picker_apply)).performClick()

        assertEquals(blue to 0x112233, pickedGradient)
    }
}
