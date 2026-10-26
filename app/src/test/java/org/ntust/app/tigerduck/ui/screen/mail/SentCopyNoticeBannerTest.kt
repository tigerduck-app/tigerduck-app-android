package org.ntust.app.tigerduck.ui.screen.mail

import android.app.Application
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.SentCopyNotice
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [Build.VERSION_CODES.TIRAMISU])
class SentCopyNoticeBannerTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    @Test
    fun `each reason reads as its own sentence, a failure with what went wrong`() {
        var notice by mutableStateOf<SentCopyNotice>(SentCopyNotice.NoFolder)
        composeRule.setContent { SentCopyNoticeBanner(notice, onDismiss = {}) }
        composeRule.onNodeWithText(text(R.string.school_mail_sent_copy_no_folder)).assertExists()
        notice = SentCopyNotice.Unknown
        composeRule.onNodeWithText(text(R.string.school_mail_sent_copy_unknown)).assertExists()
        notice = SentCopyNotice.Failed(MailError.Network())
        composeRule.onNodeWithText(
            text(R.string.school_mail_sent_copy_failed) + "\n" + text(R.string.school_mail_error_network),
        ).assertExists()
    }

    @Test
    fun `Got it dismisses it`() {
        var dismissed = 0
        composeRule.setContent { SentCopyNoticeBanner(SentCopyNotice.NoFolder, onDismiss = { dismissed++ }) }
        composeRule.onNodeWithText(text(R.string.settings_acknowledged)).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `TalkBack reads it out when it appears`() {
        composeRule.setContent { SentCopyNoticeBanner(SentCopyNotice.NoFolder, onDismiss = {}) }
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)).assertExists()
    }
}
