package org.ntust.app.tigerduck.mail.warning

import org.ntust.app.tigerduck.mail.mime.TextCleaning
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailAttachment
import org.ntust.app.tigerduck.mail.sanitize.MailLink
import java.net.IDN

sealed interface MailWarning {
    data class ExternalSender(val address: String) : MailWarning
    data class DisplayNameMismatch(val shownAddress: String, val actualAddress: String) : MailWarning
    data object PasswordBait : MailWarning
    data class RiskyAttachments(val fileNames: List<String>) : MailWarning
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
    private val URL_HOST = Regex("^[a-z][a-z0-9+.-]*://(?:[^/?#@]*@)?([^/?#:]+)", RegexOption.IGNORE_CASE)

    private fun normalize(domain: String) = domain.trim().lowercase().removeSuffix(".")

    private fun toAscii(host: String): String = runCatching { IDN.toASCII(host) }.getOrDefault(host).lowercase()

    fun isSchoolDomain(host: String): Boolean {
        val h = normalize(host)
        return h == "ntust.edu.tw" || h.endsWith(".ntust.edu.tw")
    }

    fun isExternal(address: String?): Boolean {
        val domain = address?.substringAfterLast('@', missingDelimiterValue = "").orEmpty()
        return domain.isEmpty() || !isSchoolDomain(domain)
    }

    fun evaluate(
        from: MailAddress?,
        subject: String,
        plainText: String,
        links: List<MailLink>,
        attachments: List<MailAttachment>,
    ): List<MailWarning> {
        val warnings = mutableListOf<MailWarning>()
        val sender = from?.address.orEmpty()
        val external = isExternal(sender)
        if (external) warnings += MailWarning.ExternalSender(sender)
        from?.name?.let { name ->
            val shown = EMAIL.find(name)?.value
            if (shown != null && !shown.equals(sender, ignoreCase = true)) {
                warnings += MailWarning.DisplayNameMismatch(shown, sender)
            }
        }
        val text = TextCleaning.stripBidi("$subject\n$plainText").lowercase()
        val keyword = KEYWORDS.any { it in text }
        val outsideLink = links.any { link ->
            val href = link.href.trim()
            href.startsWith("http", ignoreCase = true) && hostOf(href)?.let { !isSchoolDomain(it) } == true
        }
        if (keyword && (external || outsideLink)) warnings += MailWarning.PasswordBait
        val risky = attachments.filter { riskReason(it.fileName, it.contentType, text) != null }
            .map { TextCleaning.clean(it.fileName) }
        if (risky.isNotEmpty()) warnings += MailWarning.RiskyAttachments(risky)
        return warnings
    }

    /** [subjectAndBody] is only used for the protected-archive rule. */
    fun riskReason(fileName: String, contentType: String, subjectAndBody: String): RiskReason? {
        val name = TextCleaning.stripBidi(fileName).trim().trimEnd('.', ' ').lowercase()
        val parts = name.split('.')
        if (parts.size < 2) return null
        val ext = parts.last()
        val previous = if (parts.size >= 3) parts[parts.size - 2] else null
        val type = contentType.lowercase().substringBefore(';').trim()
        return when {
            ext in RISKY_EXTENSIONS && previous in DOCUMENT_EXTENSIONS -> RiskReason.DOUBLE_EXTENSION
            ext in RISKY_EXTENSIONS && (type == "application/pdf" || type.startsWith("image/") || type == "text/plain") ->
                RiskReason.TYPE_MISMATCH
            ext in RISKY_EXTENSIONS -> RiskReason.RISKY_TYPE
            ext in ARCHIVE_EXTENSIONS && ARCHIVE_PASSWORD_WORDS.any { it in subjectAndBody.lowercase() } ->
                RiskReason.PROTECTED_ARCHIVE
            else -> null
        }
    }

    fun checkLink(text: String, href: String): LinkVerdict {
        val trimmedHref = href.trim()
        if (trimmedHref.startsWith("mailto:", ignoreCase = true)) {
            val actual = trimmedHref.substringAfter(':').substringBefore('?')
            val shown = EMAIL.find(text)?.value
            return LinkVerdict(
                host = actual,
                shownHost = shown,
                mismatch = shown != null && !shown.equals(actual, ignoreCase = true),
                punycode = false,
                insecure = false,
            )
        }
        val actual = hostOf(trimmedHref).orEmpty()
        val shownHost = HOST_LIKE.matchEntire(text.trim())?.groupValues?.get(1)
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

    private fun hostOf(href: String): String? =
        URL_HOST.find(href)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { toAscii(normalize(it)) }
}
