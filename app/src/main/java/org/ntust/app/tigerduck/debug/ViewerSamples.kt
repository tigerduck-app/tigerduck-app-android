package org.ntust.app.tigerduck.debug

import java.io.File
import java.io.InputStream

/** One of the debug build's file viewer samples, as `assets/viewer-samples/index.tsv` lists it. */
data class ViewerSample(val group: String, val name: String, val mimeType: String, val bytes: Long)

/**
 * The debug build's file viewer samples (Settings › Developer › File viewer samples), which
 * tools/viewer-samples/make_samples.sh makes, and the files picked on the phone beside them. Both
 * are copied under [CACHE_DIR] and opened as a downloaded Moodle file opens.
 */
object ViewerSamples {
    const val ASSET_DIR = "viewer-samples"
    const val INDEX = "index.tsv"

    /** Under the cache, where the FileProvider's `viewer_samples` path hands them out. */
    const val CACHE_DIR = "viewer_samples"

    const val GENERIC_TYPE = "application/octet-stream"

    /** Short of the 255 bytes a file name may take, with room for the copy's own folder. */
    private const val MAX_NAME_BYTES = 200
    private const val PICKED = "picked"

    /** The index's samples: group, name, MIME type and size, tab-separated, under a header. */
    fun parseIndex(text: String): List<ViewerSample> =
        text.lineSequence().drop(1).filter { it.isNotBlank() }.map { line ->
            val (group, name, mimeType, bytes) = line.split('\t')
            ViewerSample(group, name, mimeType, bytes.trim().toLong())
        }.toList()

    /**
     * Where the sample [name] is stored in the assets: a name ending in .gz has .sample after it,
     * since the Android Gradle plugin's asset merge takes a .gz off and unpacks the file.
     * make_samples.sh's write_index.py keeps the same rule.
     */
    fun assetName(name: String): String = if (name.endsWith(".gz")) "$name.sample" else name

    /**
     * The name a file picked on the phone is copied and opened under: its provider's
     * [displayName], down to its last path segment and with no control characters, at most
     * [MAX_NAME_BYTES] in UTF-8, as a file name is limited in bytes, cut between characters with
     * its extension kept; or, with none, "picked" with the extension [extensionOf] gives its
     * [mimeType].
     */
    fun pickedName(displayName: String?, mimeType: String?, extensionOf: (String) -> String?): String {
        val own = displayName.orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .filterNot { it.isISOControl() }
            .trim()
        if (own.isEmpty() || own == "." || own == "..") {
            val extension = mimeType?.let(extensionOf)
            return if (extension.isNullOrEmpty()) PICKED else "$PICKED.$extension"
        }
        if (own.utf8Bytes() <= MAX_NAME_BYTES) return own
        val extension = own.substringAfterLast('.', missingDelimiterValue = "")
        val suffix = if (extension.isNotEmpty() && extension.utf8Bytes() < MAX_NAME_BYTES / 4) ".$extension" else ""
        val stem = if (suffix.isEmpty()) own else own.substringBeforeLast('.')
        return stem.takeUtf8Bytes(MAX_NAME_BYTES - suffix.utf8Bytes()) + suffix
    }

    /**
     * Of the folders [picks] the picked files were copied into, each named for when it was made
     * ("<millis>-<anything>"), the ones to delete: all but the [keep] newest. The newest stay, for
     * an app a copy went to that may open it again; anything not named that way goes.
     */
    fun picksToDrop(picks: List<String>, keep: Int): List<String> {
        val (dated, other) = picks.partition { it.substringBefore('-').toLongOrNull() != null }
        return other + dated.sortedByDescending { it.substringBefore('-').toLong() }.drop(keep)
    }

    private fun String.utf8Bytes(): Int = toByteArray(Charsets.UTF_8).size

    /** The longest start of this string, whole characters only, that takes at most [limit] bytes. */
    private fun String.takeUtf8Bytes(limit: Int): String {
        var bytes = 0
        var end = 0
        while (end < length) {
            val next = offsetByCodePoints(end, 1)
            val size = substring(end, next).utf8Bytes()
            if (bytes + size > limit) break
            bytes += size
            end = next
        }
        return substring(0, end)
    }

    /**
     * The type a picked file opens as: its provider's [providerType], as a Moodle file opens as
     * the type Moodle sends; with none, the one [typeOf] gives the extension of [name], or
     * [GENERIC_TYPE].
     */
    fun pickedType(providerType: String?, name: String, typeOf: (String) -> String?): String =
        providerType?.trim()?.takeIf { it.isNotEmpty() }
            ?: name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
                .takeIf { it.isNotEmpty() }?.let(typeOf)
            ?: GENERIC_TYPE

    /**
     * Copies what [source] opens into [file], making its folder. When the read or the write fails
     * part way, deletes [cleanUp] (the file, or a folder made for it alone) before rethrowing, so
     * no half copy is left to open or to pile up.
     */
    fun copyInto(file: File, cleanUp: File, source: () -> InputStream) {
        try {
            file.parentFile?.mkdirs()
            source().use { input -> file.outputStream().use { input.copyTo(it) } }
        } catch (e: Throwable) {
            cleanUp.deleteRecursively()
            throw e
        }
    }
}

