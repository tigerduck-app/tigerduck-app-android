package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.ui.screen.settings.PinMyselfSetting
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * "Pin myself" is a switch in the members list's ⋮, above Settings, and a row of Settings, as on
 * the iPhone (tigerduck-app#1032); both write the one preference.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PinMyselfSwitchTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPreferences(context)
    }

    private fun string(id: Int) = composeRule.activity.getString(id)

    @Test
    fun `the members list's menu has the switch above Settings, and it turns the pin off`() {
        composeRule.setContent {
            MoodleListScaffold(
                title = "Members",
                onBack = {},
                moodlePath = null,
                menuItems = { close -> PinMyselfMenuSwitch(prefs.pinMyselfInCourseMembers, close) },
            ) {}
        }

        composeRule.onNodeWithContentDescription(string(R.string.action_more_actions)).performClick()
        val switch = composeRule.onNodeWithText(string(R.string.course_members_pin_myself))
        val settings = composeRule.onNodeWithText(string(R.string.action_settings))
        assertTrue(switch.fetchSemanticsNode().boundsInRoot.top < settings.fetchSemanticsNode().boundsInRoot.top)
        switch.assertIsOn().performClick()

        assertFalse(prefs.pinMyselfInCourseMembers.value)
    }

    @Test
    fun `the Settings row shows the preference and writes it`() {
        prefs.pinMyselfInCourseMembers.value = false
        composeRule.setContent { PinMyselfSetting(prefs.pinMyselfInCourseMembers) }

        composeRule.onNodeWithText(string(R.string.course_members_pin_myself)).assertIsOff().performClick()

        assertTrue(prefs.pinMyselfInCourseMembers.value)
        composeRule.onNodeWithText(string(R.string.course_members_pin_myself)).assertIsOn()
    }
}
