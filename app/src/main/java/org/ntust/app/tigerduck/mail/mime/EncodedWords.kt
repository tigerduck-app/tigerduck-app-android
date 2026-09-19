package org.ntust.app.tigerduck.mail.mime

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * RFC 2047 decoding through [MailCharsets], so a `big5` word gets the
 * Big5-HKSCS treatment the spec requires (Angus' own decoder would map it
 * to plain Big5).
 */
object EncodedWords {
    private val FOLD = Regex("\r?\n[ \t]")
    private val WORD = Regex("=\\?([^?\\s]+)\\?([BbQq])\\?([^?\\s]*)\\?=")
    private val BETWEEN_WORDS = Regex("(\\?=)\\s+(=\\?)")

    fun decode(raw: String?): String {
        if (raw.isNullOrEmpty()) return raw.orEmpty()
        val unfolded = FOLD.replace(raw, " ")
        // Whitespace between two adjacent encoded words is not displayed (RFC 2047 §6.2).
        val joined = BETWEEN_WORDS.replace(unfolded, "$1$2")
        return WORD.replace(joined) { m ->
            val label = m.groupValues[1].substringBefore('*') // RFC 2231 language suffix
            val text = m.groupValues[3]
            val bytes = if (m.groupValues[2].equals("B", ignoreCase = true)) {
                runCatching { Base64.getMimeDecoder().decode(text) }.getOrNull()
            } else {
                decodeQ(text)
            }
            if (bytes == null || (bytes.isEmpty() && text.isNotEmpty())) m.value else MailCharsets.decode(bytes, label)
        }
    }

    private fun decodeQ(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '_') {
                out.write(0x20); i++; continue
            }
            if (c == '=' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex); i += 3; continue
                }
            }
            out.write(c.code and 0xFF); i++
        }
        return out.toByteArray()
    }
}
