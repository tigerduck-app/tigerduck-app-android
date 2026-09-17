package org.ntust.app.tigerduck.ui.screen.mail

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.mail.compose.ComposeRules
import org.ntust.app.tigerduck.mail.mime.TextCleaning
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject

/**
 * Reads a locally picked SAF document's metadata (name/type/size) without touching its bytes --
 * the stream returned as [ComposeAttachment.Source.Local.open] is only opened for real when the
 * mail is sent or saved as a draft. A single-method seam (not called directly from a Composable)
 * so [SchoolMailComposeViewModel] can dispatch this onto its injected `io` dispatcher instead of
 * the caller's, and so a test can supply a trivial fake without touching `android.net.Uri`/
 * `ContentResolver` at all.
 */
fun interface PickedAttachmentReader {
    fun describe(uri: Uri): ComposeAttachment?
}

/** The real, `ContentResolver`-backed implementation; only ever driven from [io][org.ntust.app.tigerduck.di.IoDispatcher]. */
class ContentResolverAttachmentReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : PickedAttachmentReader {
    override fun describe(uri: Uri): ComposeAttachment? {
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
        // The provider may report no size at all (no SIZE column, or a query exception), or an
        // AssetFileDescriptor whose own .length is UNKNOWN_LENGTH (-1) -- either way this must
        // never leave sizeBytes negative or zero-by-default, or the 50 MB check downstream would
        // silently never see this attachment.
        val size = queriedSize ?: assetFileDescriptorSize(resolver, uri) ?: streamedSize(open)
        return ComposeAttachment(
            id = uri.toString(),
            fileName = TextCleaning.clean(name).ifBlank { "attachment" },
            contentType = resolver.getType(uri) ?: "application/octet-stream",
            sizeBytes = size,
            source = ComposeAttachment.Source.Local(open),
        )
    }

    private fun assetFileDescriptorSize(resolver: ContentResolver, uri: Uri): Long? =
        runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()?.takeIf { it >= 0 }

    /**
     * Measures a size the provider couldn't report by counting bytes as they stream in, stopping
     * as soon as the running total alone would already fail [ComposeRules.fitsSizeLimit] -- for a
     * huge file this reads only as much as it takes to prove the 50 MB limit is blown, not the
     * whole thing. A file that streams to EOF before that point is measured exactly.
     */
    private fun streamedSize(open: () -> InputStream): Long = runCatching {
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
    }.getOrDefault(0L)

    private companion object {
        const val STREAM_BUFFER_BYTES = 64 * 1024
    }
}
