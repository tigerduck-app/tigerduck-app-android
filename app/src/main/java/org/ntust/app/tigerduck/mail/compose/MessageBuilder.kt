package org.ntust.app.tigerduck.mail.compose

import jakarta.activation.DataHandler
import jakarta.activation.DataSource
import jakarta.mail.Message
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimeUtility
import org.ntust.app.tigerduck.mail.MailProperties
import org.ntust.app.tigerduck.mail.MailServerConfig
import org.ntust.app.tigerduck.mail.model.MailAddress
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.Properties
import java.util.UUID

class BuiltMessage(val mime: MimeMessage, val messageId: String, val envelopeRecipients: List<InternetAddress>) {
    /** Serializes the message; attachment streams are opened again each time. */
    fun toBytes(): ByteArray = ByteArrayOutputStream().also { mime.writeTo(it) }.toByteArray()
}

/** UTF-8 plain text, quoted-printable; attachments base64 with RFC 2231 names (spec §8.4). */
class MessageBuilder(
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Date = { Date() },
) {
    fun build(mail: OutgoingMail): BuiltMessage {
        MailProperties.installSystemProperties()
        val session = Session.getInstance(Properties())
        val messageId = "<${newId()}@${MailServerConfig.DOMAIN}>"
        val msg = FixedIdMessage(session, messageId)
        msg.setFrom(internet(mail.from))
        msg.setRecipients(Message.RecipientType.TO, mail.to.map(::internet).toTypedArray())
        if (mail.cc.isNotEmpty()) msg.setRecipients(Message.RecipientType.CC, mail.cc.map(::internet).toTypedArray())
        msg.setSubject(mail.subject, "UTF-8")
        msg.sentDate = now()
        mail.inReplyTo?.let { msg.setHeader("In-Reply-To", it) }
        mail.references?.let { msg.setHeader("References", MimeUtility.fold("References: ".length, it)) }
        if (mail.attachments.isEmpty()) {
            msg.setText(mail.body, "UTF-8")
            msg.setHeader("Content-Transfer-Encoding", "quoted-printable")
        } else {
            val text = MimeBodyPart().apply {
                setText(mail.body, "UTF-8")
                setHeader("Content-Transfer-Encoding", "quoted-printable")
            }
            val multipart = MimeMultipart("mixed")
            multipart.addBodyPart(text)
            mail.attachments.forEach { multipart.addBodyPart(attachmentPart(it)) }
            msg.setContent(multipart)
        }
        msg.saveChanges()
        val envelope = (mail.to + mail.cc + mail.bcc).distinctBy { it.address.lowercase() }.map(::internet)
        return BuiltMessage(msg, messageId, envelope)
    }

    private fun internet(a: MailAddress) = InternetAddress(a.address, a.name?.takeIf { it.isNotBlank() }, "UTF-8")

    private fun attachmentPart(a: OutgoingAttachment) = MimeBodyPart().apply {
        dataHandler = DataHandler(object : DataSource {
            override fun getInputStream(): InputStream = a.open()
            override fun getOutputStream(): OutputStream = throw IOException("read-only")
            override fun getContentType(): String = a.contentType
            override fun getName(): String = a.fileName
        })
        fileName = a.fileName
        disposition = Part.ATTACHMENT
        setHeader("Content-Transfer-Encoding", "base64")
    }

    /** MimeMessage regenerates Message-ID on saveChanges; keep ours so the sent-copy check can find it. */
    private class FixedIdMessage(session: Session, private val id: String) : MimeMessage(session) {
        override fun updateMessageID() {
            setHeader("Message-ID", id)
        }
    }
}
