package org.ntust.app.tigerduck.shared.tls

import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * The eMudhra intermediates whose keys can be recovered (CVE-2026-111943) fail every chain
 * [PinnedTrustManager] checks, whatever the platform's store and the pins say. The fixture is
 * the real "EM OV TLS CA - G3C" certificate, one of the eight, as OkHttp 5.5.1's own test has
 * it; a hand-made leaf stands in for a certificate forged under it.
 */
class CompromisedCertificatesTest {

    private val compromised: X509Certificate =
        requireNotNull(javaClass.getResourceAsStream("/tls/emsign-em-ov-tls-ca-g3c.pem")).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private val forgedLeaf = HeldCertificate.Builder().addSubjectAlternativeName(HOST).build().certificate
    private val honestCa = HeldCertificate.Builder().certificateAuthority(0).commonName("Honest CA").build()
    private val honestLeaf = HeldCertificate.Builder().addSubjectAlternativeName(HOST).signedBy(honestCa).build().certificate

    /** A device whose store still trusts the emSign roots and the AAA cross-sign: it accepts anything. */
    private val trustingPlatform = object : X509ExtendedTrustManager() {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = Unit
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = Unit
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun manager(
        pins: Set<String>? = null,
        validator: ChainValidator = ChainValidator { chain, _, _ -> chain.toList() },
    ) = PinnedTrustManager(
        platform = trustingPlatform,
        validator = validator,
        policy = {
            val sets = pins?.let { listOf(PinSet("test", listOf(PinDomain(HOST, includeSubdomains = false)), it, Instant.parse("2027-01-01T00:00:00Z"))) }
            PinPolicy(builtIn = sets.orEmpty(), document = null, acceptances = emptyList(), appVersion = 30)
        },
        onMismatch = {},
        now = { Instant.parse("2026-10-25T00:00:00Z") },
    )

    private fun engineFor(host: String): SSLEngine = SSLContext.getInstance("TLS").apply { init(null, null, null) }.createSSLEngine(host, 443)

    @Test
    fun `the eight are OkHttp 5_5_1's, the fixture among them`() {
        assertEquals(8, CompromisedCertificates.spkiSha256Hex.size)
        assertTrue(CompromisedCertificates.contains(compromised))
        assertFalse(CompromisedCertificates.contains(honestCa.certificate))
    }

    @Test
    fun `the eight are exactly OkHttp's own blocklist`() {
        // Every hash, not only the fixture's: read from OkHttp's internal list, so a mistyped
        // hash fails here, and so does a later OkHttp that blocks more.
        val blocklist = Class.forName("okhttp3.internal.tls.CertificateBlocklist")
        val field = blocklist.getDeclaredField("blockedCertificateSha256s").apply { isAccessible = true }
        val okhttps = (field.get(null) as Set<*>).map { (it as okio.ByteString).hex() }.toSet()

        assertEquals(okhttps, CompromisedCertificates.spkiSha256Hex)
    }

    @Test
    fun `an unpinned host's chain through one is refused, though the platform trusts it`() {
        assertThrows(CompromisedCertificateException::class.java) {
            manager().checkServerTrusted(arrayOf(forgedLeaf, compromised), "ECDHE_ECDSA", engineFor("api.example.com"))
        }
    }

    @Test
    fun `a pinned host's chain through one is refused, even when a pin names it`() {
        assertThrows(CompromisedCertificateException::class.java) {
            manager(pins = setOf(compromised.spki)).checkServerTrusted(arrayOf(forgedLeaf, compromised), "ECDHE_ECDSA", engineFor(HOST))
        }
    }

    @Test
    fun `an unpinned host's chain the platform completes with one is refused`() {
        // The server leaves the intermediate out, and the platform supplies it from what it has.
        val completing = ChainValidator { chain, _, _ -> listOf(chain[0], compromised) }

        assertThrows(CompromisedCertificateException::class.java) {
            manager(validator = completing).checkServerTrusted(arrayOf(forgedLeaf), "ECDHE_ECDSA", engineFor("api.example.com"))
        }
    }

    @Test
    fun `a chain checked with no host is refused too`() {
        assertThrows(CompromisedCertificateException::class.java) {
            manager().checkServerTrusted(arrayOf(forgedLeaf, compromised), "ECDHE_ECDSA")
        }
        assertThrows(CompromisedCertificateException::class.java) {
            manager().checkServerTrusted(arrayOf(forgedLeaf, compromised), "ECDHE_ECDSA", null as Socket?)
        }
    }

    @Test
    fun `a chain without them goes on as before`() {
        manager().checkServerTrusted(arrayOf(honestLeaf, honestCa.certificate), "ECDHE_ECDSA", engineFor("api.example.com"))
        manager(pins = setOf(honestCa.certificate.spki)).checkServerTrusted(arrayOf(honestLeaf, honestCa.certificate), "ECDHE_ECDSA", engineFor(HOST))
    }

    private companion object {
        const val HOST = "pinned.example.com"
    }
}
