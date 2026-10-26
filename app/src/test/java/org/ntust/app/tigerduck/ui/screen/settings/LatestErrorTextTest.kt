package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The TigerSync status card's latest error. A failure without a message is
 * stored as "", and the card says "Failed" in the language the app shows now,
 * so switching the app's language later does not leave the old one's word.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app this test does not need.
@Config(application = Application::class)
class LatestErrorTextTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(lastError: String) = composeRule.setContent { Text(latestErrorText(lastError)) }

    @Test
    fun `a failure without a message reads as Failed`() {
        show("")
        composeRule.onNodeWithText("Failed").assertExists()
    }

    @Test
    @Config(qualifiers = "zh-rTW")
    fun `in Chinese it reads as the Chinese word`() {
        show("")
        composeRule.onNodeWithText("失敗").assertExists()
    }

    @Test
    fun `a failure's own message is shown as it is`() {
        show("timeout")
        composeRule.onNodeWithText("timeout").assertExists()
    }
}
