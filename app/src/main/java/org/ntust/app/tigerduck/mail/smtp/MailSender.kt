package org.ntust.app.tigerduck.mail.smtp

import kotlinx.coroutines.delay
import org.ntust.app.tigerduck.mail.MailCredentials
import org.ntust.app.tigerduck.mail.compose.MessageBuilder
import org.ntust.app.tigerduck.mail.compose.OutgoingMail
import org.ntust.app.tigerduck.mail.imap.AppendFlag
import org.ntust.app.tigerduck.mail.imap.MailSessionFactory

/**
 * Spec §8.4: send, wait, and APPEND to the sent folder only if no mail with
 * our Message-ID is among its newest ones — correct whether or not the
 * server files its own copy.
 *
 * The wait is a [delay], not a `Thread.sleep`: this runs on the IO dispatcher,
 * where a sleeping thread is a thread nothing else can use, and a send the user
 * navigated away from would sit here for the full three seconds after its
 * coroutine was cancelled instead of stopping.
 */
class MailSender(
    private val builder: MessageBuilder,
    private val transport: MailTransport,
    private val sessions: MailSessionFactory,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun send(credentials: MailCredentials, mail: OutgoingMail, sentFolder: String?): String {
        val built = builder.build(mail)
        transport.send(credentials, built)
        if (sentFolder != null) {
            // Outside the runCatching: a cancelled send must stop here, and
            // runCatching would swallow the CancellationException delay throws.
            pause(SENT_COPY_WAIT_MS)
            runCatching {
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
