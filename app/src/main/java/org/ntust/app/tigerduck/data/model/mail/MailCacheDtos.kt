package org.ntust.app.tigerduck.data.model.mail

// Gson-persisted mail cache files. Upgrade-safe rule: reference fields are
// nullable, everything else is a primitive — Gson's Unsafe path skips Kotlin
// defaults, so a non-null field missing from an older file reads as null.
// R8 keeps these through the existing `data.model.**` keep rule.

data class MailAddressDto(val name: String?, val address: String?)

data class MailSummaryDto(
    val uid: Long,
    val from: MailAddressDto?,
    val replyTo: List<MailAddressDto>?,
    val to: List<MailAddressDto>?,
    val cc: List<MailAddressDto>?,
    val subject: String?,
    val sentAtMillis: Long,
    val receivedAtMillis: Long,
    val seen: Boolean,
    val answered: Boolean,
    val flagged: Boolean,
    val draft: Boolean,
    val sizeBytes: Long,
    val hasAttachments: Boolean,
    val messageId: String?,
    val inReplyTo: String?,
    val references: String?,
    /** Absent from every page cached before this field existed: null then means "not known", and the mail is judged exactly as it was before. */
    val returnPath: String?,
)

/** [nextBeforeSeq] 0 means the page reached the oldest mail. */
data class FolderCacheDto(
    val version: Int,
    val uidValidity: Long,
    val totalMessages: Int,
    val nextBeforeSeq: Int,
    val messages: List<MailSummaryDto>?,
)

data class AttachmentDto(
    val partId: String?,
    val fileName: String?,
    val contentType: String?,
    val sizeBytes: Long,
    val contentId: String?,
)

data class InlineImageDto(val contentId: String?, val contentType: String?, val base64: String?)

data class BodyCacheDto(
    val version: Int,
    val uidValidity: Long,
    val uid: Long,
    val html: String?,
    val plain: String?,
    val attachments: List<AttachmentDto>?,
    val inlineImages: List<InlineImageDto>?,
)

/**
 * One mail's raw RFC 822 source. Written to its own file next to the bodies, so it shares
 * their LRU budget; [source] is nullable like every other reference field here, and a file
 * without it is treated as a miss rather than handing a null to a non-null parameter.
 */
data class SourceCacheDto(
    val version: Int,
    val uidValidity: Long,
    val uid: Long,
    val source: String?,
)
