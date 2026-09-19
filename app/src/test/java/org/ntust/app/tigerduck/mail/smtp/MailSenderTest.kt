package org.ntust.app.tigerduck.mail.smtp

import jakarta.mail.Folder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.mail.MailTestServer
import org.ntust.app.tigerduck.mail.compose.MessageBuilder
import org.ntust.app.tigerduck.mail.compose.OutgoingMail
import org.ntust.app.tigerduck.mail.imap.AngusMailSessionFactory
import org.ntust.app.tigerduck.mail.model.MailAddress

class MailSenderTest {
    @get:Rule val server = MailTestServer()

    private fun count(folder: String) = server.rawStore().use { store ->
        val f = store.getFolder(folder)
        f.open(Folder.READ_ONLY)
        f.messageCount.also { f.close(false) }
    }

    /** Stands in for the repository's held connection. */
    private fun held() = SentCopySession { block ->
        AngusMailSessionFactory(server.config).open(server.credentials).use(block)
    }

    private fun mailToSelf() = OutgoingMail(
        from = MailAddress("測試", server.credentials.address),
        to = listOf(MailAddress(null, server.credentials.address)),
        cc = emptyList(), bcc = emptyList(), subject = "hi", body = "body",
    )

    @Test
    fun `sends and saves exactly one sent copy`() = runBlocking {
        server.createFolders("寄件備份匣")
        val sender = MailSender(MessageBuilder(), AngusMailTransport(server.config), pause = {})
        val result = sender.send(server.credentials, mailToSelf(), sentFolder = "寄件備份匣", sessions = held())
        assertTrue(result.messageId.endsWith("@mail.ntust.edu.tw>"))
        assertEquals(SentCopy.Filed, result.sentCopy)
        assertTrue(server.greenMail.waitForIncomingEmail(5_000, 1))
        assertEquals(1, count("寄件備份匣"))
    }

    @Test
    fun `no second copy when the server already saved one`() = runBlocking {
        server.createFolders("寄件備份匣")
        val sessions = AngusMailSessionFactory(server.config)
        val real = AngusMailTransport(server.config)
        // Stands in for a server that files its own copy of everything sent.
        val autoSaving = MailTransport { creds, message ->
            real.send(creds, message)
            sessions.open(creds).use { it.append("寄件備份匣", message.toBytes(), emptySet()) }
        }
        val result = MailSender(MessageBuilder(), autoSaving, pause = {})
            .send(server.credentials, mailToSelf(), sentFolder = "寄件備份匣", sessions = held())
        assertEquals(SentCopy.ServerFiledItself, result.sentCopy)
        assertEquals(1, count("寄件備份匣"))
    }

    @Test
    fun `a failed sent copy never fails the send, but says so`() = runBlocking {
        // No Sent folder exists on the server, so the probe can't even look: the mail is still
        // sent, nothing is appended blind, and the outcome says the copy was not filed.
        val sender = MailSender(MessageBuilder(), AngusMailTransport(server.config), pause = {})
        val result = sender.send(server.credentials, mailToSelf(), sentFolder = "寄件備份匣", sessions = held())
        assertTrue(server.greenMail.waitForIncomingEmail(5_000, 1))
        assertTrue("the send succeeded", result.messageId.isNotBlank())
        assertFalse("and the lost copy is reported, not swallowed", result.sentCopy.filed)
    }

    @Test
    fun `no sent folder is reported as never attempted`() = runBlocking {
        val sender = MailSender(MessageBuilder(), AngusMailTransport(server.config), pause = {})
        val result = sender.send(server.credentials, mailToSelf(), sentFolder = null, sessions = held())
        assertEquals(SentCopy.NotAttempted, result.sentCopy)
        assertTrue(server.greenMail.waitForIncomingEmail(5_000, 1))
    }
}
