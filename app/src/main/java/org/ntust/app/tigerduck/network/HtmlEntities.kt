package org.ntust.app.tigerduck.network

/**
 * Decode HTML character references that Moodle may emit in assignment titles
 * and course full-names. Handles three forms:
 *
 *   • Numeric decimal — `&#38;` → `&`
 *   • Numeric hexadecimal — `&#x26;` / `&#X26;` → `&`
 *   • Named — `&amp;`, `&lt;`, `&rsquo;`, `&mdash;`, …
 *
 * Decoding is a single left-to-right pass — `&amp;lt;` therefore resolves to
 * the literal `&lt;` (not `<`), preserving doubly-encoded text. Unknown
 * entities are left intact rather than dropped, so a stray `&foo;` survives
 * untouched instead of disappearing. Mirrors the iOS
 * `String.decodingHTMLEntities()` helper.
 */
fun String.decodeHtmlEntities(): String {
    if (!contains('&')) return this

    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c == '&') {
            val semi = findSemicolon(i + 1)
            val decoded = if (semi > i + 1) decodeEntity(substring(i + 1, semi)) else null
            if (decoded != null) {
                out.append(decoded)
                i = semi + 1
                continue
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

// Bounded lookahead so an unrelated `;` later in the string isn't treated as
// the terminator of an unrelated `&`.
private const val MAX_ENTITY_BODY = 10

private fun String.findSemicolon(from: Int): Int {
    val end = minOf(length, from + MAX_ENTITY_BODY)
    for (j in from until end) if (this[j] == ';') return j
    return -1
}

private fun decodeEntity(body: String): String? {
    if (body.isEmpty()) return null
    if (body[0] == '#') return decodeNumericEntity(body.substring(1))
    return NAMED_ENTITIES[body]
}

private fun decodeNumericEntity(digits: String): String? {
    if (digits.isEmpty()) return null
    val code = if (digits[0] == 'x' || digits[0] == 'X') {
        digits.substring(1).toIntOrNull(16)
    } else {
        digits.toIntOrNull(10)
    } ?: return null
    return runCatching { String(Character.toChars(code)) }.getOrNull()
}

// Common HTML5 named entities Moodle text typically uses. Not the full spec
// list — covers the punctuation, symbols, and Latin supplement characters
// likely to appear in assignment titles and course descriptions. Numeric
// entities cover anything outside this set.
private val NAMED_ENTITIES: Map<String, String> = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
    "copy" to "©",
    "reg" to "®",
    "trade" to "™",
    "hellip" to "…",
    "mdash" to "—",
    "ndash" to "–",
    "lsquo" to "‘",
    "rsquo" to "’",
    "sbquo" to "‚",
    "ldquo" to "“",
    "rdquo" to "”",
    "bdquo" to "„",
    "laquo" to "«",
    "raquo" to "»",
    "bull" to "•",
    "middot" to "·",
    "deg" to "°",
    "plusmn" to "±",
    "times" to "×",
    "divide" to "÷",
    "frac12" to "½",
    "frac14" to "¼",
    "frac34" to "¾",
    "sup2" to "²",
    "sup3" to "³",
    "para" to "¶",
    "sect" to "§",
    "iexcl" to "¡",
    "iquest" to "¿",
    "Auml" to "Ä", "auml" to "ä",
    "Ouml" to "Ö", "ouml" to "ö",
    "Uuml" to "Ü", "uuml" to "ü",
    "szlig" to "ß",
    "Eacute" to "É", "eacute" to "é",
    "Egrave" to "È", "egrave" to "è",
    "Aacute" to "Á", "aacute" to "á",
    "Agrave" to "À", "agrave" to "à",
    "Iacute" to "Í", "iacute" to "í",
    "Oacute" to "Ó", "oacute" to "ó",
    "Uacute" to "Ú", "uacute" to "ú",
    "ntilde" to "ñ", "Ntilde" to "Ñ",
)
