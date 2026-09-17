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

    /** RFC 2045 §6.7 line-length cap the quoted-printable encoder wraps at. */
    private const val QP_LINE_LENGTH = 76

    /** RFC 5322 specials: a display name containing any of these must be quoted. */
    private val NAME_SPECIALS = charArrayOf(',', ';', '"', '<', '>', '(', ')', ':', '@', '\\')

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

    /** The school server has no SMTPUTF8 (spec A.6): a non-ASCII local part or domain is never
     *  deliverable here, so compose reports it the same as any other malformed token -- unlike
     *  [AddressParser] itself, which stays permissive so reading already-delivered mail (whose
     *  sender compose never chose) still shows a "From" instead of hiding it. */
    fun parseRecipients(input: String): RecipientParse {
        val addresses = mutableListOf<MailAddress>()
        val invalid = mutableListOf<String>()
        AddressParser.splitTopLevel(input).map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
            val parsed = AddressParser.parseOne(token)
            if (parsed != null && parsed.address.all { it.code < 0x80 }) addresses.add(parsed) else invalid.add(token)
        }
        return RecipientParse(addresses.distinctBy { it.address.lowercase() }, invalid)
    }

    fun formatRecipients(list: List<MailAddress>): String = list.joinToString(", ", transform = ::formatOne)

    /** Quotes a display name containing an RFC 5322 special, escaping `"` and
     *  `\` with a backslash so the token parses back through [AddressParser.parseOne]. */
    private fun formatOne(a: MailAddress): String {
        val name = a.name
        if (name.isNullOrBlank()) return a.address
        val display = if (name.any { it in NAME_SPECIALS }) {
            "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } else {
            name
        }
        return "$display <${a.address}>"
    }

    /**
     * Estimated size of the built message: a true upper bound for the `text/plain; charset=utf-8`
     * body once encoded quoted-printable and for a locally picked, not-yet-encoded attachment
     * (base64, 76-char lines + CRLF), plus a header-overhead buffer.
     *
     * [attachmentBytes] are raw (decoded) bytes that still need that base64 growth applied.
     * [encodedAttachmentBytes] are counted at the size a server already reported for them (a
     * forwarded or reopened draft's original attachment, via IMAP BODYSTRUCTURE) -- exact for an
     * original part that was itself base64, only *approximate* for one encoded some other way
     * (7bit/8bit/quoted-printable), since this never re-derives what encoding it as base64 would
     * actually cost. Re-applying the base64 growth formula on top of an already-encoded size would
     * double-count it and reject attachments that fit comfortably, so those are added to the total
     * as-is instead. If this estimate still undershoots the real built message, the server's own
     * SIZE limit rejects it and the compose form is kept (spec §8.4) -- this is a fail-fast local
     * budget check, not the only thing standing between the user and an oversized send.
     */
    fun estimateEncodedSize(body: String, attachmentBytes: List<Long>, encodedAttachmentBytes: List<Long> = emptyList()): Long {
        val text = quotedPrintableUpperBound(body)
        val raw = attachmentBytes.sumOf { bytes -> ((bytes + 56) / 57) * 78 + 512 }
        val encoded = encodedAttachmentBytes.sum()
        return text + raw + encoded + 4096
    }

    /**
     * Simulates quoted-printable encoding byte-for-byte to bound its output
     * size, counting real UTF-8 bytes rather than UTF-16 code units: a
     * printable ASCII byte (33-126, excluding `=`) costs 1 output byte,
     * everything else (UTF-8 continuation/lead bytes of non-ASCII text,
     * control characters, `=` itself) costs 3 (`=XX`), and a soft line break
     * (`=CRLF`, 3 bytes) is charged whenever a line would exceed 76 columns.
     * Every rule here rounds toward the real encoder's worst case (or worse),
     * so this can only over-count, never under-count -- undercounting is
     * what let CJK bodies whose UTF-8 encoding is ~3 bytes/char (each of
     * which then triples again under QP) sail past the 50 MB gate.
     */
    private fun quotedPrintableUpperBound(body: String): Long {
        var total = 0L
        var column = 0
        for (byte in body.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            val cost = if (value in 33..126 && value != '='.code) 1 else 3
            if (column + cost > QP_LINE_LENGTH) {
                total += 3
                column = 0
            }
            total += cost
            column += cost
        }
        return total
    }

    fun fitsSizeLimit(body: String, attachmentBytes: List<Long>, encodedAttachmentBytes: List<Long> = emptyList()): Boolean =
        estimateEncodedSize(body, attachmentBytes, encodedAttachmentBytes) <= MAX_ENCODED_BYTES
}
