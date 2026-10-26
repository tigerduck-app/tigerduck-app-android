// The course color picker's code field: what typing does to the color the sheet applies, and
// what the field shows when the color is moved another way.

package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need. Tall enough that
// nothing scrolls.
@Config(application = Application::class, qualifiers = "en-w411dp-h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColorPickerSheetCodeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val teal = Color(0xFF1ABC9C)
    private val coral = Color(0xFFFF7F50)
    private var applied: Color? = null

    @Test
    fun `the field shows the course's color code`() {
        show()

        assertEquals("#1ABC9C", fieldText())
    }

    @Test
    fun `a typed code is the color Apply gives, exactly`() {
        show()
        field().performTextReplacement("#12AB34")

        assertEquals(Color(0xFF12AB34), apply())
    }

    @Test
    fun `a code without its # and in lower case is taken too`() {
        show()
        field().performTextReplacement("12ab34")

        assertEquals("12ab34", fieldText())
        assertEquals(Color(0xFF12AB34), apply())
    }

    @Test
    fun `a code that is no color stays in the field and never applies`() {
        show()
        field().performTextReplacement("#12AB34")
        field().performTextReplacement("#12AB3G")

        assertEquals("#12AB3G", fieldText())
        assertEquals("the last color typed in full is applied", Color(0xFF12AB34), apply())
    }

    @Test
    fun `a code pasted longer than the field takes is the color the field shows`() {
        show()
        field().performTextReplacement("#12AB345")

        assertEquals("#12AB34", fieldText())
        assertEquals(Color(0xFF12AB34), apply())
    }

    @Test
    fun `a code typed after the square and the slider were used still moves them`() {
        show()
        // Each starts its gesture handling on first use; a typed code must reach it after that.
        tapSquare(x = 1f / 4, y = 1f / 4)
        tapSlider(x = 2f / 3)
        field().performTextReplacement("#800000")
        tapSlider(x = 1f / 3)

        val (hue, sat, value) = hsv(apply())
        assertEquals(120f, hue, 3f)
        assertEquals("the saturation is not the typed code's", 1f, sat, 0.01f)
        assertEquals("the brightness is not the typed code's", 0.5f, value, 0.01f)
    }

    @Test
    fun `a typed code moves the slider, so the square then picks from its hue`() {
        show()
        field().performTextReplacement("#FF0000")
        tapSquare(x = 1f / 2, y = 1f / 2)

        val (hue, sat, value) = hsv(apply())
        assertTrue("the hue stayed where the course's color had it: $hue", hue < 2f || hue > 358f)
        assertEquals(0.5f, sat, 0.02f)
        assertEquals(0.5f, value, 0.02f)
    }

    @Test
    fun `a typed code moves the square, so the slider then keeps its saturation and brightness`() {
        show()
        field().performTextReplacement("#800000")
        tapSlider(x = 1f / 3)

        val (hue, sat, value) = hsv(apply())
        assertEquals(120f, hue, 3f)
        assertEquals("the saturation stayed where the course's color had it", 1f, sat, 0.01f)
        assertEquals("the brightness stayed where the course's color had it", 0.5f, value, 0.01f)
    }

    @Test
    fun `the field follows the slider`() {
        show()
        tapSlider(x = 1f / 3)

        val code = fieldText()
        assertEquals(code, "#%06X".format(apply().toArgb() and 0xFFFFFF))
        assertTrue("the field kept the course's code", code != "#1ABC9C")
    }

    @Test
    fun `the field follows the square`() {
        show()
        tapSquare(x = 1f / 4, y = 3f / 4)

        val code = fieldText()
        assertEquals(code, "#%06X".format(apply().toArgb() and 0xFFFFFF))
        assertTrue("the field kept the course's code", code != "#1ABC9C")
    }

    @Test
    fun `the field follows a preset`() {
        show()
        composeRule.onNodeWithContentDescription("Color 1").performClick()
        composeRule.waitForIdle()

        assertEquals("#FF7F50", fieldText())
        assertEquals(coral, apply())
    }

    private fun show() {
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) {
                ColorPickerSheet(
                    courseName = "Operating Systems",
                    currentColor = teal,
                    presetPalette = listOf(coral, teal),
                    usedByOthers = emptySet(),
                    onApply = { applied = it },
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Custom").performClick()
        composeRule.waitForIdle()
    }

    private fun field() = composeRule.onNode(hasSetTextAction())

    private fun fieldText(): String = field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun apply(): Color {
        composeRule.onNode(hasText("Apply") and hasClickAction()).performClick()
        composeRule.waitForIdle()
        return checkNotNull(applied) { "Apply gave no color" }
    }

    private fun hsv(color: Color): Triple<Float, Float, Float> {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        return Triple(hsv[0], hsv[1], hsv[2])
    }

    private fun dp(value: Float) = value * RuntimeEnvironment.getApplication().resources.displayMetrics.density

    /**
     * Taps the hue slider, [x] of the way across. It carries no semantics of its own, so it is
     * found from the field under it: 12dp above the field, 28dp high, as wide as the field.
     */
    private fun tapSlider(x: Float) = tapAbove(x, centerAboveField = dp(12f + 28f / 2))

    /** Taps the square, [x] across and [y] down: above the slider, 180dp high. */
    private fun tapSquare(x: Float, y: Float) =
        tapAbove(x, centerAboveField = dp(12f + 28f + 12f + 180f * (1 - y)))

    private fun tapAbove(x: Float, centerAboveField: Float) {
        val field = field().fetchSemanticsNode().boundsInWindow
        val container = sheet()
        val origin = container.boundsInWindow
        composeRule.onNode(SemanticsMatcher("the sheet") { it.id == container.id }).performTouchInput {
            click(Offset(field.left + field.width * x - origin.left, field.top - centerAboveField - origin.top))
        }
        composeRule.waitForIdle()
    }

    /** The sheet: the field's highest ancestor below its window's root. */
    private fun sheet(): SemanticsNode {
        var node = field().fetchSemanticsNode()
        while (node.parent?.parent != null) node = node.parent!!
        return node
    }
}
