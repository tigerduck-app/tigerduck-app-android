package org.ntust.app.tigerduck.mail

import org.ntust.app.tigerduck.files.RasterImage
import org.ntust.app.tigerduck.mail.mime.TextCleaning

/**
 * The name a file of a mail is written under, from the name the sender gave it: an attachment's,
 * one staged to send, and an image of the body opened full screen. Never a path out of the folder
 * it is written in.
 */
object MailFileNames {
    private const val MAX_NAME_LENGTH = 120

    /**
     * A name's most UTF-8 bytes: the file system takes 255, and a Chinese character is three. The
     * rest is room for what a caller puts before it, as compose's staging does.
     */
    private const val MAX_NAME_BYTES = 200
    private val UNSAFE_NAME = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

    /**
     * At most [MAX_NAME_LENGTH] UTF-16 code units and [MAX_NAME_BYTES] UTF-8 bytes, the extension
     * kept intact (the stem is shortened instead), never cutting a surrogate pair in half, and
     * trimmed of leading or trailing dots/spaces only *after* truncation -- truncating first can
     * otherwise leave a new trailing "." or " " that trimming beforehand never saw.
     */
    fun safe(name: String): String {
        val cleaned = TextCleaning.clean(name).replace(UNSAFE_NAME, "_")
        val truncated = truncateKeepingExtension(cleaned)
        return truncated.trim('.', ' ').ifBlank { "attachment" }
    }

    /**
     * A body image's file: the name the mail gives it, or "image", with the extension of the kind
     * its bytes are ([kind]), whatever extension it was sent with, as the iPhone names it.
     */
    fun image(name: String?, kind: RasterImage): String {
        val stem = name?.let { TextCleaning.clean(it).substringBeforeLast('.').trim('.', ' ') }.orEmpty()
        return safe("${stem.ifEmpty { "image" }}.${kind.extension}")
    }

    private fun truncateKeepingExtension(name: String): String {
        if (fits(name)) return name
        val dot = name.lastIndexOf('.')
        // Only treat it as a real extension when there's a non-empty stem before it and the
        // suffix is short enough to plausibly be one, not just some dot deep in a long name.
        val hasExtension = dot > 0 && dot < name.length - 1 && (name.length - dot - 1) <= 10
        val extension = if (hasExtension) name.substring(dot) else ""
        val stem = if (hasExtension) name.substring(0, dot) else name
        val stemBudget = (MAX_NAME_LENGTH - extension.length).coerceAtLeast(1)
        var cut = truncateAtCodePointBoundary(stem, stemBudget)
        // Then a code point at a time, at most a hundred and twenty, until the bytes fit too.
        while (cut.isNotEmpty() && !fits(cut + extension)) cut = cut.substring(0, cut.offsetByCodePoints(cut.length, -1))
        return cut + extension
    }

    private fun fits(name: String) = name.length <= MAX_NAME_LENGTH && name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES

    private fun truncateAtCodePointBoundary(s: String, maxLength: Int): String {
        if (maxLength <= 0) return ""
        if (s.length <= maxLength) return s
        var end = maxLength
        if (Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end)
    }
}
