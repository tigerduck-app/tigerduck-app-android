package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A settings switch row reads as one switch to TalkBack, its label and summary included. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class SettingsToggleRowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val switch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

    @Test
    fun `the row is one switch that reads its label and summary and toggles from anywhere`() {
        var checked by mutableStateOf(false)
        composeRule.setContent {
            SettingsToggleRow(
                label = "Flip to show library QR",
                checked = checked,
                subtitle = "Flip your phone face-down to open the library QR.",
                onCheckedChange = { checked = it },
            )
        }

        composeRule.onAllNodes(isToggleable()).assertCountEquals(1)
        val row = composeRule.onAllNodes(isToggleable())[0]
        row.assert(switch)
            .assert(hasText("Flip to show library QR"))
            .assert(hasText("Flip your phone face-down to open the library QR."))
            .assertIsOff()

        composeRule.onNodeWithText("Flip to show library QR").performClick()
        row.assertIsOn()
    }

    @Test
    fun `a disabled row says why in place of on or off`() {
        composeRule.setContent {
            SettingsToggleRow(
                label = "Flip to show library QR",
                checked = false,
                enabled = false,
                disabledStateDescription = "Not signed in",
                onCheckedChange = {},
            )
        }

        composeRule.onAllNodes(isToggleable())[0]
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not signed in"))
    }
}
