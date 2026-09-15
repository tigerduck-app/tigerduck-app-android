package org.ntust.app.tigerduck.mail.compose

import org.ntust.app.tigerduck.mail.mime.AddressParser
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailSummary
import java.io.InputStream

class OutgoingAttachment(
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long,
    val open: () -> InputStream,
)

data class OutgoingMail(
    val from: MailAddress,
    val to: List<MailAddress>,
    val cc: List<MailAddress>,
    val bcc: List<MailAddress>,
    val subject: String,
    val body: String,
    val attachments: List<OutgoingAttachment> = emptyList(),
    val inReplyTo: String? = null,
    val references: String? = null,
)

data class RecipientParse(val addresses: List<MailAddress>, val invalid: List<String>)

/** Plain-text compose rules (spec §6.4). */
object ComposeRules {
    /** The server's SMTP SIZE limit; the check is on the encoded size (spec A.6). */
    const val MAX_ENCODED_BYTES = 52_428_800L

    private val REPLY_PREFIX = Regex("^(re|回覆|答复)\\s*[:：]", RegexOption.IGNORE_CASE)
    private val FORWARD_PREFIX = Regex("^(fwd?|轉寄|转发)\\s*[:：]", RegexOption.IGNORE_CASE)

    fun replySubject(subject: String): String {
        val s = subject.trim()
        return if (REPLY_PREFIX.containsMatchIn(s)) s else "Re: $s"
    }

    fun forwardSubject(subject: String): String {
        val s = subject.trim()
        return if (FORWARD_PREFIX.containsMatchIn(s)) s else "Fwd: $s"
    }

    /** [header] is the localized `school_mail_quote_header` with date and sender filled in. */
    fun quote(original: String, header: String): String = buildString {
        append("\n\n").append(header)
        original.lines().forEach { append("\n> ").append(it) }
    }

    fun replyRecipients(original: MailSummary): List<MailAddress> =
        original.replyTo.ifEmpty { listOfNotNull(original.from) }

    fun replyAllRecipients(original: MailSummary, selfAddress: String): Pair<List<MailAddress>, List<MailAddress>> {
        val to = replyRecipients(original)
        val taken = (to.map { it.address.lowercase() } + selfAddress.lowercase()).toMutableSet()
        val cc = (original.to + original.cc).filter { taken.add(it.address.lowercase()) }
        return to to cc
    }

    fun references(original: MailSummary): String? =
        listOfNotNull(original.references, original.messageId).joinToString(" ").trim().ifEmpty { null }

    fun parseRecipients(input: String): RecipientParse {
        val addresses = mutableListOf<MailAddress>()
        val invalid = mutableListOf<String>()
        AddressParser.splitTopLevel(input).map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
            AddressParser.parseOne(token)?.let(addresses::add) ?: invalid.add(token)
        }
        return RecipientParse(addresses.distinctBy { it.address.lowercase() }, invalid)
    }

    fun formatRecipients(list: List<MailAddress>): String =
        list.joinToString(", ") { if (it.name.isNullOrBlank()) it.address else "${it.name} <${it.address}>" }

    /** UTF-8 body (≤ 3 bytes/char, QP-safe upper bound) + base64 attachments (76-char lines + CRLF) + headers. */
    fun estimateEncodedSize(body: String, attachmentBytes: List<Long>): Long {
        val text = body.length.toLong() * 3
        val attachments = attachmentBytes.sumOf { bytes -> ((bytes + 56) / 57) * 78 + 512 }
        return text + attachments + 4096
    }

    fun fitsSizeLimit(body: String, attachmentBytes: List<Long>): Boolean =
        estimateEncodedSize(body, attachmentBytes) <= MAX_ENCODED_BYTES
}
