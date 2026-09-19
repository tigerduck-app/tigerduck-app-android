package org.ntust.app.tigerduck.mail

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import jakarta.mail.search.SearchException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertSame
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

    /**
     * There is no [MailError] that honestly describes a cancelled coroutine, and every caller of
     * [MailErrors.classify] routes plain `Exception` through it -- so without this a cancellation
     * would come back as [MailError.Protocol] and be recorded as a failure of a check or a
     * sign-in that was merely called off.
     */
    @Test
    fun `a cancellation is rethrown, never classified as a mail failure`() {
        val cancelled = CancellationException("called off")
        val thrown = runCatching { MailErrors.classify(cancelled) }.exceptionOrNull()
        assertSame(cancelled, thrown)
    }

    @Test
    fun `a full mailbox server is busy, not a rejected password`() {
        // Spec §12.3 keeps them apart: a busy server is retried next round, while a rejected
        // password cancels every background check and asks the user to sign in again. Mail2000
        // reports both as an authentication failure, so only the RFC 5530 response codes and
        // two unambiguous phrasings count as busy.
        val busy = listOf(
            "NO [UNAVAILABLE] Too many connections",
            "NO [LIMIT] maximum number of connections reached",
            "NO [INUSE] mailbox is in use, try again later",
            "NO too many connections from this address",
            "NO connection limit reached for this user",
        )
        busy.forEach { reply ->
            assertTrue(reply, MailErrors.classify(AuthenticationFailedException(reply)) is MailError.ServerBusy)
        }
        // The same list is what the generic fallback uses for a non-Authentication failure.
        assertTrue(MailErrors.classify(MessagingException("NO Too many connections")) is MailError.ServerBusy)
    }

    @Test
    fun `an ordinary rejection stays a rejected password`() {
        // Fail closed: the server's exact wording is unverified, so anything that is not an
        // unambiguous busy reply must keep stopping the retries -- including the ones that only
        // *sound* like "not now". "Please try again" is what a rejection says as well.
        listOf(
            "",
            "LOGIN failed",
            "LOGIN failed, please try again",
            "NO Authentication failed",
            "NO mailbox busy",
            "invalid credentials",
            "NO maximum login attempts exceeded",
        ).forEach { reply ->
            assertTrue(reply, MailErrors.classify(AuthenticationFailedException(reply)) is MailError.AuthFailed)
        }
        // A wrapper's wording must not turn a rejected password into "just busy" either.
        val wrapped = MessagingException("connect failed; try again", AuthenticationFailedException("LOGIN failed"))
        assertTrue(MailErrors.classify(wrapped) is MailError.AuthFailed)
    }
}
