package org.ntust.app.tigerduck.mail.mime

import org.ntust.app.tigerduck.mail.model.MailAddress

/** Address lists from headers or from the compose fields. Names are decoded and cleaned. */
object AddressParser {
    private val FOLD = Regex("\r?\n[ \t]")
    private val ADDRESS = Regex("^[^\\s@<>()\",;:]+@[^\\s@<>()\",;:]+\\.[^\\s@<>()\",;:]+$")

    fun parseList(raw: String?): List<MailAddress> {
        if (raw.isNullOrBlank()) return emptyList()
        return splitTopLevel(FOLD.replace(raw, " ")).mapNotNull(::parseOne)
    }

    fun parseOne(raw: String): MailAddress? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val lt = s.lastIndexOf('<')
        val gt = s.lastIndexOf('>')
        if (lt >= 0 && gt > lt) {
            val address = s.substring(lt + 1, gt).trim()
            if (!looksLikeAddress(address)) return null
            val rawName = unescapeQuoted(s.substring(0, lt).trim().removeSurrounding("\""))
            val name = TextCleaning.clean(EncodedWords.decode(rawName)).ifBlank { null }
            return MailAddress(name, address)
        }
        return if (looksLikeAddress(s)) MailAddress(null, s) else null
    }

    fun looksLikeAddress(s: String): Boolean = ADDRESS.matches(s)

    /**
     * RFC 5322 quoted-pairs (`\"` and `\\`) in a display name, undone in one
     * left-to-right pass: a backslash consumes exactly the next character, so
     * the `\` an escaped backslash produces can never act as an escape itself.
     * Chained `replace` calls get that wrong -- `\\"` would unescape to `\"`
     * and then to `"`, losing the quote the sender actually wrote. This is the
     * inverse of `ComposeRules.formatOne`'s quoting, so a name round-trips.
     */
    internal fun unescapeQuoted(s: String): String {
        if ('\\' !in s) return s
        val out = StringBuilder(s.length)
        var escaped = false
        for (c in s) {
            when {
                escaped -> { out.append(c); escaped = false }
                c == '\\' -> escaped = true
                else -> out.append(c)
            }
        }
        // A trailing lone backslash escapes nothing; keep it rather than drop it.
        if (escaped) out.append('\\')
        return out.toString()
    }

    /** Splits on `,` or `;` that are outside quotes and angle brackets. */
    internal fun splitTopLevel(s: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inQuote = false
        var depth = 0
        var escaped = false
        for (c in s) {
            when {
                escaped -> { current.append(c); escaped = false }
                c == '\\' && inQuote -> { current.append(c); escaped = true }
                c == '"' -> { inQuote = !inQuote; current.append(c) }
                c == '<' && !inQuote -> { depth++; current.append(c) }
                c == '>' && !inQuote -> { depth = maxOf(0, depth - 1); current.append(c) }
                (c == ',' || c == ';') && !inQuote && depth == 0 -> { parts += current.toString(); current.clear() }
                else -> current.append(c)
            }
        }
        parts += current.toString()
        return parts
    }
}
