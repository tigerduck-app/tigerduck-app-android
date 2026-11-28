package org.ntust.app.tigerduck.ui.screen.whatsnew

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.WhatsNewSummary
import org.ntust.app.tigerduck.data.model.WhatsNewSummaryItem
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// An update past several versions shows each one's summary in turn: every summary but the last
// moves on with Next, so Continue, which closes the sheet, comes only once the last is reached.
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app this test does not need.
@Config(application = Application::class, qualifiers = "w384dp-h832dp-xxhdpi")
class WhatsNewSummaryStepsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun summary(versionCode: Int, title: String) = WhatsNewSummary(
        versionCode = versionCode,
        title = title,
        items = listOf(WhatsNewSummaryItem(title = "Row of $title", body = null, icon = null)),
    )

    private fun string(id: Int) = composeRule.activity.getString(id)

    @Test
    fun `an earlier version's summary moves on to the next one`() {
        val steps = listOf(summary(29, "What's new in 2.3.1"), summary(30, "What's new in 2.4.0")).map(WhatsNewStep::Summary)
        composeRule.setContent { WhatsNewSheet(WhatsNewFlow(steps), onDismiss = {}) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("What's new in 2.3.1").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.action_next)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("What's new in 2.4.0").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.whats_new_continue)).assertIsDisplayed()
    }
}
