package org.ntust.app.tigerduck.mail.compose

import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.mail.model.MailAddress
import java.io.ByteArrayInputStream
import java.util.Date
import java.util.Properties

class MessageBuilderTest {
    private val builder = MessageBuilder(newId = { "fixed" }, now = { Date(0) })

    private fun mail(attachments: List<OutgoingAttachment> = emptyList()) = OutgoingMail(
        from = MailAddress("測試", "b10000001@mail.ntust.edu.tw"),
        to = listOf(MailAddress(null, "a@x.tw")),
        cc = listOf(MailAddress("C", "c@x.tw")),
        bcc = listOf(MailAddress(null, "hidden@x.tw")),
        subject = "期中考 question",
        body = "你好\nline two",
        attachments = attachments,
        inReplyTo = "<m1@x>",
        references = "<r0@x> <m1@x>",
    )

    private fun reparse(built: BuiltMessage) =
        MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(built.toBytes()))

    @Test
    fun `headers, recipients and a plain-text body`() {
        val built = builder.build(mail())
        assertEquals("<fixed@mail.ntust.edu.tw>", built.messageId)
        assertEquals(listOf("a@x.tw", "c@x.tw", "hidden@x.tw"), built.envelopeRecipients.map { it.address })

        val msg = reparse(built)
        assertEquals("測試", (msg.from.single() as InternetAddress).personal)
        assertEquals("b10000001@mail.ntust.edu.tw", (msg.from.single() as InternetAddress).address)
        assertEquals("期中考 question", msg.subject)
        assertEquals("<fixed@mail.ntust.edu.tw>", msg.messageID)
        assertEquals("<m1@x>", msg.getHeader("In-Reply-To").single())
        assertEquals("<r0@x> <m1@x>", msg.getHeader("References").single().replace(Regex("\\s+"), " "))
        assertNull("Bcc must never be written", msg.getHeader("Bcc"))
        assertEquals(1, msg.getRecipients(Message.RecipientType.CC).size)
        assertEquals("quoted-printable", msg.encoding)
        assertTrue(msg.isMimeType("text/plain"))
        assertEquals("你好\nline two", (msg.content as String).replace("\r\n", "\n").trimEnd())
    }

    @Test
    fun `attachments use RFC 2231 names`() {
        val pdf = "%PDF-1.4\n".toByteArray()
        val built = builder.build(mail(listOf(OutgoingAttachment("報告.pdf", "application/pdf", pdf.size.toLong()) { ByteArrayInputStream(pdf) })))
        val raw = String(built.toBytes(), Charsets.US_ASCII)
        assertTrue(raw, raw.contains("filename*=UTF-8''%E5%A0%B1%E5%91%8A.pdf") || raw.contains("filename*0*=UTF-8''%E5%A0%B1"))

        val multipart = reparse(built).content as MimeMultipart
        assertEquals(2, multipart.count)
        val attachment = multipart.getBodyPart(1)
        assertEquals("報告.pdf", attachment.fileName)
        assertTrue(attachment.isMimeType("application/pdf"))
        assertEquals("%PDF-1.4\n", attachment.inputStream.readBytes().toString(Charsets.US_ASCII))
    }

    @Test
    fun `CRLF in threading headers cannot inject an extra header`() {
        val hostile = mail().copy(
            inReplyTo = "<m1@x>\r\nBcc: attacker@evil.com",
            references = "<r0@x>\r\nBcc: attacker2@evil.com",
        )
        val built = builder.build(hostile)
        val raw = String(built.toBytes(), Charsets.US_ASCII)
        assertFalse(raw, raw.contains("\r\nBcc:", ignoreCase = true))

        val msg = reparse(built)
        assertNull("Bcc must never be written", msg.getHeader("Bcc"))
        assertEquals(listOf("a@x.tw", "c@x.tw", "hidden@x.tw"), built.envelopeRecipients.map { it.address })
    }

    @Test
    fun `control characters are stripped from the subject and the sender's display name too`() {
        // Plain ASCII on purpose: a non-ASCII value would be hidden inside an RFC 2047 encoded
        // word, so only ASCII shows whether the header structure itself is safe.
        val hostile = mail().copy(
            subject = "Question\r\nBcc: attacker@evil.com",
            from = MailAddress("Tester\r\nBcc: attacker2@evil.com", "b10000001@mail.ntust.edu.tw"),
        )
        val built = builder.build(hostile)
        val raw = String(built.toBytes(), Charsets.US_ASCII)
        assertFalse(raw, raw.contains("\r\nBcc:", ignoreCase = true))

        val msg = reparse(built)
        assertNull("Bcc must never be written", msg.getHeader("Bcc"))
        assertEquals("QuestionBcc: attacker@evil.com", msg.subject)
        assertEquals("TesterBcc: attacker2@evil.com", (msg.from.single() as InternetAddress).personal)
    }
}
