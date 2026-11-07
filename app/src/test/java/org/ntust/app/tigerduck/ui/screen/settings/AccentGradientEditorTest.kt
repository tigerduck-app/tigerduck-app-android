package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.AccentPolicy
import org.ntust.app.tigerduck.shared.AccentGradient
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.shared.AccentGradientStyle
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The accent gradient editor, as the iPhone's (tigerduck-app#1004): a preview, the four styles,
 * the eight linear directions, and one to five stops to add, remove, reorder and recolor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h2400dp")
class AccentGradientEditorTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val blue = 0x007AFF
    private val purple = 0xAF52DE
    private val cyan = 0x5AC8FA

    private var applied: AccentGradient? = null
    private var dismissed = 0

    private fun text(id: Int) = composeRule.activity.getString(id)
    private fun stop(number: Int) = "Color $number"
    private fun remove(number: Int) = "Remove, Color $number"
    private val add get() = text(R.string.action_add)
    private val apply get() = text(R.string.color_picker_apply)

    private fun gradient(
        vararg stops: Int,
        style: AccentGradientStyle = AccentGradientStyle.Linear,
        direction: AccentGradientDirection = AccentGradientDirection.TopLeftToBottomRight,
    ) = requireNotNull(AccentGradient.of(stops.toList(), style, direction))

    private fun show(initial: AccentGradient, fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AccentGradientEditor(
                    initial = initial,
                    isDark = false,
                    onApply = { applied = it },
                    onDismiss = { dismissed++ },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun applyNow(): AccentGradient? {
        // Below the controls, which scroll, so always on screen.
        composeRule.onNodeWithText(apply).performClick()
        composeRule.waitForIdle()
        return applied
    }

    /** What TalkBack is told, politely, once it changes: the count of colors after an add or remove. */
    private fun announcement(): String? = composeRule
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        .fetchSemanticsNodes()
        .singleOrNull { it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite }
        ?.config?.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()

    @Test
    fun `one color has one stop, which cannot be removed, and no styles yet`() {
        show(AccentGradient.color(blue))

        composeRule.onNodeWithText(text(R.string.settings_accent_gradient_custom)).assertExists()
        composeRule.onNodeWithContentDescription(stop(1)).assertIsSelected()
        composeRule.onNode(hasContentDescription(stop(1)) and hasText("#007AFF")).assertExists()
        composeRule.onNodeWithContentDescription(remove(1)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(text(R.string.settings_accent_gradient_style_linear)).assertDoesNotExist()
        composeRule.onNodeWithText(add).assertExists()
    }

    @Test
    fun `adding a stop adds the first preset not yet used, and says how many colors there are`() {
        show(AccentGradient.color(blue))

        composeRule.onNodeWithText(add).performClick()

        composeRule.onNodeWithContentDescription(remove(1)).assertExists()
        composeRule.onNodeWithContentDescription(remove(2)).assertExists()
        assertEquals("Colors: 2", announcement())
        assertEquals(gradient(blue, purple), applyNow())
    }

    @Test
    fun `stops can be added up to five, and then no more`() {
        show(AccentGradient.color(blue))

        repeat(4) { composeRule.onNodeWithText(add).performScrollTo().performClick() }

        composeRule.onNodeWithText(add).assertDoesNotExist()
        assertEquals("Colors: 5", announcement())
        assertEquals(5, applyNow()?.stops?.size)
    }

    @Test
    fun `stops can be removed down to one, the count said each time`() {
        show(gradient(blue, purple, cyan))

        composeRule.onNodeWithContentDescription(remove(2)).performClick()
        assertEquals("Colors: 2", announcement())
        composeRule.onNodeWithContentDescription(remove(1)).performClick()
        assertEquals("Colors: 1", announcement())

        composeRule.onNodeWithContentDescription(remove(1)).assertDoesNotExist()
        assertEquals(AccentGradient.color(cyan), applyNow())
    }

    @Test
    fun `opening says nothing until the count changes`() {
        show(gradient(blue, purple))

        assertNull(announcement())
    }

    @Test
    fun `a stop moves up or down by the actions TalkBack offers`() {
        show(gradient(blue, purple, cyan))

        val moveDown = text(R.string.desktop_settings_sidebar_move_down)
        val actions = composeRule.onNodeWithContentDescription(stop(1)).fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        // The first stop can only go down.
        assertEquals(listOf(moveDown), actions.map { it.label })
        actions.single().action()
        composeRule.waitForIdle()

        val last = composeRule.onNodeWithContentDescription(stop(3)).fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        assertEquals(listOf(text(R.string.desktop_settings_sidebar_move_up)), last.map { it.label })
        assertEquals(gradient(purple, blue, cyan), applyNow())
    }

    @Test
    fun `a stop chosen is recolored by its code, the others left as they are`() {
        show(gradient(blue, purple))

        composeRule.onNodeWithContentDescription(stop(2)).performClick()
        composeRule.onNodeWithContentDescription(stop(2)).assertIsSelected()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("#123456")

        assertEquals(gradient(blue, 0x123456), applyNow())
    }

    // Cyan is the pale preset: the theme draws it darker on the light cards
    // (AccentPolicy.fixedColor), which must not be stored over it.
    @Test
    fun `apply keeps the colors the editor opened with as they were`() {
        val opening = gradient(cyan, 0xFFFFFF, style = AccentGradientStyle.Angular)
        show(opening)

        assertEquals(opening, applyNow())
    }

    @Test
    fun `a color picked is stored as picked, and drawn readable on the cards`() {
        show(AccentGradient.color(blue))

        composeRule.onNode(hasSetTextAction()).performTextReplacement("#FFFFFF")

        assertEquals(AccentGradient.color(0xFFFFFF), applyNow())
        // As the preview and every screen draw it: white darkened on the light cards, black
        // lightened on the dark ones, to 3:1 (AccentPolicy.fixedColor).
        val lightCard = Color(0xFFF2F2F7)
        val darkCard = Color(0xFF3A3A3C)
        assertTrue(AccentPolicy.contrast(Color(editorTone(isDark = false)(0xFFFFFF)), lightCard) >= 3f)
        assertTrue(AccentPolicy.contrast(Color(editorTone(isDark = true)(0x000000)), darkCard) >= 3f)
    }

    @Test
    fun `cancelling applies nothing`() {
        show(gradient(blue, purple))

        composeRule.onNodeWithText(add).performClick()
        composeRule.onNodeWithText(text(R.string.action_cancel)).performClick()

        assertNull(applied)
        assertEquals(1, dismissed)
    }

    @Test
    fun `a gradient picks one of the four styles, and only a linear one picks a direction`() {
        show(gradient(blue, purple))

        val radial = text(R.string.settings_accent_gradient_style_radial)
        val bottomToTop = text(R.string.settings_accent_gradient_direction_bottom_to_top)
        composeRule.onNodeWithContentDescription(text(R.string.settings_accent_gradient_style_linear)).assertIsSelected()
        composeRule.onNodeWithContentDescription(bottomToTop).performClick()
        composeRule.onNodeWithContentDescription(radial).performClick()
        composeRule.onNodeWithContentDescription(radial).assertIsSelected()
        composeRule.onNodeWithContentDescription(bottomToTop).assertDoesNotExist()

        assertEquals(
            gradient(blue, purple, style = AccentGradientStyle.Radial, direction = AccentGradientDirection.BottomToTop),
            applyNow(),
        )
    }

    @Test
    fun `every style is offered by name`() {
        show(gradient(blue, purple))

        listOf(
            R.string.settings_accent_gradient_style_linear,
            R.string.settings_accent_gradient_style_radial,
            R.string.settings_accent_gradient_style_angular,
            R.string.settings_accent_gradient_style_mesh,
        ).forEach { composeRule.onNodeWithContentDescription(text(it)).assertExists() }
    }

    /** The directions' names, in the editor's order: row by row, four to a row. */
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
    fun `the directions are two rows of four, each above its reverse, the one picked selected`() {
        show(gradient(blue, purple, direction = AccentGradientDirection.RightToLeft))

        assertDirectionGrid(directionTiles())
        composeRule.onNodeWithContentDescription(text(R.string.settings_accent_gradient_direction_right_to_left))
            .assertIsSelected()
    }

    // A 360dp phone at the app's 130% display size: 277dp, and text at 150%.
    @Config(qualifiers = "w277dp-h2400dp")
    @Test
    fun `at the largest sizes on a small phone the directions stay two rows of four, whole and big enough to tap`() {
        show(gradient(blue, purple), fontScale = 1.5f)

        val tiles = directionTiles()
        assertDirectionGrid(tiles)
        val width = with(composeRule.density) { 277.dp.toPx() }
        tiles.forEach { assertTrue("$it runs past the screen", it.left >= 0f && it.right <= width) }
    }
}
