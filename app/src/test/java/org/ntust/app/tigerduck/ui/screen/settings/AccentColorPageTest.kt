package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import androidx.activity.ComponentActivity
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.AccentGradient
import org.ntust.app.tigerduck.shared.AccentGradientStyle
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The accent color page: the wallpaper's colors, the preset colors, and the custom one, which
 * opens the gradient editor. The default SDK, 33, has the wallpaper's colors.
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

    private var picked: Int? = null
    private var pickedAccent: AccentGradient? = null
    private var wallpaperPicks = 0

    private fun text(id: Int) = composeRule.activity.getString(id)
    private val wallpaper get() = text(R.string.color_name_wallpaper)
    private val custom get() = text(R.string.settings_accent_gradient_custom)

    private fun show(
        accent: AccentGradient = AccentGradient.color(blue),
        followsWallpaper: Boolean = false,
        isDark: Boolean = false,
    ) {
        composeRule.setContent {
            AccentColorPage(
                accent = accent,
                followsWallpaper = followsWallpaper,
                isDark = isDark,
                onPick = { picked = it },
                onPickAccent = { pickedAccent = it },
                onPickWallpaper = { wallpaperPicks++ },
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `the page names each section, and says what the wallpaper's colors do`() {
        show()

        composeRule.onNodeWithText(wallpaper).assertExists()
        composeRule.onNodeWithText(text(R.string.settings_accent_wallpaper_description), substring = true).assertExists()
        composeRule.onNodeWithText(text(R.string.color_picker_presets_note)).assertExists()
        // Ready-made gradients and their directions are the editor's now, as on the iPhone.
        composeRule.onNodeWithText(text(R.string.settings_accent_gradients)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.settings_accent_gradient_direction)).assertDoesNotExist()
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
        show(accent = AccentGradient.color(blue))

        composeRule.onNodeWithContentDescription("Blue").assertIsSelected()
        composeRule.onNodeWithContentDescription("Indigo").assertIsNotSelected()
        composeRule.onNodeWithContentDescription(custom).assertIsNotSelected()
        composeRule.onNodeWithContentDescription(wallpaper).assertIsNotSelected()
    }

    @Test
    fun `choosing a preset in dark mode stores its light color`() {
        show(isDark = true)

        composeRule.onNodeWithContentDescription("Cyan").performClick()

        assertEquals(0x5AC8FA, picked)
    }

    @Test
    fun `a color of the user's own selects the custom one`() {
        show(accent = AccentGradient.color(own))

        composeRule.onNodeWithContentDescription(custom).assertIsSelected()
        composeRule.onNodeWithContentDescription("Blue").assertIsNotSelected()
    }

    @Test
    fun `a gradient selects the custom one, a gradient from a preset too, and no preset`() {
        show(accent = requireNotNull(AccentGradient.of(listOf(blue, purple))))

        composeRule.onNodeWithContentDescription(custom).assertIsSelected()
        composeRule.onNodeWithContentDescription("Blue").assertIsNotSelected()
    }

    @Test
    fun `the custom one opens the editor on the accent picked, and applying picks what it holds`() {
        val gradient = requireNotNull(AccentGradient.of(listOf(blue, own), AccentGradientStyle.Radial))
        show(accent = gradient)

        composeRule.onNodeWithContentDescription(custom).performClick()
        composeRule.onNodeWithText("#123456").assertExists()
        composeRule.onNodeWithText(text(R.string.color_picker_apply)).performClick()

        assertEquals(gradient, pickedAccent)
        composeRule.onNodeWithText(text(R.string.color_picker_apply)).assertDoesNotExist()
    }

    @Test
    fun `cancelling the editor picks nothing`() {
        show(accent = AccentGradient.color(own))

        composeRule.onNodeWithContentDescription(custom).performClick()
        composeRule.onNodeWithText(text(R.string.action_cancel)).performClick()

        assertNull(pickedAccent)
        assertNull(picked)
    }

    @Test
    fun `a color can be typed by its code in the editor, as TalkBack can`() {
        show(accent = AccentGradient.color(blue))

        composeRule.onNodeWithContentDescription(custom).performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("#336699")
        composeRule.onNodeWithText(text(R.string.color_picker_apply)).performClick()

        assertEquals(AccentGradient.color(0x336699), pickedAccent)
    }

    @Test
    fun `while the wallpaper is picked it is the one selected, and no other`() {
        show(accent = requireNotNull(AccentGradient.of(listOf(blue, purple))), followsWallpaper = true)

        composeRule.onNodeWithContentDescription(wallpaper).assertIsSelected()
        composeRule.onNodeWithContentDescription(custom).assertIsNotSelected()
        composeRule.onNodeWithContentDescription("Blue").assertIsNotSelected()
    }

    @Test
    fun `choosing the wallpaper picks it and nothing else`() {
        show()

        composeRule.onNodeWithContentDescription(wallpaper).performClick()

        assertEquals(1, wallpaperPicks)
        assertNull(picked)
        assertNull(pickedAccent)
    }

    @Test
    fun `every swatch is whole on a phone's screen`() {
        show()

        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val nodes = composeRule
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .fetchSemanticsNodes()
        // The wallpaper, eight presets and the custom one.
        assertEquals(1 + 8 + 1, nodes.size)
        nodes.forEach {
            val name = it.config[SemanticsProperties.ContentDescription].joinToString()
            assertTrue("$name is cut", it.boundsInRoot.left >= root.left && it.boundsInRoot.right <= root.right)
        }
    }

    // A phone on its side: the editor's controls do not all fit, and Apply
    // must still be there to press.
    @Config(qualifiers = "w640dp-h360dp")
    @Test
    fun `on a short screen the editor can still be applied`() {
        show(accent = AccentGradient.color(own))

        composeRule.onNodeWithContentDescription(custom).performScrollTo().performClick()
        composeRule.onNodeWithText(text(R.string.color_picker_apply)).performClick()

        assertEquals(AccentGradient.color(own), pickedAccent)
    }
}
