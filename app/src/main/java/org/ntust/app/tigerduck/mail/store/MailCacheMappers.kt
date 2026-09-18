package org.ntust.app.tigerduck.mail.store

import org.ntust.app.tigerduck.data.model.mail.AttachmentDto
import org.ntust.app.tigerduck.data.model.mail.BodyCacheDto
import org.ntust.app.tigerduck.data.model.mail.InlineImageDto
import org.ntust.app.tigerduck.data.model.mail.MailAddressDto
import org.ntust.app.tigerduck.data.model.mail.MailSummaryDto
import org.ntust.app.tigerduck.mail.model.InlineImage
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailAttachment
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailSummary
import java.time.Instant
import java.util.Base64

fun MailAddress.toDto() = MailAddressDto(name, address)

/**
 * Both fields are nullable on disk, so an entry with neither is the only one worth dropping: a
 * cached sender that has a name but no deliverable address (a Mail2000 bounce, see [MailAddress])
 * must come back with its name, or reloading the folder from cache would turn it back into
 * "(no sender)". The empty address keeps it unroutable, exactly as the parser left it.
 */
fun MailAddressDto.toModel(): MailAddress? {
    val label = name?.takeIf { it.isNotBlank() }
    val mailbox = address.orEmpty().trim()
    return if (label == null && mailbox.isEmpty()) null else MailAddress(label, mailbox)
}

fun MailSummary.toDto() = MailSummaryDto(
    uid = uid,
    from = from?.toDto(),
    replyTo = replyTo.map { it.toDto() },
    to = to.map { it.toDto() },
    cc = cc.map { it.toDto() },
    subject = subject,
    sentAtMillis = sentAt?.toEpochMilli() ?: 0L,
    receivedAtMillis = receivedAt?.toEpochMilli() ?: 0L,
    seen = flags.seen,
    answered = flags.answered,
    flagged = flags.flagged,
    draft = flags.draft,
    sizeBytes = sizeBytes,
    hasAttachments = hasAttachments,
    messageId = messageId,
    inReplyTo = inReplyTo,
    references = references,
    returnPath = returnPath,
)

fun MailSummaryDto.toModel() = MailSummary(
    uid = uid,
    from = from?.toModel(),
    replyTo = replyTo.orEmpty().mapNotNull { it.toModel() },
    to = to.orEmpty().mapNotNull { it.toModel() },
    cc = cc.orEmpty().mapNotNull { it.toModel() },
    subject = subject.orEmpty(),
    sentAt = sentAtMillis.takeIf { it > 0 }?.let(Instant::ofEpochMilli),
    receivedAt = receivedAtMillis.takeIf { it > 0 }?.let(Instant::ofEpochMilli),
    flags = MailFlags(seen = seen, answered = answered, flagged = flagged, deleted = false, draft = draft),
    sizeBytes = sizeBytes,
    hasAttachments = hasAttachments,
    messageId = messageId,
    inReplyTo = inReplyTo,
    references = references,
    returnPath = returnPath,
)

fun MailBody.toDto(uidValidity: Long, uid: Long) = BodyCacheDto(
    version = MailCache.VERSION,
    uidValidity = uidValidity,
    uid = uid,
    html = html,
    plain = plain,
    attachments = attachments.map { AttachmentDto(it.partId, it.fileName, it.contentType, it.sizeBytes, it.contentId) },
    inlineImages = inlineImages.map { (cid, image) ->
        InlineImageDto(cid, image.contentType, Base64.getEncoder().encodeToString(image.bytes))
    },
)

fun BodyCacheDto.toModel() = MailBody(
    html = html,
    plain = plain,
    attachments = attachments.orEmpty().mapNotNull { a ->
        val partId = a.partId ?: return@mapNotNull null
        MailAttachment(
            partId = partId,
            fileName = a.fileName.orEmpty().ifBlank { "attachment" },
            contentType = a.contentType ?: "application/octet-stream",
            sizeBytes = a.sizeBytes,
            contentId = a.contentId,
        )
    },
    inlineImages = inlineImages.orEmpty().mapNotNull { i ->
        val cid = i.contentId ?: return@mapNotNull null
        val bytes = runCatching { Base64.getDecoder().decode(i.base64.orEmpty()) }.getOrNull() ?: return@mapNotNull null
        cid to InlineImage(i.contentType ?: "application/octet-stream", bytes)
    }.toMap(),
)
