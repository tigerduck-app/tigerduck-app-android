package org.ntust.app.tigerduck.browser

import kotlinx.coroutines.CancellationException
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ntust.app.tigerduck.shared.tls.PinnedTls
import org.ntust.app.tigerduck.shared.tls.pinnedClientBuilder
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

/**
 * The leaf certificate a school site serves the app's own pinned TLS, which a page the WebView
 * shows is matched with before it gets the stored password ([BrowserSiteSignIn]). One HEAD of the
 * page, with no cookies, no credentials and no redirect followed; its answer, whatever its status,
 * is not read. The connection it goes out on has met the pins in force for its host and OkHttp's
 * check of the host name.
 *
 * The leaf is the one the server sent on that connection's TLS session, as `ConnectionPins` reads
 * it: OkHttp's own `handshake.peerCertificates` is rebuilt from the roots alone and comes back
 * empty for a server that leaves its intermediate out.
 */
class PinnedPageCertificate(private val client: OkHttpClient) {

    /** The leaf [url]'s server sent, or null when the request failed: the pins, the network, the time. */
    suspend fun leaf(url: String): X509Certificate? {
        val served = Served()
        val request = Request.Builder().url(url).head().tag(Served::class.java, served).build()
        return try {
            client.newCall(request).await().close()
            served.leaf
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** What the server sent on the connection a request went out on. */
    private class Served {
        @Volatile var leaf: X509Certificate? = null
    }

    companion object {
        const val TIMEOUT_SECONDS = 5L

        /** Network interceptor, after the connection's pins are met: notes the leaf the request went out beside. */
        private val noteLeaf = Interceptor { chain ->
            val session = (chain.connection()?.socket() as? SSLSocket)?.session
            chain.request().tag(Served::class.java)?.leaf = session?.peerCertificates?.firstOrNull() as? X509Certificate
            chain.proceed(chain.request())
        }

        /** The app's pinned client, with no cookie jar, no authenticator and no redirect followed. */
        fun client(tls: PinnedTls, demoMode: Interceptor): OkHttpClient = pinnedClientBuilder(tls)
            .addInterceptor(demoMode)
            .addNetworkInterceptor(noteLeaf)
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}
