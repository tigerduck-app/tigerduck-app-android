package org.ntust.app.tigerduck.shared.tls

import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.X509ExtendedTrustManager

/**
 * [PinnedTrustManager.meetsPins]: the leaf a WebView gives for its page, held to the pins the
 * app's own requests meet. The validator completes the chain from an intermediate it already
 * knows, as Android's does from the chains it validated before, and the pins name CAs alone.
 */
class PageChainPinsTest {

    private val root = HeldCertificate.Builder().certificateAuthority(1).commonName("Test Root").build()
    private val intermediate = HeldCertificate.Builder().certificateAuthority(0).commonName("Test CA").signedBy(root).build()
    private val otherCa = HeldCertificate.Builder().certificateAuthority(0).commonName("Other CA").signedBy(root).build()
    private val leaf = HeldCertificate.Builder().addSubjectAlternativeName(HOST).signedBy(intermediate).build().certificate

    private val systemTrust =
        HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build().trustManager as X509ExtendedTrustManager

    private val now = Instant.parse("2026-10-25T00:00:00Z")
    private val asked = mutableListOf<Pair<List<X509Certificate>, String>>()
    private val mismatches = mutableListOf<PinMismatch>()

    /** Builds the chain up to the root from the leaf alone, or refuses as the platform does. */
    private var validator = ChainValidator { chain, _, host ->
        asked += chain.toList() to host
        val served = chain.first()
        if (runCatching { served.verify(intermediate.certificate.publicKey) }.isFailure) throw CertificateException("no issuer known")
        listOf(served, intermediate.certificate, root.certificate)
    }

    private fun set(pins: Set<String>, expiresAt: Instant = Instant.parse("2027-01-01T00:00:00Z")) =
        PinSet("test", listOf(PinDomain(HOST, includeSubdomains = false)), pins, expiresAt)

    private fun manager(vararg sets: PinSet) = PinnedTrustManager(
        platform = systemTrust,
        validator = { chain, authType, host -> validator.validate(chain, authType, host) },
        policy = { PinPolicy(builtIn = sets.toList(), document = null, acceptances = emptyList(), appVersion = 30) },
        onMismatch = { mismatches += it },
        now = { now },
    )

    @Test
    fun `a leaf whose chain reaches a pinned CA meets the pins`() {
        assertTrue(manager(set(setOf(intermediate.certificate.spki))).meetsPins(HOST, listOf(leaf)))
        assertTrue(manager(set(setOf(root.certificate.spki))).meetsPins(HOST, listOf(leaf)))
        assertEquals(listOf(listOf(leaf) to HOST), asked.take(1))
    }

    @Test
    fun `a chain that shares no pin does not, and no mismatch is told`() {
        assertFalse(manager(set(setOf(otherCa.certificate.spki))).meetsPins(HOST, listOf(leaf)))
        assertTrue(mismatches.isEmpty())
    }

    @Test
    fun `a leaf the platform cannot build a chain for does not`() {
        val stranger = HeldCertificate.Builder().addSubjectAlternativeName(HOST).signedBy(otherCa).build().certificate
        assertFalse(manager(set(setOf(root.certificate.spki))).meetsPins(HOST, listOf(stranger)))
        validator = ChainValidator { _, _, _ -> throw IllegalArgumentException("no chain") }
        assertFalse(manager(set(setOf(root.certificate.spki))).meetsPins(HOST, listOf(leaf)))
    }

    @Test
    fun `a host no set covers does not, though the platform trusts it`() {
        assertFalse(manager(set(setOf(root.certificate.spki))).meetsPins("elsewhere.test", listOf(leaf)))
        assertFalse(manager().meetsPins(HOST, listOf(leaf)))
    }

    @Test
    fun `no certificate does not`() {
        assertFalse(manager(set(setOf(root.certificate.spki))).meetsPins(HOST, emptyList()))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a set past its expiry no longer enforces, as for the app's own requests`() {
        val expired = set(setOf(otherCa.certificate.spki), expiresAt = now.minusSeconds(1))
        assertTrue(manager(expired).meetsPins(HOST, listOf(leaf)))
    }

    @Test
    fun `a chain holding a compromised CA does not, whatever the pins say`() {
        val compromised = requireNotNull(javaClass.getResourceAsStream("/tls/emsign-em-ov-tls-ca-g3c.pem")).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        validator = ChainValidator { chain, _, _ -> listOf(chain.first(), compromised) }
        assertFalse(manager(set(setOf(compromised.spki))).meetsPins(HOST, listOf(leaf)))
    }

    private companion object {
        const val HOST = "pinned.example.com"
    }
}
