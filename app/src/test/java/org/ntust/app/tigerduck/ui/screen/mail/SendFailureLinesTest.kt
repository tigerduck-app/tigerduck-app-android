package org.ntust.app.tigerduck.ui.screen.mail

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.mail.MailError

/**
 * What the compose screen says when a send fails (tigerduck-app-android#377, as the iPhone's
 * tigerduck-app#662): a send that may have gone out says so, in place of the failure line, so the
 * user checks Sent before sending it again.
 */
class SendFailureLinesTest {

    @Test
    fun `a send whose outcome is unknown says it may have gone out`() {
        assertEquals(listOf(R.string.school_mail_send_unconfirmed), sendFailureLines(MailError.SendUnconfirmed()))
    }

    @Test
    fun `a failed send says it failed, and why`() {
        assertEquals(
            listOf(R.string.school_mail_send_failed, R.string.school_mail_error_network),
            sendFailureLines(MailError.Network()),
        )
    }
}
