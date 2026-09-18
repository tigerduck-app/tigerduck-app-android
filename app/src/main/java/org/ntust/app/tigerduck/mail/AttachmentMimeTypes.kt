package org.ntust.app.tigerduck.mail

import android.webkit.MimeTypeMap

/**
 * Resolves the MIME type to hand an `ACTION_VIEW` intent for a mail attachment (spec: opening an
 * attachment must not present an empty chooser for formats mail clients routinely mislabel, e.g.
 * `.m4a` as `audio/x-m4a` or `application/octet-stream` -- Android's audio players register only
 * `audio/mp4`).
 *
 * The sender's declared Content-Type is a weak signal, not a worthless one: this never derives
 * purely from the filename extension, since that would let a mail carrying `evil.apk` while
 * declaring `image/png` open as an installer. Precedence:
 *  1. Normalise [declaredType]: lowercase, strip any `;` parameters, trim.
 *  2. Map known non-standard aliases ([CONTENT_TYPE_ALIASES]) to the type Android actually
 *     registers handlers for.
 *  3. If the normalised type is missing, blank, or `application/octet-stream` -- the generic
 *     label that carries no information -- derive from [fileName]'s extension: first via
 *     [EXTENSION_OVERRIDES] (covers extensions [MimeTypeMap] itself is missing on some API
 *     levels, notably "m4a"), then via [extensionLookup].
 *  4. Otherwise keep the (normalised) declared type as-is.
 *
 * [extensionLookup] is injected so this stays a pure, JVM-testable function with no Android
 * runtime dependency; production call sites can simply omit it to use the real [MimeTypeMap].
 */
internal fun resolveAttachmentMimeType(
    declaredType: String,
    fileName: String,
    extensionLookup: (extension: String) -> String? = { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) },
): String {
    val normalized = declaredType.substringBefore(';').trim().lowercase()
    CONTENT_TYPE_ALIASES[normalized]?.let { return it }
    if (normalized.isNotBlank() && normalized != GENERIC_OCTET_STREAM) return normalized

    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    if (extension.isEmpty()) return GENERIC_OCTET_STREAM
    return EXTENSION_OVERRIDES[extension] ?: extensionLookup(extension) ?: GENERIC_OCTET_STREAM
}

private const val GENERIC_OCTET_STREAM = "application/octet-stream"

/**
 * Declared Content-Type aliases mail senders commonly attach to a file, which Android's intent
 * resolution does not recognise under that alias -- keyed by the normalised (lowercase, no `;`
 * parameters) declared type, mapped to the type media apps actually register a handler for. Keep
 * this small: add an entry only once a real sender/handler mismatch is confirmed.
 */
private val CONTENT_TYPE_ALIASES = mapOf(
    // .m4a: mail clients commonly declare either of these; Android's audio players register audio/mp4.
    "audio/x-m4a" to "audio/mp4",
    "audio/m4a" to "audio/mp4",
    // Other x- prefixed aliases seen from mail senders/MTAs for otherwise-common formats.
    "audio/x-wav" to "audio/wav",
    "audio/x-mpeg" to "audio/mpeg",
    "image/x-png" to "image/png",
    "application/x-pdf" to "application/pdf",
)

/**
 * Filename-extension fallback consulted only once the declared type has already been ruled
 * uninformative (missing/blank/[GENERIC_OCTET_STREAM]) -- takes precedence over [extensionLookup]
 * because [MimeTypeMap] itself has no entry for some of these extensions on several API levels
 * (notably "m4a"). Mirrors [CONTENT_TYPE_ALIASES]'s canonical values.
 */
private val EXTENSION_OVERRIDES = mapOf(
    "m4a" to "audio/mp4",
)
