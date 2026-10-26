package org.ntust.app.tigerduck.mail.compose

import java.net.URLDecoder

/**
 * A `mailto:` link (RFC 6068) as the fields of a new mail: the addresses in its path and its `to`
 * query, its `cc`, `subject` and `body`. A `bcc` is dropped on purpose: a link in a mail must not
 * add a recipient the sender never sees on the compose screen without opening the Cc/Bcc fields.
 */
object MailtoLink {
    /** Recipients as one comma-separated line each, the form the compose fields take. */
    data class Draft(val to: String, val cc: String, val subject: String, val body: String)

    fun parse(href: String): Draft? {
        if (!href.startsWith(SCHEME, ignoreCase = true)) return null
        val rest = href.substring(SCHEME.length)
        val query = rest.substringAfter('?', "")
            .split('&')
            .filter { it.isNotEmpty() }
            .map { it.substringBefore('=').lowercase() to decode(it.substringAfter('=', "")) }
        fun values(key: String) = query.filter { it.first == key }.map { it.second }
        fun addresses(vararg parts: String) = parts.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        return Draft(
            to = addresses(decode(rest.substringBefore('?')), *values("to").toTypedArray()),
            cc = addresses(*values("cc").toTypedArray()),
            // One line, as the subject field shows it: sending strips control characters, so a line
            // break left in would join words on the wire that were apart on screen.
            subject = values("subject").firstOrNull().orEmpty().replace("\r\n", " ").map { if (it.isISOControl()) ' ' else it }.joinToString(""),
            body = values("body").firstOrNull().orEmpty().replace("\r\n", "\n"),
        )
    }

    /**
     * [href] as it can travel to the compose screen: whole up to [MAX_LENGTH], its addresses alone
     * past that, and a bare `mailto:` when even those are too long. The link rides in the route,
     * whose arguments are saved with the back stack, so one of hundreds of kilobytes in a mail
     * would overflow that the moment the app went to the background.
     */
    fun capped(href: String): String = when {
        href.length <= MAX_LENGTH -> href
        else -> href.substringBefore('?').takeIf { it.length <= MAX_LENGTH } ?: SCHEME
    }

    /** The longest link [capped] passes on whole. */
    const val MAX_LENGTH = 8_192

    /** Percent-decoding only: in a mailto link a `+` is a plus sign, not a space. */
    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

    private const val SCHEME = "mailto:"
}
