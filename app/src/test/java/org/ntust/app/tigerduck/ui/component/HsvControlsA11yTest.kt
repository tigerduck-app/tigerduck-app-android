package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.ui.screen.settings.AccentPickerSheet
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The color picker's square and hue bar as TalkBack is handed them: each an
 * adjustable control with a name, its value read out, and a value it can be
 * set to, where they used to be plain drawings only a finger could move.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h900dp")
class HsvControlsA11yTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int) = composeRule.activity.getString(id)

    private fun control(id: Int): SemanticsNodeInteraction = composeRule.onNodeWithContentDescription(text(id))

    private fun SemanticsNodeInteraction.setTo(target: Float) =
        performSemanticsAction(SemanticsActions.SetProgress) { it(target) }

    private fun range(current: Float, range: ClosedFloatingPointRange<Float>) =
        SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(current, range))

    private fun reads(state: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state)

    private val focusable = SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused)

    /** The square and the bar on their own, with the values they were last set to. */
    private var hue = 0f
    private var sat = 0f
    private var value = 0f

    private fun showControls(startHue: Float, startSat: Float, startValue: Float) {
        composeRule.setContent {
            var h by remember { mutableFloatStateOf(startHue) }
            var s by remember { mutableFloatStateOf(startSat) }
            var v by remember { mutableFloatStateOf(startValue) }
            hue = h; sat = s; value = v
            Column {
                SatValSquare(hue = h, sat = s, value = v, onSatValChange = { ns, nv -> s = ns; v = nv })
                HueSlider(hue = h, onHueChange = { h = it })
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `the hue bar is an adjustable control named Hue that reads its degrees`() {
        showControls(startHue = 120f, startSat = 0.5f, startValue = 0.5f)

        control(R.string.a11y_color_picker_hue)
            .assert(range(120f, 0f..360f))
            .assert(reads("120°"))
            .assert(focusable)
    }

    @Test
    fun `setting the hue bar moves the hue and nothing else, within 0 to 360`() {
        showControls(startHue = 120f, startSat = 0.25f, startValue = 0.75f)

        control(R.string.a11y_color_picker_hue).setTo(200f)
        composeRule.waitForIdle()
        assertEquals(200f, hue, 0.001f)
        assertEquals(0.25f, sat, 0.001f)
        assertEquals(0.75f, value, 0.001f)
        control(R.string.a11y_color_picker_hue).assert(reads("200°"))

        control(R.string.a11y_color_picker_hue).setTo(500f)
        composeRule.waitForIdle()
        assertEquals(360f, hue, 0.001f)
    }

    @Test
    fun `the square is two adjustable controls, saturation and brightness, read as percentages`() {
        showControls(startHue = 30f, startSat = 0.8f, startValue = 0.6f)

        control(R.string.a11y_color_picker_saturation)
            .assert(range(0.8f, 0f..1f))
            .assert(reads("80%"))
            .assert(focusable)
        control(R.string.a11y_color_picker_brightness)
            .assert(range(0.6f, 0f..1f))
            .assert(reads("60%"))
            .assert(focusable)
    }

    @Test
    fun `setting saturation keeps the brightness, and setting brightness keeps the saturation`() {
        showControls(startHue = 30f, startSat = 0.8f, startValue = 0.6f)

        control(R.string.a11y_color_picker_saturation).setTo(0.3f)
        composeRule.waitForIdle()
        assertEquals(0.3f, sat, 0.001f)
        assertEquals(0.6f, value, 0.001f)

        control(R.string.a11y_color_picker_brightness).setTo(-1f)
        composeRule.waitForIdle()
        assertEquals(0.3f, sat, 0.001f)
        assertEquals(0f, value, 0.001f)
        assertEquals(30f, hue, 0.001f)
    }

    @Test
    fun `a finger still moves the square and the bar under TalkBack's controls`() {
        showControls(startHue = 0f, startSat = 0f, startValue = 0f)

        // The middle of the saturation control is the middle of the square's top half.
        control(R.string.a11y_color_picker_saturation).performTouchInput { click(center) }
        composeRule.waitForIdle()
        assertEquals(0.5f, sat, 0.02f)
        assertEquals(0.75f, value, 0.02f)

        control(R.string.a11y_color_picker_hue).performTouchInput { click(center) }
        composeRule.waitForIdle()
        assertEquals(180f, hue, 2f)
    }

    @Test
    fun `a drag that starts in the square's top half follows the finger into the bottom half`() {
        showControls(startHue = 0f, startSat = 0f, startValue = 1f)

        control(R.string.a11y_color_picker_saturation).performTouchInput {
            down(center)
            // The saturation control is the top half, so twice its height is the square's bottom edge.
            moveTo(Offset(center.x, height * 2f - 1f))
            up()
        }
        composeRule.waitForIdle()
        assertEquals(0.5f, sat, 0.02f)
        assertEquals(0f, value, 0.02f)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `left and right step a focused control, and stop at its ends`() {
        showControls(startHue = 342f, startSat = 0.5f, startValue = 0.5f)
        val hueBar = control(R.string.a11y_color_picker_hue)
        hueBar.requestFocus()

        hueBar.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()
        assertEquals(360f, hue, 0.001f)
        hueBar.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()
        assertEquals(360f, hue, 0.001f)
        hueBar.performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.waitForIdle()
        assertEquals(342f, hue, 0.001f)

        val saturation = control(R.string.a11y_color_picker_saturation)
        saturation.requestFocus()
        saturation.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()
        assertEquals(0.55f, sat, 0.001f)
        assertEquals(0.5f, value, 0.001f)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `up and down move the keyboard focus on instead of changing a value`() {
        showControls(startHue = 30f, startSat = 0.5f, startValue = 0.5f)
        val saturation = control(R.string.a11y_color_picker_saturation)
        val brightness = control(R.string.a11y_color_picker_brightness)
        saturation.requestFocus()

        saturation.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        brightness.assertIsFocused()
        brightness.performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.waitForIdle()
        saturation.assertIsFocused()

        assertEquals(0.5f, sat, 0.001f)
        assertEquals(0.5f, value, 0.001f)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `an arrow past a control's end moves the keyboard focus on`() {
        composeRule.setContent {
            var h by remember { mutableFloatStateOf(360f) }
            hue = h
            Row {
                HueSlider(hue = h, onHueChange = { h = it }, modifier = Modifier.weight(1f))
                Box(Modifier.size(48.dp).testTag(AFTER).focusable())
            }
        }
        composeRule.waitForIdle()
        val hueBar = control(R.string.a11y_color_picker_hue)
        hueBar.requestFocus()

        hueBar.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()

        assertEquals(360f, hue, 0.001f)
        composeRule.onNodeWithTag(AFTER).assertIsFocused()
    }

    private companion object {
        const val AFTER = "after the hue bar"
    }

    @Test
    fun `in the accent picker, each end of a gradient is adjusted on its own`() {
        composeRule.setContent {
            AccentPickerSheet(
                initialStart = 0xFF0000,
                initialEnd = 0x0000FF,
                direction = AccentGradientDirection.DEFAULT,
                isDark = false,
                onApply = { _, _ -> },
                onDismiss = {},
            )
        }
        composeRule.waitForIdle()

        control(R.string.a11y_color_picker_brightness).setTo(0.5f)
        composeRule.waitForIdle()
        composeRule.onNode(hasText("#800000")).assertExists()

        composeRule.onNodeWithText(text(R.string.settings_accent_gradient_end)).performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasText("#0000FF")).assertExists()
        control(R.string.a11y_color_picker_hue).setTo(120f)
        composeRule.waitForIdle()
        composeRule.onNode(hasText("#00FF00")).assertExists()

        composeRule.onNodeWithText(text(R.string.settings_accent_gradient_start)).performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasText("#800000")).assertExists()
    }

    @Test
    fun `in the course color picker, setting the hue changes the color code shown`() {
        composeRule.setContent {
            ColorPickerSheet(
                courseName = "Calculus",
                currentColor = Color(0xFFFF0000),
                presetPalette = emptyList(),
                usedByOthers = emptySet(),
                onApply = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText(text(R.string.color_picker_custom)).performClick()
        composeRule.waitForIdle()

        control(R.string.a11y_color_picker_hue).setTo(120f)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("#00FF00").assertExists()
    }

    @Test
    fun `in the accent picker, setting brightness changes the color code field`() {
        composeRule.setContent {
            AccentPickerSheet(
                initialStart = 0xFF0000,
                initialEnd = null,
                direction = AccentGradientDirection.DEFAULT,
                isDark = false,
                onApply = { _, _ -> },
                onDismiss = {},
            )
        }
        composeRule.waitForIdle()

        control(R.string.a11y_color_picker_brightness).setTo(0.5f)
        composeRule.waitForIdle()

        composeRule.onNode(hasText("#800000")).assertExists()
    }
}
