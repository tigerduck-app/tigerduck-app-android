package org.ntust.app.tigerduck.ui.screen.mail

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.mail.compose.ComposeRules
import org.ntust.app.tigerduck.mail.mime.TextCleaning
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Reads a locally picked SAF document's metadata (name/type/size) without touching its bytes --
 * the stream returned as [ComposeAttachment.Source.Local.open] is only opened for real when the
 * mail is sent or saved as a draft. A single-method seam (not called directly from a Composable)
 * so [SchoolMailComposeViewModel] can dispatch this onto its injected `io` dispatcher instead of
 * the caller's, and so a test can supply a trivial fake without touching a real `ContentResolver`.
 *
 * [describe]'s [uri] is nullable: no known Android launcher hands back a null entry, but declaring
 * it this way also lets a plain JVM unit test drive [SchoolMailComposeViewModel.addPicked] without
 * constructing a real `android.net.Uri` -- local unit tests run against a stub `android.jar` whose
 * `Uri.parse`/`Uri.EMPTY`/etc. all return null (or throw), and `Uri`'s own constructor is
 * package-private, so there is no way to obtain a working instance outside Robolectric.
 */
fun interface PickedAttachmentReader {
    fun describe(uri: Uri?): ComposeAttachment?
}

/**
 * The name a picked file goes out under. The system Photo Picker hands out content URIs whose
 * display name is the media store id with an extension -- `34.png` -- and that is the name the
 * recipient would see (#338). A name that is nothing but digits is replaced by one from the
 * photo's capture date in the form a camera gives, `IMG_20261015_143012.jpg`, with the extension
 * the picked file's [mimeType] has; a type with none keeps whatever the provider put after the
 * digits. Without a capture date the moment of picking stands in, so two photos picked a while
 * apart are at least told apart by it. In the device's own time zone, as the camera that named
 * the original did. Any other name is the user's file name and stays as it is -- which is why
 * [dateTakenMs] is asked only when the name needs it: it is a second query of the provider.
 *
 * [extensionLookup] is injected so this stays a pure, JVM-testable function, as
 * [org.ntust.app.tigerduck.mail.resolveAttachmentMimeType] does; production call sites omit it to
 * use the real [MimeTypeMap].
 */
internal fun pickedFileName(
    displayName: String,
    mimeType: String,
    dateTakenMs: () -> Long?,
    nowMs: () -> Long,
    zone: ZoneId = ZoneId.systemDefault(),
    extensionLookup: (mimeType: String) -> String? = { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) },
): String {
    val stem = displayName.substringBeforeLast('.')
    if (stem.isEmpty() || !stem.all { it in '0'..'9' }) return displayName
    val extension = extensionLookup(mimeType.substringBefore(';').trim().lowercase())
        ?: displayName.substringAfterLast('.', missingDelimiterValue = "").ifEmpty { null }
    val at = Instant.ofEpochMilli(dateTakenMs() ?: nowMs()).atZone(zone)
    return "IMG_" + PHOTO_NAME_TIME.format(at) + extension?.let { ".$it" }.orEmpty()
}

private val PHOTO_NAME_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

/** The real, `ContentResolver`-backed implementation; only ever driven from [io][org.ntust.app.tigerduck.di.IoDispatcher]. */
class ContentResolverAttachmentReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : PickedAttachmentReader {
    override fun describe(uri: Uri?): ComposeAttachment? {
        uri ?: return null
        val resolver = context.contentResolver
        var name: String? = null
        var queriedSize: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) c.getLong(1).takeIf { it >= 0 }?.let { queriedSize = it }
                }
            }
        }
        val open: () -> InputStream = { resolver.openInputStream(uri) ?: throw IOException("cannot open $uri") }
        // The provider may report no size at all (no SIZE column, or a query exception), an
        // AssetFileDescriptor whose own .length is UNKNOWN_LENGTH (-1), or fail to stream too --
        // when NONE of the three can determine a real size, the attachment is rejected outright
        // (returns null, same as any other unreadable pick) rather than silently added as "0 B",
        // which would sail straight past the 50 MB check downstream.
        val size = queriedSize ?: assetFileDescriptorSize(resolver, uri) ?: streamedSize(open) ?: return null
        val contentType = resolver.getType(uri) ?: "application/octet-stream"
        return ComposeAttachment(
            id = uri.toString(),
            fileName = pickedFileName(
                TextCleaning.clean(name).ifBlank { "attachment" },
                contentType,
                dateTakenMs = { dateTaken(resolver, uri) },
                nowMs = System::currentTimeMillis,
            ),
            contentType = contentType,
            sizeBytes = size,
            source = ComposeAttachment.Source.Local(open),
        )
    }

    /**
     * The photo's capture date in milliseconds, as the provider reports it: the Photo Picker's
     * own column first, then the media store's. Each is asked on its own, and apart from the name
     * and size query above, because a provider throws for a column it has not got rather than
     * answering null -- one it lacks must cost neither the other nor the attachment. Null when
     * neither answers.
     */
    private fun dateTaken(resolver: ContentResolver, uri: Uri): Long? = DATE_TAKEN_COLUMNS.firstNotNullOfOrNull { column ->
        runCatching {
            resolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 } else null
            }
        }.getOrNull()
    }

    private fun assetFileDescriptorSize(resolver: ContentResolver, uri: Uri): Long? =
        runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()?.takeIf { it >= 0 }

    /**
     * Measures a size the provider couldn't report by counting bytes as they stream in, stopping
     * as soon as the running total alone would already fail [ComposeRules.fitsSizeLimit] -- for a
     * huge file this reads only as much as it takes to prove the 50 MB limit is blown, not the
     * whole thing. A file that streams to EOF before that point is measured exactly. Null (not 0)
     * on any failure to open or read the stream -- a size that couldn't be measured is not the
     * same as a confirmed empty file.
     */
    private fun streamedSize(open: () -> InputStream): Long? = runCatching {
        open().use { stream ->
            var total = 0L
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            while (ComposeRules.fitsSizeLimit("", listOf(total))) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
            }
            total
        }
    }.getOrNull()

    private companion object {
        const val STREAM_BUFFER_BYTES = 64 * 1024

        /**
         * `MediaStore.PickerMediaColumns.DATE_TAKEN`, which the picker's URIs answer, and
         * `MediaStore.MediaColumns.DATE_TAKEN`, which a media store URI does -- the document
         * picker the platform falls back to without a Photo Picker can hand one out. Spelt out:
         * the picker's constant lives in a class API 33 added, and a string is all either is.
         */
        val DATE_TAKEN_COLUMNS = listOf("date_taken", "datetaken")
    }
}
