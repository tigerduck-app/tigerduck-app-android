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
    /**
     * The `Return-Path` header, or null where it was not fetched or the mail carries none. A
     * null reverse-path (`<>`) marks a delivery status notification; see
     * `MailWarnings.isBounce`. Written by the receiving server, so unlike the `From` display
     * name it is not the sender's to choose.
     */
    val returnPath: String? = null,
)

/**
 * One list row: a [summary] together with the real folder it lives in.
 *
 * A UID is unique only within its folder, so Inbox and Sent routinely both hold a UID 42
 * belonging to two entirely different mails. Every row therefore carries its own folder, and
 * everything a row leads to -- opening it, marking it read, moving or deleting it -- addresses
 * [folder], never whichever folder chip happens to be selected. [key] is that (folder, uid)
 * identity as a single string, for list keys and for finding a row again after an update.
 */
data class MailRow(val folder: String, val summary: MailSummary) {
    val uid: Long get() = summary.uid

    val key: String get() = key(folder, uid)

    companion object {
        /** The key the row of [folder] and [uid] has, for finding it without its summary in hand. */
        fun key(folder: String, uid: Long): String = "$folder\u0000$uid"
    }
}

data class FolderStatus(val uidValidity: Long, val uidNext: Long, val messages: Int, val unseen: Int)

/**
 * Newest first. [nextBeforeSeq] is the sequence number to page back from, or null at the oldest mail.
 *
 * [windowUids] spans the UIDs of the whole sequence range the page read, `\Deleted` mail included,
 * though [messages] leaves that mail out: a row the list holds inside it that [messages] lacks is
 * gone from the server. Null where unknown -- a page from the cache, or from the demo mailbox.
 */
data class MailPage(
    val uidValidity: Long,
    val totalMessages: Int,
    val messages: List<MailSummary>,
    val nextBeforeSeq: Int?,
    val windowUids: LongRange? = null,
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
