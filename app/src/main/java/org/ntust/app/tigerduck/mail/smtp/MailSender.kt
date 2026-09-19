package org.ntust.app.tigerduck.mail.smtp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.ntust.app.tigerduck.mail.MailCredentials
import org.ntust.app.tigerduck.mail.MailErrors
import org.ntust.app.tigerduck.mail.compose.BuiltMessage
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
 *
 * Filing the copy never fails the send -- the mail is delivered before any of
 * it runs -- but it is no longer silent either: whatever happened comes back in
 * [SendResult.sentCopy] for the caller to report.
 */
class MailSender(
    private val builder: MessageBuilder,
    private val transport: MailTransport,
    private val sessions: MailSessionFactory,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun send(credentials: MailCredentials, mail: OutgoingMail, sentFolder: String?): SendResult {
        val built = builder.build(mail)
        transport.send(credentials, built)
        // From here on the mail is delivered; nothing below may throw a send failure.
        val copy = if (sentFolder == null) SentCopy.NotAttempted else fileCopy(credentials, built, sentFolder)
        return SendResult(built.messageId, copy)
    }

    private suspend fun fileCopy(
        credentials: MailCredentials,
        built: BuiltMessage,
        sentFolder: String,
    ): SentCopy {
        // Outside the try: a cancelled send must stop here, and a swallowed
        // CancellationException would be reported as a failed sent copy.
        pause(SENT_COPY_WAIT_MS)
        return try {
            sessions.open(credentials).use { session ->
                val probe = SentCopyPolicy.probe {
                    session.hasRecentMessageId(sentFolder, built.messageId, SENT_COPY_WINDOW)
                }
                if (!SentCopyPolicy.appends(probe)) {
                    SentCopyPolicy.declined(probe)
                } else {
                    session.append(sentFolder, built.toBytes(), setOf(AppendFlag.SEEN))
                    SentCopy.Filed
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SentCopy.Failed(MailErrors.classify(e))
        }
    }

    companion object {
        const val SENT_COPY_WAIT_MS = 3_000L
        const val SENT_COPY_WINDOW = 10
    }
}
