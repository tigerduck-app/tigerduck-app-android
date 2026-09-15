package org.ntust.app.tigerduck.mail.smtp

import jakarta.mail.Folder
import org.junit.Assert.assertEquals
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

    private fun mailToSelf() = OutgoingMail(
        from = MailAddress("測試", server.credentials.address),
        to = listOf(MailAddress(null, server.credentials.address)),
        cc = emptyList(), bcc = emptyList(), subject = "hi", body = "body",
    )

    @Test
    fun `sends and saves exactly one sent copy`() {
        server.createFolders("寄件備份匣")
        val sessions = AngusMailSessionFactory(server.config)
        val sender = MailSender(MessageBuilder(), AngusMailTransport(server.config), sessions, pause = {})
        val id = sender.send(server.credentials, mailToSelf(), sentFolder = "寄件備份匣")
        assertTrue(id.endsWith("@mail.ntust.edu.tw>"))
        assertTrue(server.greenMail.waitForIncomingEmail(5_000, 1))
        assertEquals(1, count("寄件備份匣"))
    }

    @Test
    fun `no second copy when the server already saved one`() {
        server.createFolders("寄件備份匣")
        val sessions = AngusMailSessionFactory(server.config)
        val real = AngusMailTransport(server.config)
        // Stands in for a server that files its own copy of everything sent.
        val autoSaving = MailTransport { creds, message ->
            real.send(creds, message)
            sessions.open(creds).use { it.append("寄件備份匣", message.toBytes(), emptySet()) }
        }
        MailSender(MessageBuilder(), autoSaving, sessions, pause = {})
            .send(server.credentials, mailToSelf(), sentFolder = "寄件備份匣")
        assertEquals(1, count("寄件備份匣"))
    }

    @Test
    fun `a failed sent copy never fails the send`() {
        // No 寄件備份匣 exists, so the APPEND fails; the mail is still sent.
        val sender = MailSender(MessageBuilder(), AngusMailTransport(server.config), AngusMailSessionFactory(server.config), pause = {})
        sender.send(server.credentials, mailToSelf(), sentFolder = "寄件備份匣")
        assertTrue(server.greenMail.waitForIncomingEmail(5_000, 1))
    }
}
