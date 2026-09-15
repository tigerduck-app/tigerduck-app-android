package org.ntust.app.tigerduck.mail.smtp

import jakarta.mail.Session
import org.ntust.app.tigerduck.mail.MailCredentials
import org.ntust.app.tigerduck.mail.MailErrors
import org.ntust.app.tigerduck.mail.MailProperties
import org.ntust.app.tigerduck.mail.MailServerConfig
import org.ntust.app.tigerduck.mail.compose.BuiltMessage

fun interface MailTransport {
    /** Throws [org.ntust.app.tigerduck.mail.MailError]. */
    fun send(credentials: MailCredentials, message: BuiltMessage)
}

/** SMTP 465 implicit TLS, AUTH LOGIN with the bare student ID. */
class AngusMailTransport(private val config: MailServerConfig) : MailTransport {
    override fun send(credentials: MailCredentials, message: BuiltMessage) {
        val session = Session.getInstance(MailProperties.smtp(config))
        val transport = session.getTransport(MailProperties.smtpProtocol(config))
        try {
            transport.connect(config.host, config.smtpPort, credentials.loginName, credentials.password)
            transport.sendMessage(message.mime, message.envelopeRecipients.toTypedArray())
        } catch (e: Exception) {
            throw MailErrors.classify(e)
        } finally {
            runCatching { transport.close() }
        }
    }
}
