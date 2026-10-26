package org.ntust.app.tigerduck.ui.screen.mail

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.mail.mailSummary
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What TalkBack is told about a mail's attachments and the message's ⋮ button. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MailA11yLabelsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(@StringRes id: Int) = RuntimeEnvironment.getApplication().getString(id)

    @Test
    fun `a mail with attachments says so on its card`() {
        composeRule.setContent {
            MailCard(mailSummary(1, hasAttachments = true), mailDomain = "mail.ntust.edu.tw", onClick = {})
        }

        composeRule
            .onNodeWithContentDescription(text(R.string.school_mail_attachments), useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun `the message's more button is named for all its actions`() {
        composeRule.setContent { MessageActionsButton(onClick = {}) }

        composeRule
            .onNodeWithContentDescription(text(R.string.school_mail_message_actions))
            .assertHasClickAction()
        composeRule.onNodeWithContentDescription(text(R.string.school_mail_view_mode)).assertDoesNotExist()
    }
}
