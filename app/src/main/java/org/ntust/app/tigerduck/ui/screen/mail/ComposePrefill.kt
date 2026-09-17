package org.ntust.app.tigerduck.ui.screen.mail

import org.ntust.app.tigerduck.mail.compose.ComposeRules
import org.ntust.app.tigerduck.mail.model.MailSummary

data class ComposeDraft(
    val to: String,
    val cc: String,
    val subject: String,
    val body: String,
    val inReplyTo: String?,
    val references: String?,
)

/** What reply, reply all, forward and draft-editing put in the fields (spec §6.4). Strings come from the caller. */
object ComposePrefill {
    class Labels(
        val quoteHeader: (date: String, sender: String) -> String,
        val forwardedHeader: String,
        val from: (String) -> String,
        val date: (String) -> String,
        val subject: (String) -> String,
        val to: (String) -> String,
    )

    fun reply(original: MailSummary, originalText: String, all: Boolean, selfAddress: String, labels: Labels): ComposeDraft {
        val (to, cc) = if (all) {
            ComposeRules.replyAllRecipients(original, selfAddress)
        } else {
            ComposeRules.replyRecipients(original) to emptyList()
        }
        val header = labels.quoteHeader(MailDateFormat.full(original.sentAt ?: original.receivedAt), sender(original))
        return ComposeDraft(
            to = ComposeRules.formatRecipients(to),
            cc = ComposeRules.formatRecipients(cc),
            subject = ComposeRules.replySubject(original.subject),
            body = ComposeRules.quote(originalText, header),
            inReplyTo = original.messageId,
            references = ComposeRules.references(original),
        )
    }

    fun forward(original: MailSummary, originalText: String, labels: Labels): ComposeDraft {
        val block = listOf(
            labels.forwardedHeader,
            labels.from(sender(original)),
            labels.date(MailDateFormat.full(original.sentAt ?: original.receivedAt)),
            labels.subject(original.subject),
            labels.to(ComposeRules.formatRecipients(original.to)),
        ).joinToString("\n")
        return ComposeDraft(
            to = "",
            cc = "",
            subject = ComposeRules.forwardSubject(original.subject),
            body = "\n\n$block\n\n$originalText",
            inReplyTo = null,
            references = null,
        )
    }

    fun draft(original: MailSummary, text: String) = ComposeDraft(
        to = ComposeRules.formatRecipients(original.to),
        cc = ComposeRules.formatRecipients(original.cc),
        subject = original.subject,
        body = text,
        inReplyTo = original.inReplyTo,
        references = original.references,
    )

    private fun sender(original: MailSummary): String =
        original.from?.let { ComposeRules.formatRecipients(listOf(it)) }.orEmpty()
}
