package org.ntust.app.tigerduck.mail.smtp

import org.ntust.app.tigerduck.mail.MailCredentials
import org.ntust.app.tigerduck.mail.compose.MessageBuilder
import org.ntust.app.tigerduck.mail.compose.OutgoingMail
import org.ntust.app.tigerduck.mail.imap.AppendFlag
import org.ntust.app.tigerduck.mail.imap.MailSessionFactory

/**
 * Spec §8.4: send, wait, and APPEND to the sent folder only if no mail with
 * our Message-ID is among its newest ones — correct whether or not the
 * server files its own copy.
 */
class MailSender(
    private val builder: MessageBuilder,
    private val transport: MailTransport,
    private val sessions: MailSessionFactory,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    fun send(credentials: MailCredentials, mail: OutgoingMail, sentFolder: String?): String {
        val built = builder.build(mail)
        transport.send(credentials, built)
        if (sentFolder != null) {
            runCatching {
                pause(SENT_COPY_WAIT_MS)
                sessions.open(credentials).use { session ->
                    if (!session.hasRecentMessageId(sentFolder, built.messageId, SENT_COPY_WINDOW)) {
                        session.append(sentFolder, built.toBytes(), setOf(AppendFlag.SEEN))
                    }
                }
            }
        }
        return built.messageId
    }

    companion object {
        const val SENT_COPY_WAIT_MS = 3_000L
        const val SENT_COPY_WINDOW = 10
    }
}
