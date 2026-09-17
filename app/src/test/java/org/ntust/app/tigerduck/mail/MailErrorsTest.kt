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

    @Test
    fun `a full mailbox server is busy, not a rejected password`() {
        // Spec §12.3 keeps them apart: a busy server is retried next round, while a rejected
        // password cancels every background check and asks the user to sign in again. Mail2000
        // reports both as an authentication failure.
        val busy = listOf(
            "NO [UNAVAILABLE] Too many connections",
            "NO [LIMIT] maximum number of connections reached",
            "NO [INUSE] mailbox busy, try again later",
        )
        busy.forEach { reply ->
            assertTrue(reply, MailErrors.classify(AuthenticationFailedException(reply)) is MailError.ServerBusy)
        }
    }

    @Test
    fun `an ordinary rejection stays a rejected password`() {
        // The safe default: the server's exact wording is unverified, so anything that isn't a
        // recognized busy reply must keep stopping the retries.
        listOf("", "LOGIN failed", "NO Authentication failed", "invalid credentials").forEach { reply ->
            assertTrue(reply, MailErrors.classify(AuthenticationFailedException(reply)) is MailError.AuthFailed)
        }
        // A wrapper's wording must not turn a rejected password into "just busy" either.
        val wrapped = MessagingException("connect failed; try again", AuthenticationFailedException("LOGIN failed"))
        assertTrue(MailErrors.classify(wrapped) is MailError.AuthFailed)
    }
}
