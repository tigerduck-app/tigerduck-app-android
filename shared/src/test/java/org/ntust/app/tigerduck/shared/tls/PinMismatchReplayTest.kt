package org.ntust.app.tigerduck.shared.tls

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.X509ExtendedTrustManager

/**
 * A call whose redirect met a pin mismatch already sent its first hop: it is tried again only
 * when asking again is safe, so a POST that went through is never sent twice.
 */
class PinMismatchReplayTest {

    private val root = HeldCertificate.Builder().certificateAuthority(0).commonName("Test Root").build()
    private val leaf = HeldCertificate.Builder()
        .addSubjectAlternativeName("localhost")
        .addSubjectAlternativeName("127.0.0.1")
        .signedBy(root)
        .build()
    private val trust =
        HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build().trustManager as X509ExtendedTrustManager

    /** Everything but /next is sent on to /next on the other host. */
    private val server: LocalTlsServer = LocalTlsServer(leaf, emptyList()) { line: String ->
        if (" /next " in line) LocalTlsServer.OK
        else "HTTP/1.1 302 Found\r\nLocation: https://127.0.0.1:${server.port}/next\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    }

    private val expiry = Instant.parse("2030-01-01T00:00:00Z")
    private val first = PinSet("first", listOf(PinDomain("localhost", false)), setOf(root.certificate.spki), expiry)
    private val second = PinSet("second", listOf(PinDomain("127.0.0.1", false)), setOf(BOGUS), expiry)
    private val state = PinState(PinPolicy(builtIn = listOf(first, second), appVersion = 30))
    private val asked = AtomicInteger()

    /** Clears every mismatch, as a Continue would. */
    private val resolver = PinMismatchResolver { mismatch, _ ->
        asked.incrementAndGet()
        state.update { it.with(acceptances = listOf(PinAcceptance(mismatch.setId, mismatch.acceptableSpkis, 30, 1))) }
        true
    }

    private val client = pinnedClientBuilder(
        PinnedTls(PinnedTrustManager(trust, AnchoringValidator(trust), { state.policy }, {}), resolver),
    ).connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).dns(LOOPBACK_DNS).build()

    @After
    fun close() = server.close()

    private fun url() = "https://localhost:${server.port}/"

    @Test
    fun `a POST whose redirect met a mismatch isn't sent again`() {
        val request = Request.Builder().url(url()).post("x".toRequestBody()).build()

        val failure = runCatching { client.newCall(request).execute().use { it.body.string() } }.exceptionOrNull()

        assertNotNull("the call went through", failure?.pinMismatch())
        assertEquals(listOf("POST / HTTP/1.1"), server.requestLines.toList())
        assertEquals(0, asked.get())
    }

    @Test
    fun `a GET whose redirect met a mismatch is asked again once it is cleared`() {
        val body = client.newCall(Request.Builder().url(url()).build()).execute().use { it.body.string() }

        assertEquals("ok", body)
        assertEquals(listOf("GET / HTTP/1.1", "GET / HTTP/1.1", "GET /next HTTP/1.1"), server.requestLines.toList())
        assertEquals(1, asked.get())
    }

    private companion object {
        val BOGUS: String = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    }
}
