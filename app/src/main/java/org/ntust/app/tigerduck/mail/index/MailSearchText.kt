package org.ntust.app.tigerduck.mail.index

import org.jsoup.Jsoup
import org.ntust.app.tigerduck.mail.model.MailBody

/**
 * What a search reads of a mail's body: its plain text, or the text of its HTML when it has none,
 * with runs of white space as one space, so a phrase broken across lines still matches.
 */
object MailSearchText {
    /**
     * At most this many characters of each mail: a few pages of text, far past where anything
     * anyone searches for sits, which keeps a mailbox of thousands of mails to tens of megabytes.
     */
    const val MAX_CHARS = 32_000

    private val WHITESPACE = Regex("\\s+")

    fun of(body: MailBody): String = of(body.plain, body.html)

    fun of(plain: String?, html: String?): String {
        val text = plain?.takeIf { it.isNotBlank() } ?: html?.let { Jsoup.parse(it).text() } ?: return ""
        return WHITESPACE.replace(text, " ").trim().take(MAX_CHARS)
    }
}
