package org.ntust.app.tigerduck.shared.tls

import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509ExtendedTrustManager

/**
 * A connection made under pins a newer document has since changed is held to the pins in force
 * now: closed while idle, and refused request by request while it stays open.
 */
class OpenConnectionPinsTest {

    private val root = HeldCertificate.Builder().certificateAuthority(0).commonName("Test Root").build()
    private val leaf = HeldCertificate.Builder().addSubjectAlternativeName("localhost").signedBy(root).build()
    private val trust =
        HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build().trustManager as X509ExtendedTrustManager
    private val server = LocalTlsServer(leaf, emptyList(), keepAlive = true)

    private val now = Instant.parse("2026-10-25T00:00:00Z")
    private val pinned = PinSet("test", listOf(PinDomain("localhost", false)), setOf(root.certificate.spki), now.plusSeconds(86_400))
    private val state = PinState(PinPolicy(builtIn = listOf(pinned), appVersion = 30))

    /** A document that pins localhost to a key no certificate here has. */
    private val dropsThePin = PinDocument(
        serial = BuiltInPins.BASELINE_SERIAL + 1,
        issuedAt = now,
        expiresAt = now.plusSeconds(3600),
        sets = listOf(pinned.copy(pins = setOf(BOGUS))),
        promptRoots = emptySet(),
        envelope = ByteArray(0),
    )

    @After
    fun close() = server.close()

    private fun tls(watching: PinState?) =
        PinnedTls(PinnedTrustManager(trust, AnchoringValidator(trust), { state.policy }, {}, { now }), state = watching)

    private fun PinnedTls.client() = pinnedClientBuilder(this)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).dns(LOOPBACK_DNS)
        .build()

    private fun okhttp3.OkHttpClient.get(): String =
        newCall(Request.Builder().url("https://localhost:${server.port}/").build()).execute().use { it.body.string() }

    @Test
    fun `a request over a connection already open is refused once a document drops its pin`() {
        // Not told of the change, as a connection busy with another call isn't closed by it.
        val client = tls(watching = null).client()
        assertEquals("ok", client.get())

        state.update { it.with(document = dropsThePin) }
        val failure = runCatching { client.get() }.exceptionOrNull()

        assertNotNull("the request went out: $failure", (failure as? IOException)?.pinMismatch())
        assertEquals(1, server.requestLines.size)
        assertEquals(1, server.connections.get())
    }

    @Test
    fun `a document that changes the pins closes the idle connections made under the old ones`() {
        val tls = tls(watching = state)
        assertEquals("ok", tls.client().get())
        assertEquals(1, tls.connectionPool.connectionCount())

        state.update { it.with(document = dropsThePin) }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (tls.connectionPool.connectionCount() > 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(0, tls.connectionPool.connectionCount())
    }

    @Test
    fun `an acceptance, which only adds pins, leaves them open`() {
        val tls = tls(watching = state)
        assertEquals("ok", tls.client().get())

        state.update { it.with(acceptances = listOf(PinAcceptance("test", setOf(BOGUS), 30, 1))) }

        Thread.sleep(200)
        assertEquals(1, tls.connectionPool.connectionCount())
    }

    private companion object {
        val BOGUS: String = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    }
}
