package org.ntust.app.tigerduck.mail

import com.icegreen.greenmail.user.GreenMailUser
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.junit.rules.ExternalResource
import java.util.Date
import java.util.Properties

/**
 * An in-memory IMAP + SMTP server (plain, no TLS) holding one school
 * account. GreenMail supports extensions Mail2000 lacks; the code under
 * test must not use them, and the tests don't either.
 */
class MailTestServer : ExternalResource() {
    lateinit var greenMail: GreenMail
        private set
    lateinit var user: GreenMailUser
        private set

    val credentials = MailCredentials(studentId = "b10000001", password = "pw")
    val config = MailServerConfig(
        host = "127.0.0.1",
        imapPort = ServerSetupTest.IMAP.port,
        smtpPort = ServerSetupTest.SMTP.port,
        secure = false,
    )

    override fun before() {
        greenMail = GreenMail(ServerSetupTest.SMTP_IMAP).apply { start() }
        user = greenMail.setUser(credentials.address, credentials.loginName, credentials.password)
    }

    override fun after() {
        greenMail.stop()
    }

    /** A plain Angus store for test setup and assertions, outside the code under test. */
    fun rawStore(): Store = Session.getInstance(Properties()).getStore("imap").apply {
        connect(config.host, config.imapPort, credentials.loginName, credentials.password)
    }

    fun createFolders(vararg names: String) {
        rawStore().use { store ->
            names.forEach { name ->
                val folder = store.getFolder(name)
                if (!folder.exists()) folder.create(Folder.HOLDS_MESSAGES)
            }
        }
    }

    fun deliver(
        subject: String,
        body: String = "body",
        from: String = "someone@example.com",
        html: String? = null,
        messageId: String? = null,
    ) {
        val session = Session.getInstance(Properties())
        val msg = object : MimeMessage(session) {
            override fun updateMessageID() {
                if (messageId != null) setHeader("Message-ID", messageId) else super.updateMessageID()
            }
        }
        msg.setFrom(InternetAddress(from))
        msg.setRecipients(Message.RecipientType.TO, credentials.address)
        msg.setSubject(subject, "UTF-8")
        if (html == null) msg.setText(body, "UTF-8") else msg.setContent(html, "text/html; charset=UTF-8")
        msg.sentDate = Date()
        msg.saveChanges()
        user.deliver(msg)
    }

    fun deliverEml(resource: String) {
        val stream = requireNotNull(javaClass.getResourceAsStream(resource)) { "missing $resource" }
        user.deliver(MimeMessage(Session.getInstance(Properties()), stream))
    }
}
