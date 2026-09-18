package org.ntust.app.tigerduck.mail.model

import java.time.Instant

/**
 * A mailbox. [name] is already RFC 2047-decoded and bidi-cleaned.
 *
 * [address] is empty when the header named no deliverable mailbox: Mail2000 sends every
 * delivery-failure notice as `From: "Mail Deliver System" <MAILER-DAEMON>`, a bare local part
 * with no domain. The name is worth showing, the token is not an address, and inventing one
 * would be worse than having none -- [address] feeds `MailWarnings.isExternal`, the
 * display-name-mismatch check and reply/forward recipients. Anything that puts an address on
 * the wire must check [isRoutable] first.
 */
data class MailAddress(val name: String?, val address: String) {
    val display: String get() = name?.takeIf { it.isNotBlank() } ?: address

    /** False for a mailbox kept only for its display name; see the class docs. */
    val isRoutable: Boolean get() = address.isNotBlank()
}

data class MailFlags(
    val seen: Boolean,
    val answered: Boolean,
    val flagged: Boolean,
    val deleted: Boolean,
    val draft: Boolean,
) {
    companion object {
        val NONE = MailFlags(seen = false, answered = false, flagged = false, deleted = false, draft = false)
    }
}

/** One row of a folder list. Header text is decoded and cleaned. */
data class MailSummary(
    val uid: Long,
    val from: MailAddress?,
    val replyTo: List<MailAddress>,
    val to: List<MailAddress>,
    val cc: List<MailAddress>,
    val subject: String,
    val sentAt: Instant?,
    val receivedAt: Instant?,
    val flags: MailFlags,
    val sizeBytes: Long,
    val hasAttachments: Boolean,
    val messageId: String?,
    val inReplyTo: String?,
    val references: String?,
)

data class FolderStatus(val uidValidity: Long, val uidNext: Long, val messages: Int, val unseen: Int)

/** Newest first. [nextBeforeSeq] is the sequence number to page back from, or null at the oldest mail. */
data class MailPage(
    val uidValidity: Long,
    val totalMessages: Int,
    val messages: List<MailSummary>,
    val nextBeforeSeq: Int?,
)

/** [partId] is the MIME tree path ("1", "2.1", …) produced by `MimeWalker`. */
data class MailAttachment(
    val partId: String,
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long,
    val contentId: String?,
)

class InlineImage(val contentType: String, val bytes: ByteArray)

/** Text parts plus attachment metadata. Inline images are keyed by Content-ID without angle brackets. */
data class MailBody(
    val html: String?,
    val plain: String?,
    val attachments: List<MailAttachment>,
    val inlineImages: Map<String, InlineImage>,
)
