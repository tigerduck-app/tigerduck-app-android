package org.ntust.app.tigerduck.shared.tls

import okhttp3.Request
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * A server that leaves its intermediate out of the chain it sends, as www.academic.ntust.edu.tw
 * does (the leaf, the leaf again, and the root). Android completes the chain, so the handshake goes
 * through; the check each request meets on the open connection has to take the chain as the
 * handshake did, not as OkHttp's own chain cleaner rebuilds it from the roots alone, which it can't.
 */
class MissingIntermediateTest {

    private val root = HeldCertificate.Builder().certificateAuthority(1).commonName("Test Root").build()
    private val intermediate =
        HeldCertificate.Builder().certificateAuthority(0).commonName("Test Intermediate").signedBy(root).build()
    private val leaf = HeldCertificate.Builder().addSubjectAlternativeName("localhost").signedBy(intermediate).build()

    // The leaf alone: no intermediate. (A test server can't send the root after a leaf it didn't
    // sign, as www.academic sends it; without the intermediate either way, the chain can't be
    // built from the roots alone.)
    private val server = LocalTlsServer(leaf, emptyList(), keepAlive = true)

    /**
     * Android's trust as it meets such a server: it knows the intermediate and completes the chain,
     * while the only issuers it lists as accepted are the roots.
     */
    private val platform = object : X509ExtendedTrustManager() {
        private fun check(chain: Array<X509Certificate>) {
            val served = chain.first()
            if (runCatching { served.verify(intermediate.certificate.publicKey) }.isFailure) {
                throw CertificateException("not issued by the intermediate")
            }
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = check(chain)
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = check(chain)
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = check(chain)
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = Unit
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = Unit
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(root.certificate)
    }

    /** X509TrustManagerExtensions on Android: the chain completed up to its root. */
    private val validator = ChainValidator { chain, authType, _ ->
        platform.checkServerTrusted(chain, authType)
        listOf(chain.first(), intermediate.certificate, root.certificate)
    }

    private val now = Instant.parse("2026-10-25T00:00:00Z")
    private val pinned = PinSet("test", listOf(PinDomain("localhost", false)), setOf(root.certificate.spki), now.plusSeconds(86_400))
    private val state = PinState(PinPolicy(builtIn = listOf(pinned), appVersion = 30))

    @After
    fun close() = server.close()

    private fun client() = pinnedClientBuilder(PinnedTls(PinnedTrustManager(platform, validator, { state.policy }, {}, { now })))
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).dns(LOOPBACK_DNS)
        .build()

    private fun okhttp3.OkHttpClient.get(): String =
        newCall(Request.Builder().url("https://localhost:${server.port}/").build()).execute().use { it.body.string() }

    @Test
    fun `a pinned host that leaves its intermediate out is reached, as its handshake was`() {
        assertEquals("ok", client().get())
    }

    @Test
    fun `and again over the same open connection`() {
        val client = client()
        assertEquals("ok", client.get())
        assertEquals("ok", client.get())
        assertEquals(1, server.connections.get())
    }
}
