package org.ntust.app.tigerduck.mail.smtp

import jakarta.mail.Session
import jakarta.mail.URLName
import org.eclipse.angus.mail.smtp.SMTPTransport
import org.ntust.app.tigerduck.mail.MailCredentials
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.MailErrors
import org.ntust.app.tigerduck.mail.MailProperties
import org.ntust.app.tigerduck.mail.MailServerConfig
import org.ntust.app.tigerduck.mail.MailServerConfigSource
import org.ntust.app.tigerduck.mail.MailTransportSecurity
import org.ntust.app.tigerduck.mail.compose.BuiltMessage
import javax.net.ssl.SSLSocketFactory

fun interface MailTransport {
    /** Throws [org.ntust.app.tigerduck.mail.MailError]. */
    fun send(credentials: MailCredentials, message: BuiltMessage)
}

/** SMTP 465 implicit TLS, AUTH LOGIN with the bare student ID. */
class AngusMailTransport(
    private val configs: MailServerConfigSource,
    /** The app's TLS; see [MailProperties]. */
    private val tls: SSLSocketFactory? = null,
) : MailTransport {
    constructor(config: MailServerConfig) : this(MailServerConfigSource { config })

    override fun send(credentials: MailCredentials, message: BuiltMessage) {
        val config = configs.current()
        val session = Session.getInstance(MailProperties.smtp(config, tls))
        val protocol = MailProperties.smtpProtocol(config)
        val transport = EndOfMailWatchingTransport(
            session, URLName(protocol, null, -1, null, null, null), protocol,
            ssl = config.smtp.security == MailTransportSecurity.IMPLICIT_TLS,
        )
        try {
            transport.connect(config.smtp.host, config.smtp.port, credentials.loginName, credentials.password)
            transport.sendMessage(message.mime, message.envelopeRecipients.toTypedArray())
        } catch (e: Exception) {
            throw if (transport.endOfMailUnanswered) MailError.SendUnconfirmed(e) else MailErrors.classify(e)
        } finally {
            runCatching { transport.close() }
        }
    }
}

/**
 * Angus's SMTP transport, as [Session.getTransport] would make it for [protocol], watching the "."
 * that ends the mail. Once it went out the server holds the whole mail, and only its reply says
 * whether the mail was delivered (tigerduck-app-android#377).
 */
private class EndOfMailWatchingTransport(
    session: Session,
    url: URLName,
    protocol: String,
    ssl: Boolean,
) : SMTPTransport(session, url, protocol, ssl) {
    // SMTPTransport.finishData sends the "." through sendCommand, then reads its
    // reply through readServerResponse, and on a refusal sends RSET.
    private var finishing = false
    private var endOfMailSent = false
    private var endOfMailReply: Int? = null

    /**
     * The end of the mail went out and no reply to it came: the connection closed (Angus reads
     * that as code -1) or the read failed. A refusal is a reply, 4xx or 5xx.
     */
    val endOfMailUnanswered: Boolean
        get() = endOfMailSent && (endOfMailReply ?: -1) !in 100..599

    override fun finishData() {
        finishing = true
        try {
            super.finishData()
        } finally {
            finishing = false
        }
    }

    override fun sendCommand(cmd: String) {
        super.sendCommand(cmd)
        if (finishing && cmd == ".") endOfMailSent = true
    }

    override fun readServerResponse(): Int {
        val code = super.readServerResponse()
        // The first reply after the ".", not the RSET's that follows a refusal.
        if (finishing && endOfMailSent && endOfMailReply == null) endOfMailReply = code
        return code
    }
}
