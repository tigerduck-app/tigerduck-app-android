package org.ntust.app.tigerduck.ui.component

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What TalkBack says for the preset swatches of a course's colour picker. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ColorPickerSheetA11yTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `each preset swatch is numbered and the current colour is selected`() {
        val red = Color(0xFFFF3B30)
        val blue = Color(0xFF007AFF)
        composeRule.setContent {
            ColorPickerSheet(
                courseName = "Calculus",
                currentColor = blue,
                presetPalette = listOf(red, blue),
                usedByOthers = emptySet(),
                onApply = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithContentDescription("Color 1").assertIsNotSelected()
        composeRule.onNodeWithContentDescription("Color 2").assertIsSelected()
    }
}
