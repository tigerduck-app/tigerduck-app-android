package org.ntust.app.tigerduck.shared.tls

import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import javax.net.ssl.SSLSocket

/**
 * The one way TigerDuck starts an OkHttp client (A9): with [tls]'s pinned trust, and a call
 * that failed on a pin mismatch tried once more when its [PinMismatchResolver] allows. Clients
 * made from it with `newBuilder()` keep both. AllHttpsClientsPinnedTest fails on an
 * `OkHttpClient.Builder()` anywhere else.
 *
 * [tls] is null only in tests, for the platform's trust.
 */
fun pinnedClientBuilder(tls: PinnedTls?): OkHttpClient.Builder {
    val builder = OkHttpClient.Builder()
    if (tls != null) {
        builder.sslSocketFactory(tls.socketFactory, tls.trustManager)
        // Shared, so a change of pins can close every client's idle connections.
        builder.connectionPool(tls.connectionPool)
        val retry = PinMismatchRetry(tls.resolver)
        // First, so a retry passes through every interceptor the client adds after it. One that
        // marks a call for its whole life, as AccountSessionGuard's does, goes ahead of it.
        builder.addInterceptor(retry)
        (tls.trustManager as? PinnedTrustManager)?.let { builder.addNetworkInterceptor(ConnectionPins(it)) }
        // Once the connection's pins are met: only then is a hop on its way out.
        builder.addNetworkInterceptor(retry.noteSent)
    }
    return builder
}

/**
 * Each request meets its host's pins in force now on the connection it goes out on, before a
 * byte of it is written: one pooled since pins a newer document dropped, or an HTTP/2 connection
 * made for another host, which OkHttp carries it over without a new handshake.
 *
 * The chain is the one the server sent, from the TLS session, as the handshake's check had it.
 * OkHttp's own `handshake().peerCertificates` is that chain rebuilt by its chain cleaner, which,
 * for a trust manager that isn't the platform's own, has only the roots to build from: a server
 * that leaves its intermediate out, as www.academic.ntust.edu.tw does, came back with no chain at
 * all, and every request to it failed though its handshake had passed.
 */
class ConnectionPins(private val trust: PinnedTrustManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val connection = chain.connection()
        if (connection != null) {
            val served = (connection.socket() as? SSLSocket)?.session?.peerCertificates?.toList()
            trust.checkExchange(
                host = chain.request().url.host,
                peerCertificates = served ?: connection.handshake()?.peerCertificates.orEmpty(),
                connection = connection,
            )
        }
        return chain.proceed(chain.request())
    }
}

/**
 * Tries a call once more after it failed on a [PinMismatch], when the resolver says to: a newer
 * pin document allows the chain now, or the student chose Continue. The failed hop sent nothing,
 * but one before it, ahead of a redirect, may have done what the call asked: such a call is tried
 * again only for a GET or a HEAD, which asking again doesn't repeat.
 */
class PinMismatchRetry(private val resolver: PinMismatchResolver) : Interceptor {

    private val sent: MutableSet<Call> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /** Network interceptor: notes a call one of whose hops is on its way out. */
    val noteSent = Interceptor { chain ->
        sent += chain.call()
        chain.proceed(chain.request())
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return try {
            chain.proceed(request)
        } catch (e: IOException) {
            val mismatch = e.pinMismatch() ?: throw e
            val call = chain.call()
            if (call in sent && request.method != "GET" && request.method != "HEAD") throw e
            if (!resolver.mayRetry(mismatch) { call.isCanceled() }) throw e
            chain.proceed(request)
        }
    }
}
