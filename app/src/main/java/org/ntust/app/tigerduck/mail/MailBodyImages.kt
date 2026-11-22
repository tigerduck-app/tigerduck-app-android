package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ntust.app.tigerduck.browser.await
import org.ntust.app.tigerduck.files.RasterImage
import org.ntust.app.tigerduck.mail.sanitize.HtmlSanitizer
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.shared.tls.PinnedTls
import org.ntust.app.tigerduck.shared.tls.pinnedClientBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * An image of a mail's formatted body, opened full screen when it is tapped, as on the iPhone
 * (tigerduck-app#1047). Its bytes come from the mail: the `data:` address an inline part is drawn
 * from ([org.ntust.app.tigerduck.ui.screen.mail.MailHtmlDocument.inlineCids]), or one the sender
 * wrote. Only once the reader has let remote images in do they come from its web address too,
 * fetched on [client]: the body is read only after a 2xx answer that announces a picture of at
 * most [MailLimits.INLINE_IMAGE_BYTES], reading stops past that many whatever was announced, and
 * the fetch gives up after [TIMEOUT_SECONDS].
 *
 * Only a picture its own bytes say is a [RasterImage] opens, whatever it was sent as: an SVG or a
 * web page sent as a PNG would run in whatever showed it, and a PNG sent as a JPEG opens as the
 * PNG it is.
 */
class MailBodyImages(
    private val client: Call.Factory,
    private val cache: MailCache,
    private val io: CoroutineDispatcher,
) {
    /** A tapped image written out as a file, to open as an attachment opens. */
    data class Opened(val file: File, val mimeType: String)

    /** An image's bytes, with the name its address gives it, if any. */
    internal class Loaded(val bytes: ByteArray, val name: String?)

    /**
     * The image the page has at [source], written into an attachment's folder, which the
     * FileProvider serves; null for one that is no picture this opens, or that [stillCurrent],
     * asked under the cache's lock as it is written ([MailCache.saveAttachmentIf]), says has
     * outlived the sign-in it was tapped under.
     */
    suspend fun open(source: String, remoteAllowed: Boolean, stillCurrent: () -> Boolean = { true }): Opened? = withContext(io) {
        val loaded = load(source, remoteAllowed) ?: return@withContext null
        val kind = RasterImage.of(loaded.bytes) ?: return@withContext null
        cache.saveAttachmentIf(MailFileNames.image(loaded.name, kind), loaded.bytes, stillCurrent)?.let { Opened(it, kind.mimeType) }
    }

    /** [source]'s bytes: a `data:` image's own, or, while [remoteAllowed], a web address's. */
    internal suspend fun load(source: String, remoteAllowed: Boolean): Loaded? {
        HtmlSanitizer.dataImageBytes(source)?.let { return Loaded(it, null) }
        if (!remoteAllowed) return null
        // Only an http or https address parses: nothing else is fetched.
        val url = source.trim().toHttpUrlOrNull() ?: return null
        return fetch(url)?.let { Loaded(it, url.pathSegments.lastOrNull()?.takeIf(String::isNotBlank)) }
    }

    private suspend fun fetch(url: HttpUrl): ByteArray? = try {
        client.newCall(Request.Builder().url(url).build()).await().use { response ->
            val body = response.body
            val fits = body.contentLength() <= MailLimits.INLINE_IMAGE_BYTES
            if (response.isSuccessful && announcesPicture(body.contentType()) && fits) readAtMost(body.byteStream()) else null
        }
    } catch (e: IOException) {
        null
    }

    /** The whole of [input], or null once it runs past [MailLimits.INLINE_IMAGE_BYTES]. */
    private suspend fun readAtMost(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > MailLimits.INLINE_IMAGE_BYTES) return null
            out.write(buffer, 0, read)
        }
    }

    companion object {
        const val TIMEOUT_SECONDS = 30L
        private const val BUFFER_BYTES = 64 * 1024

        /** The types a picture of [RasterImage]'s kinds is announced as, the common aliases among them. */
        private val ANNOUNCED_TYPES = RasterImage.entries.map { it.mimeType }.toSet() +
            setOf("image/jpg", "image/pjpeg", "image/x-bmp", "image/x-ms-bmp", "image/heic-sequence", "image/heif-sequence")

        private fun announcesPicture(type: MediaType?): Boolean =
            type != null && "${type.type}/${type.subtype}".lowercase() in ANNOUNCED_TYPES

        /**
         * The app's pinned client, with the demo's refusal on it, no cookie jar and no cache: the
         * sender's server sees no more than the page's own request for the image showed it. The
         * whole call, the body's read with it, ends after [TIMEOUT_SECONDS]. [tls] is null only in
         * tests, for the platform's trust.
         */
        fun client(tls: PinnedTls?, demoMode: Interceptor): OkHttpClient = pinnedClientBuilder(tls)
            .addInterceptor(demoMode)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}
