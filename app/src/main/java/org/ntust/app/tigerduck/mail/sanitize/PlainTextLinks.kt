package org.ntust.app.tigerduck.mail.sanitize

/** A link in plain text: [start] until [end] (exclusive) of the text, and the [href] a tap opens. */
data class PlainTextLink(val start: Int, val end: Int, val href: String)

/**
 * Finds what a plain-text mail's reader would tap: `http(s)` URLs, `mailto:` links and bare
 * addresses, which become `mailto:` links. What a link opens is never decided here -- the message
 * screen sends every tap through the same check an HTML link gets.
 *
 * A URL runs as far as the characters RFC 3986 allows in one, so it ends at a space, at a quote or
 * angle bracket, and at the first non-ASCII character: Chinese text often runs straight on from a
 * URL with no space in between. Sentence punctuation after it, and a closing bracket it never
 * opened, are left out. The schemes are matched only where no ASCII letter or digit precedes them,
 * checked by hand rather than with `\b`, which Android's regex engine also breaks after a Chinese
 * character and the JVM's does not.
 */
object PlainTextLinks {
    private const val URL_CHARS = """A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%"""
    private val URL = Regex("""(?<![A-Za-z0-9])https?://[$URL_CHARS]+""", RegexOption.IGNORE_CASE)
    private val MAILTO = Regex(
        """(?<![A-Za-z0-9])mailto:[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,}(?:\?[$URL_CHARS]*)?""",
        RegexOption.IGNORE_CASE,
    )
    private val ADDRESS = Regex("""(?<![A-Za-z0-9._%+\-])[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,}(?![A-Za-z0-9\-])""")
    private const val TRAILING = ".,;:!?'*"

    /** Longer than this, a URL or mailto: link is left as text. */
    internal const val MAX_LINK_LENGTH = 2_048

    fun find(text: String): List<PlainTextLink> {
        val urls = schemeLinks(URL, text)
        // A mailto: link inside a URL -- `?next=mailto:...` -- is part of that URL, not a link of
        // its own: overlapping links would leave which one a tap opens to chance.
        val mailtos = outside(schemeLinks(MAILTO, text), urls)
        val taken = (urls + mailtos).sortedBy { it.start }
        val addresses = outside(
            ADDRESS.findAll(text).map { PlainTextLink(it.range.first, it.range.last + 1, "mailto:${it.value}") }.toList(),
            taken,
        )
        return (taken + addresses).sortedBy { it.start }
    }

    /** [pattern]'s matches, trimmed ([trimmedEnd]), without the ones that name nothing or run past [MAX_LINK_LENGTH]. */
    private fun schemeLinks(pattern: Regex, text: String): List<PlainTextLink> = pattern.findAll(text).mapNotNull { match ->
        val start = match.range.first
        val end = trimmedEnd(text, start, match.range.last + 1)
        val href = text.substring(start, end)
        when {
            // `https://` alone, or one that is all punctuation after it, names nothing.
            href.substringAfter("://", "x").isEmpty() -> null
            href.length > MAX_LINK_LENGTH -> null
            else -> PlainTextLink(start, end, href)
        }
    }.toList()

    /**
     * [candidates] without the ones that overlap any of [taken]. Both are in text order and
     * neither overlaps itself, so one sweep through each does it.
     */
    private fun outside(candidates: List<PlainTextLink>, taken: List<PlainTextLink>): List<PlainTextLink> {
        var i = 0
        return candidates.filter { c ->
            while (i < taken.size && taken[i].end <= c.start) i++
            i >= taken.size || taken[i].start >= c.end
        }
    }

    /**
     * Where the match [start] until [end] of [text] ends without the sentence punctuation after it,
     * and without a closing bracket it never opened. The brackets are counted once and the counts
     * kept up to date as the end moves back, so a long run of `)` costs its length, not its square.
     */
    private fun trimmedEnd(text: String, start: Int, end: Int): Int {
        var openParens = 0
        var closeParens = 0
        var openBrackets = 0
        var closeBrackets = 0
        for (i in start until end) {
            when (text[i]) {
                '(' -> openParens++
                ')' -> closeParens++
                '[' -> openBrackets++
                ']' -> closeBrackets++
            }
        }
        var e = end
        while (e > start) {
            when (text[e - 1]) {
                in TRAILING -> e--
                ')' -> if (closeParens > openParens) { closeParens--; e-- } else break
                ']' -> if (closeBrackets > openBrackets) { closeBrackets--; e-- } else break
                else -> break
            }
        }
        return e
    }
}
