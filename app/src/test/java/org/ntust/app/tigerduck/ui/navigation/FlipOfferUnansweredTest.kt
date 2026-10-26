package org.ntust.app.tigerduck.ui.navigation

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.library.FlipToLibrarySetting
import org.ntust.app.tigerduck.ui.firsttrigger.FirstTriggerPromptController
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The flip listens for the question only while it is unanswered. The answer is stored, which
 * Compose cannot follow, so it is read again as the question comes and goes and as the switch
 * changes: a Maybe later has to stop the sensor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FlipOfferUnansweredTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var setting: FlipToLibrarySetting
    private lateinit var controller: FirstTriggerPromptController
    private var unanswered: Boolean? = null

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = AppPreferences(context)
        setting = FlipToLibrarySetting(prefs)
        controller = FirstTriggerPromptController(prefs)
        composeRule.setContent {
            unanswered = rememberFlipOfferUnanswered(controller, enabled = setting.enabled)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `the question is still to ask until answered`() {
        assertTrue(unanswered!!)
    }

    @Test
    fun `maybe later answers it`() {
        composeRule.runOnIdle { FlipToLibraryPrompt.request(controller, setting) }
        // Answered a frame later at the soonest, as the dialog has to show first.
        composeRule.waitForIdle()
        assertTrue(unanswered!!)
        composeRule.runOnIdle { controller.finish(accept = false) }
        composeRule.waitForIdle()
        assertFalse(unanswered!!)
    }

    @Test
    fun `a change to the switch in settings answers it`() {
        composeRule.runOnIdle { setting.setFromSettings(true) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { setting.setFromSettings(false) }
        composeRule.waitForIdle()
        assertFalse(unanswered!!)
    }
}
