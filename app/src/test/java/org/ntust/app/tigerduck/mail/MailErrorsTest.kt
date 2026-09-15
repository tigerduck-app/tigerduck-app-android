package org.ntust.app.tigerduck.mail

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import jakarta.mail.search.SearchException
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import javax.net.ssl.SSLHandshakeException

class MailErrorsTest {
    @Test
    fun `exceptions map to the error the UI shows`() {
        assertTrue(MailErrors.classify(AuthenticationFailedException("no")) is MailError.AuthFailed)
        assertTrue(MailErrors.classify(MessagingException("x", SSLHandshakeException("Pin verification failed"))) is MailError.Certificate)
        assertTrue(MailErrors.classify(MessagingException("x", ConnectException("refused"))) is MailError.Network)
        assertTrue(MailErrors.classify(MessagingException("NO Too many connections")) is MailError.ServerBusy)
        assertTrue(MailErrors.classify(SearchException("BADCHARSET")) is MailError.SearchUnsupported)
        assertTrue(MailErrors.classify(IllegalStateException("odd")) is MailError.Protocol)
        val already = MailError.Network()
        assertTrue(MailErrors.classify(already) === already)
    }
}
