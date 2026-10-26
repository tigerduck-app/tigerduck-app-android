package org.ntust.app.tigerduck.shared.tls

import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.X509ExtendedTrustManager

/** A call that failed on a pin mismatch is tried once more when the resolver allows it. */
class PinMismatchRetryTest {

    private val root = HeldCertificate.Builder().certificateAuthority(0).commonName("Test Root").build()
    private val leaf = HeldCertificate.Builder().addSubjectAlternativeName("localhost").signedBy(root).build()
    private val trust =
        HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build().trustManager as X509ExtendedTrustManager
    private val server = LocalTlsServer(leaf, emptyList())

    private val pinned = PinSet("test", listOf(PinDomain("localhost", false)), setOf(BOGUS), Instant.parse("2030-01-01T00:00:00Z"))
    private val state = PinState(PinPolicy(builtIn = listOf(pinned), appVersion = 30))
    private val asked = AtomicInteger()

    @After
    fun close() = server.close()

    private fun client(resolver: PinMismatchResolver) = pinnedClientBuilder(
        PinnedTls(PinnedTrustManager(trust, AnchoringValidator(trust), { state.policy }, {}), resolver),
    ).connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).dns(LOOPBACK_DNS).build()

    private fun call(resolver: PinMismatchResolver) =
        client(resolver).newCall(Request.Builder().url("https://localhost:${server.port}/").build()).execute().use { it.body.string() }

    @Test
    fun `a call the resolver clears is tried again and goes through`() {
        val result = call { mismatch, _ ->
            asked.incrementAndGet()
            // As a newer document or a Continue would.
            state.update { it.with(acceptances = listOf(PinAcceptance(mismatch.setId, mismatch.acceptableSpkis, 30, 1))) }
            true
        }

        assertEquals("ok", result)
        assertEquals(1, asked.get())
    }

    @Test
    fun `a call the resolver doesn't clear fails on its mismatch, untried again`() {
        try {
            call { _, _ -> asked.incrementAndGet(); false }
            fail("the call went through")
        } catch (e: IOException) {
            assertNotNull(e.pinMismatch())
        }
        assertEquals(1, asked.get())
        assertEquals(0, server.requestBytes.get())
    }

    @Test
    fun `a retry that fails again isn't tried a third time`() {
        val seen = AtomicReference<PinMismatch>()
        try {
            call { mismatch, _ -> seen.set(mismatch); asked.incrementAndGet(); true }
            fail("the call went through")
        } catch (e: IOException) {
            assertNotNull(e.pinMismatch())
        }
        assertEquals(1, asked.get())
    }

    private companion object {
        val BOGUS: String = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    }
}
