package org.ntust.app.tigerduck.mail.smtp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.mail.FakeMailServer
import org.ntust.app.tigerduck.mail.MailCredentials
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.compose.MessageBuilder
import org.ntust.app.tigerduck.mail.compose.OutgoingMail
import org.ntust.app.tigerduck.mail.model.MailAddress

/**
 * The dedupe probe and what the sender does with each of its three answers.
 *
 * Mail2000 advertises no SEARCH keys at all in its CAPABILITY banner, so a probe that cannot
 * answer is a case the app actually meets, not a theoretical one.
 */
class SentCopyTest {
    private val server = FakeMailServer()
    private val credentials = MailCredentials("b10000001", "pw")
    private val delivered = mutableListOf<String>()
    private val transport = MailTransport { _, message -> delivered += message.messageId }

    private fun sender(builder: MessageBuilder = MessageBuilder()) =
        MailSender(builder, transport, pause = {})

    /** Stands in for the repository's held connection: one session, reused for probe and APPEND. */
    private val sessions = SentCopySession { block -> server.factory().open(credentials).use(block) }

    private fun outgoing() = OutgoingMail(
        from = MailAddress("測試", credentials.address),
        to = listOf(MailAddress(null, "a@x.tw")),
        cc = emptyList(), bcc = emptyList(), subject = "hi", body = "body",
    )

    // --- the pure policy ---------------------------------------------------------------

    @Test
    fun `the probe has three answers, not two`() {
        assertEquals(SentCopyProbe.FOUND, SentCopyPolicy.probe { true })
        assertEquals(SentCopyProbe.NOT_FOUND, SentCopyPolicy.probe { false })
        assertEquals(SentCopyProbe.UNKNOWN, SentCopyPolicy.probe { throw MailError.SearchUnsupported() })
    }

    @Test
    fun `only a server that answered 'not there' gets a copy appended`() {
        assertTrue(SentCopyPolicy.appends(SentCopyProbe.NOT_FOUND))
        assertFalse(SentCopyPolicy.appends(SentCopyProbe.FOUND))
        assertFalse("a duplicate is worse than a missing copy", SentCopyPolicy.appends(SentCopyProbe.UNKNOWN))
    }

    @Test
    fun `a declined probe reports which of the two reasons it was`() {
        assertEquals(SentCopy.ServerFiledItself, SentCopyPolicy.declined(SentCopyProbe.FOUND))
        assertEquals(SentCopy.Unknown, SentCopyPolicy.declined(SentCopyProbe.UNKNOWN))
    }

    @Test
    fun `only a copy on the server counts as filed`() {
        assertTrue(SentCopy.Filed.filed)
        assertTrue(SentCopy.ServerFiledItself.filed)
        assertFalse(SentCopy.NotAttempted.filed)
        assertFalse(SentCopy.Unknown.filed)
        assertFalse(SentCopy.Failed(MailError.ServerBusy()).filed)
    }

    // --- what the sender does with each ------------------------------------------------

    @Test
    fun `a probe that cannot answer files nothing rather than risking a duplicate`() = runBlocking {
        // The probe is the session's first call, so this is the one that fails.
        server.queueCallErrors(MailError.SearchUnsupported())
        val result = sender().send(credentials, outgoing(), sentFolder = "寄件備份匣", sessions = sessions)
        assertEquals(SentCopy.Unknown, result.sentCopy)
        assertEquals("the mail still went out", 1, delivered.size)
        assertEquals("and nothing was appended blind", emptyList<String>(), server.subjects("寄件備份匣"))
    }

    @Test
    fun `a refused APPEND reports the error the send itself never fails on`() = runBlocking {
        // First call is the probe (let through), second is the APPEND.
        server.queueCallErrors(null, MailError.ServerBusy())
        val result = sender().send(credentials, outgoing(), sentFolder = "寄件備份匣", sessions = sessions)
        val failed = result.sentCopy as SentCopy.Failed
        assertTrue(failed.error is MailError.ServerBusy)
        assertTrue("the message id still came back", result.messageId.isNotBlank())
        assertEquals(1, delivered.size)
        assertTrue(server.subjects("寄件備份匣").isEmpty())
    }

    @Test
    fun `a probe that finds the server's own copy leaves it alone`() = runBlocking {
        val builder = MessageBuilder(newId = { "fixed" })
        server.deliver("already there", folder = "寄件備份匣", messageId = "<fixed@mail.ntust.edu.tw>")
        val result = sender(builder).send(credentials, outgoing(), sentFolder = "寄件備份匣", sessions = sessions)
        assertEquals(SentCopy.ServerFiledItself, result.sentCopy)
        assertEquals(listOf("already there"), server.subjects("寄件備份匣"))
    }

    @Test
    fun `a probe that answers 'not there' files the copy`() = runBlocking {
        val result = sender().send(credentials, outgoing(), sentFolder = "寄件備份匣", sessions = sessions)
        assertEquals(SentCopy.Filed, result.sentCopy)
        assertEquals(listOf("hi"), server.subjects("寄件備份匣"))
    }

    @Test
    fun `no sent folder is never attempted, and still sends`() = runBlocking {
        val result = sender().send(credentials, outgoing(), sentFolder = null, sessions = sessions)
        assertEquals(SentCopy.NotAttempted, result.sentCopy)
        assertEquals(1, delivered.size)
    }
}
