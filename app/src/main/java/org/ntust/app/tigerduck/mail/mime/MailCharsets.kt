package org.ntust.app.tigerduck.mail.mime

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Charset resolution and decoding for mail parts and headers (spec appendix A.5). */
object MailCharsets {
    private val BIG5_LABELS = setOf("big5", "big-5", "cn-big5", "x-x-big5")
    private val GB_LABELS = setOf("gb2312", "gb_2312-80", "gbk", "x-gbk")
    private val BIG5_HKSCS: Charset = charset("Big5-HKSCS")
    private val GB18030: Charset = charset("GB18030")

    /** The charset to use for a part labelled [label], or null when unlabelled or unknown. */
    fun resolve(label: String?): Charset? {
        val l = label?.trim()?.trim('"')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when (l) {
            in BIG5_LABELS -> BIG5_HKSCS
            in GB_LABELS -> GB18030
            else -> runCatching { Charset.forName(l) }.getOrNull()
        }
    }

    /** Unlabelled or unknown: strict UTF-8, then Big5-HKSCS, then ISO-8859-1 (which never loses bytes). */
    fun decode(bytes: ByteArray, label: String?): String {
        resolve(label)?.let { return String(bytes, it) }
        return strict(bytes, Charsets.UTF_8)
            ?: strict(bytes, BIG5_HKSCS)
            ?: String(bytes, Charsets.ISO_8859_1)
    }

    private fun strict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
