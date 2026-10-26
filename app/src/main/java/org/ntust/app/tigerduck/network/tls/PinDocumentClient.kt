package org.ntust.app.tigerduck.network.tls

import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Where the pin document comes from: TigerSync, or a test's hand-made envelopes. */
fun interface PinDocumentSource {
    /** The envelope's bytes, or null on any failure; past every HTTP cache when [fresh]. */
    fun fetch(fresh: Boolean): ByteArray?
}

/**
 * Fetches TigerSync's pin document (TLS pins spec, A3 and A5). Its client is the one HTTPS
 * client the app builds without [PinnedTls][org.ntust.app.tigerduck.shared.tls.PinnedTls]: it
 * takes system trust alone, on purpose, so no pinned-host failure, api.tigerduck.app's
 * included, keeps the app from new pins. The document's integrity is its signature's.
 * AllHttpsClientsPinnedTest names this file as the exception.
 */
class PinDocumentClient(
    private val url: String = URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
) : PinDocumentSource {

    override fun fetch(fresh: Boolean): ByteArray? = runCatching {
        val request = Request.Builder()
            .url(url)
            .apply { if (fresh) cacheControl(CacheControl.FORCE_NETWORK).header("Cache-Control", "no-cache") }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val source = response.body.source()
            // More than the cap is no document; less is read whole.
            if (source.request(MAX_BYTES + 1L)) return null
            source.buffer.readByteArray()
        }
    }.getOrNull()

    companion object {
        const val URL = "https://api.tigerduck.app/.well-known/tigerduck/tls-pins.json"

        /** A document of a few sets and Mozilla's ~150 roots is a few tens of kilobytes. */
        private const val MAX_BYTES = 1 shl 20
    }
}
