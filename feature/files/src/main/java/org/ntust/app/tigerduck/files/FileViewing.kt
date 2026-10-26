package org.ntust.app.tigerduck.files

import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.math.roundToInt

/** The extensions of a web page. */
val WEB_PAGE_EXTENSIONS = setOf("html", "htm", "shtml", "xhtml", "mht", "mhtml")

/**
 * The extensions of a web page or an SVG, which a browser renders and may run scripts in: such a
 * file never opens in a viewer but goes to the share sheet, as iOS keeps it out of Quick Look.
 */
val SHARED_NOT_VIEWED_EXTENSIONS = WEB_PAGE_EXTENSIONS + "svg"

/** What the in-app viewer shows a file as. */
enum class ViewerKind { PDF, IMAGE, VIDEO, AUDIO, TEXT, MARKDOWN, DOCX, PPTX, ARCHIVE }

/** Where a downloaded file opens. */
sealed interface FileOpening {
    /** In the app's own viewer, as [kind]. */
    data class InApp(val kind: ViewerKind) : FileOpening

    /** In another app, through the system's chooser. */
    data object OtherApp : FileOpening

    /** In the share sheet only: a web page, an SVG or other XML, which a viewer would run. */
    data object ShareOnly : FileOpening
}

/**
 * Which downloaded files the app shows itself. The declared type decides, as it does for a mail
 * attachment ([org.ntust.app.tigerduck.mail.resolveAttachmentMimeType]): the name's extension only
 * when the type says nothing, so a file sent as something else never opens here by its name alone.
 * A file named or typed as a web page, an SVG or other XML goes to the share sheet, by either. A
 * file of a kind that's a zip inside, sent as a zip, goes where its name says: a Word or PowerPoint
 * file to its viewer, a spreadsheet, a book or an app to another app, never into the archive viewer.
 */
object FileViewing {

    fun opening(mimeType: String, fileName: String): FileOpening {
        val type = mimeType.substringBefore(';').trim().lowercase()
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (isWebOrXml(type) || extension in SHARED_NOT_VIEWED_EXTENSIONS || extension in XML_EXTENSIONS) {
            return FileOpening.ShareOnly
        }
        val kind = when {
            type.isEmpty() || type in GENERIC_TYPES -> kindOfExtension(extension)
            type in ZIP_TYPES && extension in ZIPPED_FORMATS -> kindOfExtension(extension)
            else -> kindOfType(type)
        }
        return kind?.let(FileOpening::InApp) ?: FileOpening.OtherApp
    }

    private fun isWebOrXml(type: String) =
        type == "text/html" || type == "text/xml" || type == "application/xml" || type.endsWith("+xml")

    private fun kindOfType(type: String): ViewerKind? = when {
        type == "application/pdf" -> ViewerKind.PDF
        type in IMAGE_TYPES -> ViewerKind.IMAGE
        type in VIDEO_TYPES -> ViewerKind.VIDEO
        type in AUDIO_TYPES -> ViewerKind.AUDIO
        type == "text/markdown" || type == "text/x-markdown" -> ViewerKind.MARKDOWN
        type.startsWith("text/") || type in TEXT_TYPES -> ViewerKind.TEXT
        type == DOCX_TYPE -> ViewerKind.DOCX
        type == PPTX_TYPE -> ViewerKind.PPTX
        type in ZIP_TYPES || type in ARCHIVE_TYPES -> ViewerKind.ARCHIVE
        else -> null
    }

    private fun kindOfExtension(extension: String): ViewerKind? = when (extension) {
        "pdf" -> ViewerKind.PDF
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp" -> ViewerKind.IMAGE
        "mp4", "m4v", "mov", "webm", "3gp", "mkv" -> ViewerKind.VIDEO
        "mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav" -> ViewerKind.AUDIO
        "md", "markdown" -> ViewerKind.MARKDOWN
        in TEXT_EXTENSIONS -> ViewerKind.TEXT
        "docx" -> ViewerKind.DOCX
        "pptx" -> ViewerKind.PPTX
        in ARCHIVE_EXTENSIONS -> ViewerKind.ARCHIVE
        else -> null
    }

    /** XML by name, which the share sheet takes whatever type it was sent as. */
    private val XML_EXTENSIONS = setOf("xml", "xsl", "xslt")

    private val GENERIC_TYPES = setOf("application/octet-stream", "document/unknown", "application/unknown")

    private val ZIP_TYPES = setOf("application/zip", "application/x-zip-compressed", "application/x-zip")

    /** Zips inside, which a server that only looks at the bytes may send as one. */
    private val ZIPPED_FORMATS = setOf(
        "docx", "pptx", "xlsx", "docm", "pptm", "xlsm", "odt", "ods", "odp", "epub", "apk", "jar",
    )

    /**
     * The other archives [Archives] reads: 7z, tar, and gzip, bzip2 or xz, around a tar or on their
     * own. Each spelling is one a real source declares; [Archives] goes by the bytes anyway.
     */
    private val ARCHIVE_TYPES = setOf(
        "application/x-7z-compressed", "application/x-tar", "application/x-gtar", "application/gzip", "application/x-gzip",
        "application/x-compressed-tar", "application/x-bzip2", "application/x-bzip", "application/x-bzip-compressed-tar",
        "application/x-xz", "application/x-xz-compressed-tar",
        // Moodle's own for .tgz, .gz and .gzip (lib/classes/filetypes.php), misspelling and all.
        "application/g-zip",
        // Debian's mime.types, which Android's type map starts from, for .tgz.
        "application/x-gtar-compressed",
        // shared-mime-info's names for bzip2 and a tar in it.
        "application/bzip2", "application/x-bzip2-compressed-tar",
    )
    private val ARCHIVE_EXTENSIONS = setOf("zip", "7z", "tar", "gz", "gzip", "tgz", "bz2", "tbz", "tbz2", "xz", "txz")

    private val IMAGE_TYPES = setOf(
        "image/jpeg", "image/png", "image/gif", "image/webp", "image/heic", "image/heif", "image/bmp", "image/x-ms-bmp",
    )
    private val VIDEO_TYPES = setOf(
        "video/mp4", "video/quicktime", "video/webm", "video/3gpp", "video/3gpp2", "video/x-matroska", "video/x-m4v",
    )

    /** What Media3 plays without an extension; WMA has no decoder on Android, so it isn't here. */
    private val AUDIO_TYPES = setOf(
        "audio/mpeg", "audio/mp3", "audio/mp4", "audio/aac", "audio/ogg", "audio/opus", "audio/flac", "audio/x-flac",
        "audio/wav", "audio/x-wav", "audio/wave", "audio/webm", "application/ogg",
    )
    private val TEXT_TYPES = setOf(
        "application/json", "application/x-yaml", "application/yaml", "application/javascript", "application/x-sh",
        "application/sql", "application/x-python-code",
    )
    private val TEXT_EXTENSIONS = setOf(
        "txt", "text", "csv", "tsv", "json", "log", "yml", "yaml", "ini", "conf", "cfg", "toml", "properties",
        "kt", "kts", "java", "py", "c", "h", "cpp", "hpp", "cc", "cs", "js", "ts", "swift", "sh", "rb", "go", "rs",
        "sql", "m", "r", "tex", "srt", "vtt",
    )

    const val DOCX_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val PPTX_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
}

/** The start of a text file, as the viewer shows it. */
data class TextPreview(val text: String, val truncated: Boolean)

/**
 * Reads a text file for the viewer: up to [LIMIT_BYTES], which a phone lays out without stalling,
 * in its byte order mark's encoding, or UTF-8, or Big5, which school systems still send.
 */
object TextPreviews {
    const val LIMIT_BYTES = 1 shl 20

    fun read(input: InputStream, limitBytes: Int = LIMIT_BYTES): TextPreview {
        val buffer = input.readNBytesCompat(limitBytes + 1)
        val truncated = buffer.size > limitBytes
        val bytes = if (truncated) buffer.copyOf(limitBytes) else buffer
        val text = when {
            bytes.startsWith(0xEF, 0xBB, 0xBF) -> utf8(bytes.copyOfRange(3, bytes.size), truncated)
            bytes.startsWith(0xFF, 0xFE) -> utf16(bytes, Charsets.UTF_16LE, truncated)
            bytes.startsWith(0xFE, 0xFF) -> utf16(bytes, Charsets.UTF_16BE, truncated)
            else -> strict(completeUtf8(bytes, truncated), Charsets.UTF_8)
                ?: strict(bytes, BIG5)
                // The limit may have kept only a Big5 character's first byte.
                ?: (if (truncated) strict(bytes.copyOf(bytes.size - 1), BIG5) else null)
                ?: String(bytes, Charsets.UTF_8)
        }
        return TextPreview(text, truncated)
    }

    /**
     * [text]'s lines, any longer than [maxLine] in pieces, so each lays out quickly. A piece ends
     * before a character's second half, never between its two.
     */
    fun lines(text: String, maxLine: Int = MAX_LINE): List<String> =
        text.lines().flatMap { line -> if (line.length <= maxLine) listOf(line) else pieces(line, maxLine) }

    private fun pieces(line: String, maxLine: Int): List<String> {
        val pieces = ArrayList<String>(line.length / maxLine + 1)
        var start = 0
        while (start < line.length) {
            var end = minOf(start + maxLine, line.length)
            if (end < line.length && end - 1 > start && line[end - 1].isHighSurrogate()) end--
            pieces += line.substring(start, end)
            start = end
        }
        return pieces
    }

    /** The longest line laid out as one: longer ones are shown in pieces. */
    const val MAX_LINE = 2_000

    private val BIG5: Charset = Charset.forName("Big5")

    private fun utf8(bytes: ByteArray, truncated: Boolean) = String(completeUtf8(bytes, truncated), Charsets.UTF_8)

    private fun strict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** [bytes] without a UTF-8 character the limit cut short, when it did. */
    private fun completeUtf8(bytes: ByteArray, truncated: Boolean): ByteArray {
        if (!truncated) return bytes
        var start = bytes.size - 1
        // Back over continuation bytes (10xxxxxx) to the character's first byte.
        while (start >= 0 && start > bytes.size - 4 && bytes[start].toInt() and 0xC0 == 0x80) start--
        if (start < 0) return bytes
        val lead = bytes[start].toInt() and 0xFF
        val length = when {
            lead < 0x80 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> 1
        }
        return if (start + length > bytes.size) bytes.copyOf(start) else bytes
    }

    /** UTF-16 after its byte order mark, without a character the limit cut short. */
    private fun utf16(bytes: ByteArray, charset: Charset, truncated: Boolean): String {
        var units = bytes.copyOfRange(2, bytes.size).let { if (it.size % 2 == 0) it else it.copyOf(it.size - 1) }
        if (truncated && units.size >= 2) {
            // The last unit, read in the file's byte order: a character's first half on its own.
            val (high, low) = if (charset == Charsets.UTF_16BE) units[units.size - 2] to units[units.size - 1]
            else units[units.size - 1] to units[units.size - 2]
            val unit = ((high.toInt() and 0xFF) shl 8) or (low.toInt() and 0xFF)
            if (unit in 0xD800..0xDBFF) units = units.copyOf(units.size - 2)
        }
        return String(units, charset)
    }

    private fun ByteArray.startsWith(vararg prefix: Int) =
        size >= prefix.size && prefix.indices.all { this[it].toInt() and 0xFF == prefix[it] }

    private fun InputStream.readNBytesCompat(count: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(count, 64 * 1024))
        val chunk = ByteArray(16 * 1024)
        var left = count
        while (left > 0) {
            val read = read(chunk, 0, minOf(chunk.size, left))
            if (read < 0) break
            out.write(chunk, 0, read)
            left -= read
        }
        return out.toByteArray()
    }
}

/** How the PDF viewer lays its pages out. */
object PdfPages {
    /** The widest a page is rendered, whatever the screen: a full A4 page at this width is ~11 MB. */
    const val MAX_RENDER_WIDTH = 2048

    /** The page at the top of the list and the count, counted from one; 0 to 0 for no pages. */
    fun position(firstVisible: Int, count: Int): Pair<Int, Int> =
        if (count <= 0) 0 to 0 else (firstVisible.coerceIn(0, count - 1) + 1) to count

    /** How tall a page of [pageWidth] by [pageHeight] points is drawn [width] px wide. */
    fun displayHeight(pageWidth: Int, pageHeight: Int, width: Int): Int =
        if (pageWidth <= 0 || pageHeight <= 0) width
        else (width.toLong() * pageHeight / pageWidth).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** The most pixels one page is rendered with: 32 MB of bitmap. */
    const val MAX_RENDER_PIXELS = 8L * 1024 * 1024

    /**
     * The bitmap a page of [pageWidth] by [pageHeight] points renders into for a view [viewWidth]
     * px wide: [renderWidth] wide, unless that would pass [MAX_RENDER_PIXELS], as a long strip of
     * a page would, when it is narrower, its shape kept.
     */
    fun renderSize(pageWidth: Int, pageHeight: Int, viewWidth: Int): Pair<Int, Int> {
        // In doubles: a page's height in pixels may not fit an Int.
        var width = renderWidth(viewWidth).toDouble()
        var height = width * pageHeight.coerceAtLeast(1) / pageWidth.coerceAtLeast(1)
        if (width * height > MAX_RENDER_PIXELS) {
            val scale = kotlin.math.sqrt(MAX_RENDER_PIXELS / (width * height))
            width *= scale
            height *= scale
        }
        val w = width.toInt().coerceAtLeast(1)
        // A page too thin for a pixel's width: as tall as the budget allows.
        val h = height.toLong().coerceIn(1, MAX_RENDER_PIXELS / w).toInt()
        return w to h
    }

    /** The width to render a page at for a view [viewWidth] px wide: sharper, so a pinch in stays crisp. */
    fun renderWidth(viewWidth: Int): Int = (viewWidth.coerceAtLeast(1) * 1.5f).roundToInt().coerceAtMost(MAX_RENDER_WIDTH)
}

/**
 * What the Office viewer's WebView may load: the viewer's own files, from the app's assets, and
 * the one document it shows, at [DOCUMENT_PATH]. Everything else, another site or scheme, another
 * of the app's files, a path out of the viewer's folder, is blocked.
 */
object OfficeRequests {
    const val HOST = "appassets.androidplatform.net"
    const val DOCUMENT_PATH = "/document"
    const val PAGE = "https://$HOST/viewer/office.html"

    /** The viewer's files, in `assets/viewer/`. */
    val ASSETS = setOf(
        "office.html", "office-guard.js", "office.js", "office.css", "jszip.min.js", "jszip-shim.js", "docx-preview.min.js",
        "pptx-to-html.js",
    )

    sealed interface Route {
        /** A file of the viewer's, by its path in the app's assets. */
        data class Asset(val path: String) : Route

        /** The document being shown. */
        data object Document : Route

        data object Blocked : Route
    }

    fun route(url: String): Route {
        val uri = runCatching { URI(url) }.getOrNull() ?: return Route.Blocked
        if (!uri.scheme.equals("https", ignoreCase = true) || !uri.host.equals(HOST, ignoreCase = true)) return Route.Blocked
        // The raw path: an encoded dot or slash must not reach a folder outside the viewer's.
        val path = uri.rawPath ?: return Route.Blocked
        if (path == DOCUMENT_PATH) return Route.Document
        val name = path.removePrefix("/viewer/").takeIf { it != path } ?: return Route.Blocked
        return if (name in ASSETS) Route.Asset("viewer/$name") else Route.Blocked
    }
}
