package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.AccentGradientSwitchStates
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The three switches under the accent row: the gradient on widgets, on the watch and in Live
 * Updates. Off by default, and dimmed while the accent is one color, as on the iPhone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccentGradientSwitchesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int) = composeRule.activity.getString(id)
    private val widgets get() = text(R.string.settings_accent_gradient_on_widgets)
    private val watch get() = text(R.string.settings_accent_gradient_on_watch)
    private val live get() = text(R.string.settings_accent_gradient_on_live_activities)

    private var switches by mutableStateOf(AccentGradientSwitchStates())

    private fun show(isGradient: Boolean, showsWatch: Boolean = true) {
        composeRule.setContent {
            // As in Settings' card, one row under another.
            Column {
                AccentGradientSwitches(
                    isGradient = isGradient,
                    states = switches,
                    showsWatch = showsWatch,
                    onChange = { switches = it },
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `while the accent is one color the three switches are off and dimmed`() {
        show(isGradient = false)

        listOf(widgets, watch, live).forEach {
            composeRule.onNodeWithText(it).assertIsOff().assertIsNotEnabled()
        }
    }

    @Test
    fun `with a gradient each switch turns on by itself`() {
        show(isGradient = true)

        listOf(widgets, watch, live).forEach { composeRule.onNodeWithText(it).assertIsEnabled() }
        composeRule.onNodeWithText(watch).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(watch).assertIsOn()
        composeRule.onNodeWithText(widgets).assertIsOff()
        assertEquals(AccentGradientSwitchStates(onWatch = true), switches)
    }

    @Test
    fun `where there is no watch app its switch is not offered`() {
        show(isGradient = true, showsWatch = false)

        composeRule.onNodeWithText(watch).assertDoesNotExist()
        composeRule.onNodeWithText(widgets).assertExists()
        composeRule.onNodeWithText(live).assertExists()
    }
}
