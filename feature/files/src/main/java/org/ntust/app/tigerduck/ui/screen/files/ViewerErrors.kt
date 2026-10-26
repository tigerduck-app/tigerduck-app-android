package org.ntust.app.tigerduck.ui.screen.files

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import java.util.Locale

/**
 * What a viewer failed on: the [error], a [note] when there's no error to give or the error
 * alone doesn't say enough, and the [track] a player had.
 */
class ViewerFailure(val error: Throwable? = null, val note: String? = null, val track: Format? = null)

/** A failure as the page shows it: its [details], and whether the device can't decode the file's encoding. */
internal data class ShownFailure(val details: String, val unsupportedEncoding: Boolean)

/**
 * The error a viewer failed on, as the file viewer prints it under "can't be shown here": raw and
 * untranslated, so a screenshot someone sends says why. Never anything that could name someone:
 * the file's own name and link, links and paths, a track's label, a token, a student ID.
 */
internal object ViewerErrors {
    /** A screenful, which the text shown is cut to. */
    const val MAX_LINES = 12

    private const val MAX_LINE_CHARS = 400

    /** How much of one message is read at all, so a huge one can't hold up the screen. */
    private const val MAX_MESSAGE_CHARS = 2_000
    private const val MAX_CAUSES = 8

    /**
     * [failure] as lines: the note, then the error's class (a player's code name) and message; for
     * a decoder's failure the renderer, how far it supports the track, and the track; then each
     * cause. [privateParts] (the file's name and link) are hidden wherever they turn up.
     */
    @OptIn(UnstableApi::class)
    fun describe(failure: ViewerFailure, privateParts: Collection<String> = emptyList()): String {
        val lines = mutableListOf<String>()
        failure.note?.let { lines += it }
        val error = failure.error
        val renderer = (error as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }
        if (error != null) lines += head(error)
        if (renderer != null) {
            lines += "renderer: ${renderer.rendererName}, format support: ${C.getFormatSupportString(renderer.rendererFormatSupport)}"
        }
        (renderer?.rendererFormat ?: failure.track)?.let { lines += "track: ${track(it)}" }
        if (error != null) lines += causes(error)
        val labels = listOfNotNull(renderer?.rendererFormat, failure.track)
            .flatMap { format -> listOfNotNull(format.label) + format.labels.map { it.value } }
        return finish(lines, privateParts + labels)
    }

    /** [failure] as the page shows it, [privateParts] hidden. */
    fun shown(failure: ViewerFailure, privateParts: Collection<String>) =
        ShownFailure(describe(failure, privateParts), isUnsupportedEncoding(failure))

    /**
     * Whether the player failed for want of a decoder that takes the file's encoding (a 10-bit
     * HEVC clip on a phone whose decoders are 8-bit only, say), not on a broken or unreadable file.
     */
    fun isUnsupportedEncoding(failure: ViewerFailure): Boolean =
        (failure.error as? PlaybackException)?.errorCode in UNSUPPORTED_ENCODING

    /**
     * The player's codes for a decoder that won't take a track (one beyond its size or rate, as a
     * 4K60 clip on a decoder rated for 4K at about 30 fps), or none found for it. A decode that
     * fails part way (ERROR_CODE_DECODING_FAILED) is left out: a damaged file does that too.
     */
    private val UNSUPPORTED_ENCODING = setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        // A container Media3 can't open (a WAV of a kind it doesn't read): no decoder is ever chosen.
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    )

    /**
     * [text] with [privateParts], links, paths, a track's label, tokens and student IDs hidden.
     * A link goes whole while it's still encoded, so a space spelled %20 can't end it early; the
     * rest is decoded before the others are looked for, so none of them hides in percent-encoding.
     */
    fun scrub(text: String, privateParts: Collection<String> = emptyList()): String {
        val parts = privateParts
            .flatMap { listOf(it, percentDecoded(it)) }
            .filter { it.length >= if (it.any { c -> c.code > 0x7F }) MIN_PRIVATE_PART_WIDE else MIN_PRIVATE_PART }
            .distinct()
            .sortedByDescending { it.length }
        fun String.withoutParts() = parts.fold(this) { out, part -> out.replace(part, "<file>", ignoreCase = true) }
        val linksGone = text.take(MAX_MESSAGE_CHARS).withoutParts().withoutLinks()
        // Again once decoded, for a link that was itself encoded ("https%3A%2F%2F…").
        return percentDecoded(linksGone).withoutParts().withoutLinks()
            .replace(INNER_FILE) { "${it.groupValues[1]}<file>)" }
            .replace(PATH) { "<path>${extension(it.value)}" }
            .replace(FORMAT_LABEL) { "Format(${it.groupValues[1]}, <label>," }
            .replace(TOKEN) { "${it.groupValues[1]}=<hidden>" }
            .replace(STUDENT_ID, "<id>")
    }

    /** Links with only their host left: no path, query, or login. */
    private fun String.withoutLinks() = this
        .replace(ENCODED_LINK) { "${it.groupValues[1]}://${percentDecoded(it.groupValues[2]).substringAfterLast('@')}/<hidden>" }
        .replace(LINK) { "${it.groupValues[1]}://${it.groupValues[2].substringAfterLast('@')}/<hidden>" }

    /** The parts of a viewed file that name it: its name, bare too, and its link, as they could turn up in an error. */
    fun privateParts(name: String, link: String, path: String?): List<String> =
        listOfNotNull(name, name.substringBeforeLast('.'), link, path, path?.substringAfterLast('/'))

    private fun head(error: Throwable): String {
        val name = if (error is PlaybackException) error.errorCodeName else error.javaClass.simpleName
        return error.message?.let { "$name: $it" } ?: name
    }

    private fun causes(error: Throwable): List<String> =
        generateSequence(error.cause) { cause -> cause.cause?.takeIf { it !== cause } }
            .take(MAX_CAUSES)
            .map { "caused by ${head(it)}" }
            .toList()

    /** What decides whether a track plays: never its label, id or language. */
    @OptIn(UnstableApi::class)
    private fun track(format: Format): String = listOfNotNull(
        format.sampleMimeType,
        format.codecs,
        if (format.width > 0 && format.height > 0) "${format.width}x${format.height}" else null,
        if (format.frameRate > 0) String.format(Locale.ROOT, "%.2ffps", format.frameRate) else null,
        format.colorInfo?.toLogString(),
        if (format.bitrate > 0) "${format.bitrate / 1000}kbps" else null,
        if (format.channelCount > 0) "${format.channelCount}ch" else null,
        if (format.sampleRate > 0) "${format.sampleRate}Hz" else null,
    ).joinToString(", ")

    private fun finish(lines: List<String>, privateParts: Collection<String>): String {
        val all = lines.flatMap { scrub(it, privateParts).lines() }
            .map { if (it.length > MAX_LINE_CHARS) it.take(MAX_LINE_CHARS) + "…" else it }
        return (if (all.size > MAX_LINES) all.take(MAX_LINES - 1) + "…" else all).joinToString("\n")
    }

    /** %XX runs as the UTF-8 they spell. */
    private fun percentDecoded(text: String): String = text.replace(PERCENT_RUN) { run ->
        val bytes = run.value.chunked(3).map { it.substring(1).toInt(16).toByte() }.toByteArray()
        String(bytes, Charsets.UTF_8)
    }

    /** The extension at the end of [path], dot and all, so the kind of file stays; or nothing. */
    private fun extension(path: String): String =
        path.substringAfterLast('/').substringAfterLast('.', "").takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
            ?.let { ".$it" } ?: ""

    /** Shorter than this, a part of a name would hide words that only look like it. */
    private const val MIN_PRIVATE_PART = 4

    /** The same in a script whose two characters can be a whole name ("小明"). */
    private const val MIN_PRIVATE_PART_WIDE = 2

    /**
     * A file inside a zip, as the Office page's JSZip names it: "(inner file : word/media/x.png)".
     * The name runs to the end of the message, brackets and line breaks and all, so the whole rest goes.
     */
    private val INNER_FILE = Regex("""(?is)(inner file\s*:\s*).*""")

    private val PERCENT_RUN = Regex("""(?:%[0-9A-Fa-f]{2})+""")

    /** A link of any kind: its host kept, which says whose, and the rest hidden. */
    private val LINK = Regex("""\b([A-Za-z][A-Za-z0-9+.-]*)://([^/\s?#]*)\S*""")

    /** A link that was itself percent-encoded ("https%3A%2F%2Fhost%2F…"), which decodes with spaces in it. */
    private val ENCODED_LINK = Regex("""(?i)\b([a-z][a-z0-9+.-]*)%3A%2F%2F((?:(?!%2F)[^/\s?#])*)\S*""")

    /** An absolute path: not a MIME type's slash, nor a link's, which is gone by now. */
    private val PATH = Regex("""(?<![\w.:/-])/[^\s'"]+""")

    /** Media3's Format(id, label, …), whose label is a track's name, which a file's maker chose. */
    private val FORMAT_LABEL = Regex("""Format\(([^,]*), ([^,]*),""")

    private val TOKEN = Regex("""(?i)\b([\w-]*token[\w-]*)=[^&\s]*""")

    /** A student ID, also where a file's name runs on from it ("B00015999_hw1"). */
    private val STUDENT_ID = Regex("""(?<![A-Za-z0-9])[A-Za-z]\d{8}(?!\d)""")
}
