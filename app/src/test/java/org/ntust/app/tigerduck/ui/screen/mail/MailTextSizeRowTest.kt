package org.ntust.app.tigerduck.ui.screen.mail

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The text size row of the message's ⋮, as the iPhone's (tigerduck-app#1047): smaller, the
 * percentage, which puts it back to 100%, and larger, from 50% to 200% in tens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MailTextSizeRowTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val smaller by lazy { composeRule.activity.getString(R.string.school_mail_text_smaller) }
    private val larger by lazy { composeRule.activity.getString(R.string.school_mail_text_larger) }
    private val reset by lazy { composeRule.activity.getString(R.string.school_mail_text_size_reset) }

    private var shown = 0

    private fun show(start: Int) {
        composeRule.setContent {
            var percent by remember { mutableIntStateOf(start) }
            shown = percent
            Column { MailTextSizeRow(percent) { percent = it } }
        }
    }

    @Test
    fun `smaller and larger step by ten, and the percentage puts it back to 100`() {
        show(100)
        composeRule.onNodeWithContentDescription(larger).performClick()
        composeRule.onNodeWithContentDescription(larger).performClick()
        composeRule.runOnIdle { assertEquals(120, shown) }
        composeRule.onNodeWithContentDescription(smaller).performClick()
        composeRule.runOnIdle { assertEquals(110, shown) }
        composeRule.onNodeWithContentDescription(reset).performClick()
        composeRule.runOnIdle { assertEquals(100, shown) }
    }

    @Test
    fun `the percentage is read as what a tap on it does, then the size it is at`() {
        show(130)
        composeRule.onNodeWithContentDescription(reset)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "130%"))
    }

    @Test
    fun `each end stops its own button only`() {
        show(190)
        composeRule.onNodeWithContentDescription(larger).performClick()
        composeRule.onNodeWithContentDescription(larger).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(smaller).assertIsEnabled()

        repeat(15) { composeRule.onNodeWithContentDescription(smaller).performClick() }
        composeRule.runOnIdle { assertEquals(50, shown) }
        composeRule.onNodeWithContentDescription(smaller).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(larger).assertIsEnabled()
    }
}
