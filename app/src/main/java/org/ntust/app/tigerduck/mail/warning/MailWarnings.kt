package org.ntust.app.tigerduck.mail.warning

import org.ntust.app.tigerduck.mail.mime.TextCleaning
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailAttachment
import org.ntust.app.tigerduck.mail.sanitize.MailLink
import java.net.IDN
import kotlin.math.abs

sealed interface MailWarning {
    data class ExternalSender(val address: String) : MailWarning
    data class DisplayNameMismatch(val shownAddress: String, val actualAddress: String) : MailWarning
    data object PasswordBait : MailWarning
    data class RiskyAttachments(val fileNames: List<String>) : MailWarning

    /** A delivery failure that names an address one or two keystrokes away from `mail.ntust.edu.tw`. */
    data object MistypedRecipient : MailWarning
}

enum class RiskReason { DOUBLE_EXTENSION, TYPE_MISMATCH, RISKY_TYPE, PROTECTED_ARCHIVE }

data class LinkVerdict(
    val host: String,
    val shownHost: String?,
    val mismatch: Boolean,
    val punycode: Boolean,
    val insecure: Boolean,
)

/** Spec appendix A.4 — keep identical to the iOS implementation. */
object MailWarnings {
    private val KEYWORDS = listOf(
        "密碼", "帳號驗證", "驗證帳號", "帳號停用", "停用帳號", "帳號異常", "信箱容量", "信箱已滿", "重新驗證",
        "重新登入", "登入驗證", "立即驗證", "解除封鎖", "password", "verify your account", "account verification",
        "mailbox quota", "mailbox full", "revalidate", "re-validate", "account suspended", "unusual sign-in",
    )
    private val RISKY_EXTENSIONS = setOf(
        "apk", "apks", "xapk", "aab", "exe", "msi", "msix", "appx", "bat", "cmd", "com", "scr", "pif", "cpl",
        "vbs", "vbe", "js", "jse", "wsf", "wsh", "ps1", "psm1", "jar", "lnk", "hta", "chm", "reg", "sh",
        "command", "app", "ipa", "iso", "img", "vhd", "vhdx", "dmg", "pkg", "html", "htm", "shtml", "xhtml",
        "mht", "mhtml", "svg", "docm", "xlsm", "pptm",
    )
    private val DOCUMENT_EXTENSIONS = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "jpg", "jpeg", "png", "gif", "txt", "zip")
    private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "tgz")
    private val ARCHIVE_PASSWORD_WORDS = listOf("密碼", "password", "解壓縮")
    private val EMAIL = Regex("[^\\s@<>()\",;:]+@[^\\s@<>()\",;:]+\\.[^\\s@<>()\",;:]+")
    private val HOST_LIKE = Regex(
        "^(?:[a-z][a-z0-9+.-]*://)?((?:[\\p{L}\\p{N}-]+\\.)+[\\p{L}]{2,})(?::\\d+)?(?:[/?#].*)?$",
        RegexOption.IGNORE_CASE,
    )
    private val URL_HOST_LEGACY = Regex("^[a-z][a-z0-9+.-]*://(?:[^/?#@]*@)?([^/?#:]+)", RegexOption.IGNORE_CASE)
    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

    /**
     * A whole href counts as a "plain" link ONLY in the form browsers would treat as
     * unambiguous: `http(s)://`, a host of ASCII letters/digits/-/. only, an optional
     * `:port`, then end of string or `/ ? #`. Anything else (userinfo, backslashes, missing
     * or extra slashes, percent-escapes or non-ASCII in the authority, whitespace) fails this
     * and must be treated as an outside link by callers (spec A.4 rule 3, password bait).
     *
     * No `RegexOption.IGNORE_CASE`: that option also turns on Unicode case folding, which
     * makes `[A-Za-z]` accept lookalikes such as the Kelvin sign (U+212A, folds to `k`),
     * dotted/dotless I (U+0130/U+0131) and long s (U+017F, folds to `s`, so `httpſ` would
     * match `https?`) — letting a non-ASCII host or scheme pass as if it were ASCII. The
     * scheme is spelled out per letter instead so it stays exactly `http`/`https`.
     */
    private val PLAIN_HTTP_LINK = Regex("[Hh][Tt][Tt][Pp][Ss]?://([A-Za-z0-9.-]+)(?::[0-9]+)?(?:[/?#].*)?")

    /** The mailbox domain every student address lives on, and the yardstick [isMistypedSchoolMailDomain] measures against. */
    const val SCHOOL_MAIL_DOMAIN = "mail.ntust.edu.tw"
    private const val MAX_DOMAIN_TYPO_EDITS = 2

    private fun normalize(domain: String) = domain.trim().lowercase().removeSuffix(".")

    /**
     * WHATWG URL pre-processing `MailWarnings` cannot assume a caller already did: strip
     * leading C0 controls and space, then remove ASCII tab/CR/LF wherever they occur (not
     * just at the ends), so a scheme or host split across a control character — e.g.
     * `ht\ttps://evil.example/` — can't dodge the scheme check or the host parsing below.
     */
    private fun sanitizeHref(href: String): String {
        val leadingStripped = href.dropWhile { it.code <= 0x1F || it == ' ' }
        return leadingStripped.filterNot { it == '\t' || it == '\r' || it == '\n' }
    }

    /**
     * Everything invisible in a piece of link text: Unicode format characters
     * (Cf -- bidi marks, word joiner U+2060, soft hyphen U+00AD, BOM U+FEFF) and
     * C0/C1 controls (Cc). U+200B is spelled out because its category has moved
     * between Unicode versions and platforms.
     */
    private val INVISIBLE = Regex("[\\p{Cf}\\p{Cc}\\u200b]")

    /**
     * What the reader actually sees. The shown-host comparison fails *open* on
     * anything invisible -- `ntust.edu.tw` with a trailing word joiner matches no
     * host pattern at all, so a link pointing somewhere else would be reported as
     * having nothing to compare instead of as a mismatch.
     */
    private fun visibleText(text: String): String = INVISIBLE.replace(text, "")

    /** The first email-shaped match in [text], with a sentence-ending `.` trimmed off. */
    private fun firstEmail(text: String): String? = EMAIL.find(text)?.value?.trimEnd('.')

    private fun toAscii(host: String): String = runCatching { IDN.toASCII(host) }.getOrDefault(host).lowercase()

    fun isSchoolDomain(host: String): Boolean {
        val h = normalize(host)
        return h == "ntust.edu.tw" || h.endsWith(".ntust.edu.tw")
    }

    /**
     * RFC 5321 §4.5.5: a delivery status notification is sent with the null reverse-path, and the
     * **receiving** server writes that down as `Return-Path: <>`. That header therefore comes
     * from our own side of the delivery, unlike the `From` display name ("Mail Deliver System"),
     * which any sender can type. It is the one signal here worth treating as a bounce marker.
     *
     * Availability, per site: the list needs it too (it draws the External badge), and it has it —
     * [org.ntust.app.tigerduck.mail.imap.AngusMailSession] fetches an explicit `HEADER.FIELDS`
     * set per message rather than the IMAP ENVELOPE, so `Return-Path` is simply one more name on
     * that list and costs no extra round trip. It rides along in `MailSummary`, and therefore in
     * the folder cache, so the badge is right from cache as well. The body fetch carries no
     * headers at all, which is why this is not derived down there.
     */
    fun isBounce(returnPath: String?): Boolean =
        returnPath?.filterNot { it.isWhitespace() } == "<>"

    /**
     * A sender with no domain to check counts as external: nothing vouches for it, and that is
     * the safe direction for both a missing `From` (null) and a mailbox kept only for its
     * display name (empty, `MailAddress.isRoutable` false).
     *
     * [bounce] is the single exemption, and it deliberately reverses what this used to say. A
     * Mail2000 delivery failure arrives as `From: "Mail Deliver System" <MAILER-DAEMON>` — a
     * bare local part with no domain — so it was badged External even though it came from the
     * school's own mail system. That is wrong on its face and teaches people to ignore the
     * badge. Callers decide with [isBounce], i.e. from `Return-Path: <>`, which the receiving
     * server sets, never from the display name.
     *
     * Why the exemption is still safe. [isExternal] feeds the badge and the password-bait rule,
     * and that rule fires on `keyword && (external || outsideLink)`: a forged "bounce" carrying
     * a phishing link to a non-school host still trips it through `outsideLink`. What remains is
     * a forged, link-free bounce — an attacker can legitimately send `MAIL FROM:<>`, so this is
     * not proof of origin — and such a mail has nothing to click. The exemption is also narrow:
     * it applies only when there is no domain at all, so a bounce whose `From` names a real
     * outside domain stays external exactly as before.
     */
    fun isExternal(address: String?, bounce: Boolean = false): Boolean {
        val domain = address?.substringAfterLast('@', missingDelimiterValue = "").orEmpty()
        if (domain.isEmpty()) return !bounce
        return !isSchoolDomain(domain)
    }

    /** What the two External-badge sites ask: [isExternal] with the [isBounce] exemption already applied. */
    fun isExternalSender(from: MailAddress?, returnPath: String?): Boolean =
        isExternal(from?.address, isBounce(returnPath))

    /**
     * True for a domain that reads as a mistyped [SCHOOL_MAIL_DOMAIN]: within
     * [MAX_DOMAIN_TYPO_EDITS] single-character edits of it, but neither it nor any other real
     * school domain. Two edits rather than one so a transposition (`ntsut`) counts, which plain
     * Levenshtein scores as two.
     *
     * The length check first is not only a shortcut: it keeps an attacker-supplied token from
     * reaching the quadratic distance loop at all.
     */
    fun isMistypedSchoolMailDomain(domain: String): Boolean {
        val host = toAscii(normalize(domain))
        if (host.isEmpty() || isSchoolDomain(host)) return false
        if (abs(host.length - SCHOOL_MAIL_DOMAIN.length) > MAX_DOMAIN_TYPO_EDITS) return false
        return editDistance(host, SCHOOL_MAIL_DOMAIN) <= MAX_DOMAIN_TYPO_EDITS
    }

    /**
     * Whether [text] names an email address whose domain is a near miss of the school's. This is
     * what turns a delivery failure into "did you mistype the address?": a bounce from
     * `gmail.com` says nothing about a typo, so it gets no such claim.
     */
    fun mentionsMistypedSchoolAddress(text: String): Boolean =
        EMAIL.findAll(text).any { isMistypedSchoolMailDomain(it.value.trimEnd('.').substringAfterLast('@')) }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /** [returnPath] is the mail's `Return-Path` header, where one is available; see [isBounce]. */
    fun evaluate(
        from: MailAddress?,
        subject: String,
        plainText: String,
        links: List<MailLink>,
        attachments: List<MailAttachment>,
        returnPath: String? = null,
    ): List<MailWarning> {
        val warnings = mutableListOf<MailWarning>()
        val sender = from?.address.orEmpty()
        val bounce = isBounce(returnPath)
        val external = isExternal(sender, bounce)
        if (external) warnings += MailWarning.ExternalSender(sender)
        from?.name?.let { name ->
            val shown = firstEmail(name)
            if (shown != null && !shown.equals(sender, ignoreCase = true)) {
                warnings += MailWarning.DisplayNameMismatch(shown, sender)
            }
        }
        val text = TextCleaning.stripBidi("$subject\n$plainText").lowercase()
        val keyword = KEYWORDS.any { it in text }
        val outsideLink = links.any { link ->
            val href = sanitizeHref(link.href).trim()
            href.startsWith("http", ignoreCase = true) && !isPlainSchoolLink(href)
        }
        if (keyword && (external || outsideLink)) warnings += MailWarning.PasswordBait
        val risky = attachments.filter { riskReason(it.fileName, it.contentType, text) != null }
            .map { cleanFileName(it.fileName) }
        if (risky.isNotEmpty()) warnings += MailWarning.RiskyAttachments(risky)
        if (bounce && mentionsMistypedSchoolAddress(text)) warnings += MailWarning.MistypedRecipient
        return warnings
    }

    /** A.3-cleaned, trailing whitespace and dots removed, original case kept (spec A.4 rule 4). */
    private fun cleanFileName(fileName: String): String = TextCleaning.clean(fileName).trimEnd('.', ' ')

    /** [subjectAndBody] is only used for the protected-archive rule. */
    fun riskReason(fileName: String, contentType: String, subjectAndBody: String): RiskReason? {
        val name = cleanFileName(fileName).lowercase()
        val parts = name.split('.')
        if (parts.size < 2) return null
        val ext = parts.last()
        val previous = if (parts.size >= 3) parts[parts.size - 2] else null
        val type = contentType.lowercase().substringBefore(';').trim()
        val body = TextCleaning.stripBidi(subjectAndBody).lowercase()
        return when {
            ext in RISKY_EXTENSIONS && previous in DOCUMENT_EXTENSIONS -> RiskReason.DOUBLE_EXTENSION
            ext in RISKY_EXTENSIONS && (type == "application/pdf" || type.startsWith("image/") || type == "text/plain") ->
                RiskReason.TYPE_MISMATCH
            ext in RISKY_EXTENSIONS -> RiskReason.RISKY_TYPE
            ext in ARCHIVE_EXTENSIONS && ARCHIVE_PASSWORD_WORDS.any { it in body } ->
                RiskReason.PROTECTED_ARCHIVE
            else -> null
        }
    }

    fun checkLink(text: String, href: String): LinkVerdict {
        val shownText = visibleText(text)
        val trimmedHref = sanitizeHref(href).trim()
        if (trimmedHref.startsWith("mailto:", ignoreCase = true)) {
            val actual = trimmedHref.substringAfter(':').substringBefore('?')
            val shown = firstEmail(shownText)
            return LinkVerdict(
                host = actual,
                shownHost = shown,
                mismatch = shown != null && !shown.equals(actual, ignoreCase = true),
                punycode = false,
                insecure = false,
            )
        }
        val actual = hostOf(trimmedHref).orEmpty()
        val shownHost = HOST_LIKE.matchEntire(shownText.trim())?.groupValues?.get(1)
            ?.let { toAscii(normalize(it)).removePrefix("www.") }
        val actualNoWww = actual.removePrefix("www.")
        val mismatch = shownHost != null && actualNoWww != shownHost && !actualNoWww.endsWith(".$shownHost")
        return LinkVerdict(
            host = actual,
            shownHost = shownHost,
            mismatch = mismatch,
            punycode = "xn--" in actual,
            insecure = trimmedHref.startsWith("http://", ignoreCase = true),
        )
    }

    /** True only when [href] matches [PLAIN_HTTP_LINK] and that host is a school domain. */
    private fun isPlainSchoolLink(href: String): Boolean {
        val host = PLAIN_HTTP_LINK.matchEntire(href)?.groupValues?.get(1) ?: return false
        return isSchoolDomain(host)
    }

    /**
     * Browser-style host extraction (spec A.4 rule 2, link mismatch). For `http`/`https`
     * (scheme case-insensitive): after `scheme:`, skip any run of `/` and `\`; the authority
     * ends at the first `/`, `\`, `?` or `#`; userinfo ends at the LAST `@` inside the
     * authority; the host is what remains before a `:port` (an IPv6 literal keeps its `[...]`
     * brackets instead of being cut at the first `:` inside them). Other schemes keep the
     * legacy regex.
     */
    private fun hostOf(href: String): String? {
        val schemeMatch = SCHEME.find(href)
        val scheme = schemeMatch?.groupValues?.get(1)
        if (scheme != null && (scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true))) {
            return browserHostOf(href, schemeMatch.range.last + 1)
        }
        return URL_HOST_LEGACY.find(href)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { toAscii(normalize(it)) }
    }

    private fun browserHostOf(href: String, authorityStart: Int): String? {
        var i = authorityStart
        while (i < href.length && (href[i] == '/' || href[i] == '\\')) i++
        var end = href.length
        for (j in i until href.length) {
            val c = href[j]
            if (c == '/' || c == '\\' || c == '?' || c == '#') {
                end = j
                break
            }
        }
        val authority = href.substring(i, end)
        val afterUserinfo = authority.substringAfterLast('@')
        val host = if (afterUserinfo.startsWith("[")) {
            val closing = afterUserinfo.indexOf(']')
            if (closing >= 0) afterUserinfo.substring(0, closing + 1) else afterUserinfo
        } else {
            afterUserinfo.substringBefore(':')
        }
        return host.takeIf { it.isNotBlank() }?.let { toAscii(normalize(it)) }
    }
}
