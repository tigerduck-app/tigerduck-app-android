package org.ntust.app.tigerduck.shared.tls

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509ExtendedTrustManager

/**
 * [PinnedTrustManager] over real handshakes with a local TLS server and hand-made certificates
 * (A12, enforcement). A test root stands in for the system's certificate authorities; the
 * platform's check is that root's trust, and [AnchoringValidator] builds the chain up to it as
 * the platform does.
 */
class PinnedTrustManagerTest {

    private val root = HeldCertificate.Builder().certificateAuthority(1).commonName("Test Root").build()
    private val intermediate = HeldCertificate.Builder().certificateAuthority(0).commonName("Test CA").signedBy(root).build()

    /** The intermediate the pins name, which the server doesn't serve. */
    private val pinnedCa = HeldCertificate.Builder().certificateAuthority(0).commonName("Pinned CA").signedBy(root).build()

    /** A later renewal through yet another intermediate under the same root. */
    private val renewalCa = HeldCertificate.Builder().certificateAuthority(0).commonName("Renewal CA").signedBy(root).build()

    private val leaf = leafSignedBy(intermediate)
    private val selfSigned = HeldCertificate.Builder().addSubjectAlternativeName(HOST).build()

    private val systemTrust: X509ExtendedTrustManager =
        HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build().trustManager as X509ExtendedTrustManager

    private val now = Instant.parse("2026-10-25T00:00:00Z")
    private val mismatches = Collections.synchronizedList(mutableListOf<PinMismatch>())
    private val servers = mutableListOf<LocalTlsServer>()

    @After
    fun closeServers() = servers.forEach { it.close() }

    private fun set(pins: Set<String>, expiresAt: Instant = Instant.parse("2027-01-01T00:00:00Z")) =
        PinSet("test", listOf(PinDomain(HOST, includeSubdomains = false)), pins, expiresAt)

    private fun client(policy: PinPolicy): OkHttpClient {
        val tls = PinnedTls(
            PinnedTrustManager(
                platform = systemTrust,
                validator = AnchoringValidator(systemTrust),
                policy = { policy },
                onMismatch = { mismatches += it },
                now = { now },
            ),
        )
        return pinnedClientBuilder(tls)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).dns(LOOPBACK_DNS)
            .build()
    }

    private fun policy(set: PinSet, acceptances: List<PinAcceptance> = emptyList(), version: Long = 30) =
        PinPolicy(builtIn = listOf(set), document = null, acceptances = acceptances, appVersion = version)

    /** A server that leaves the root out of its chain, as most do. */
    private fun serve(served: HeldCertificate = leaf, chain: HeldCertificate? = intermediate) =
        LocalTlsServer(served, listOfNotNull(chain?.certificate)).also { servers += it }

    private fun OkHttpClient.get(server: LocalTlsServer): String =
        newCall(Request.Builder().url("https://$HOST:${server.port}/code").build()).execute().use { it.body.string() }

    private fun OkHttpClient.failure(server: LocalTlsServer): IOException =
        try {
            get(server)
            fail("the call went through")
            throw AssertionError()
        } catch (e: IOException) {
            e
        }

    @Test
    fun `a chain with a pinned intermediate is allowed`() {
        val server = serve()

        assertEquals("ok", client(policy(set(setOf(intermediate.certificate.spki)))).get(server))
        assertTrue(mismatches.isEmpty())
    }

    @Test
    fun `a root pin matches though the server doesn't send the root`() {
        val server = serve()

        assertEquals("ok", client(policy(set(setOf(root.certificate.spki)))).get(server))
    }

    @Test
    fun `a system-valid chain sharing no pin fails as a mismatch, before the request is written`() {
        val server = serve()

        val failure = client(policy(set(setOf(pinnedCa.certificate.spki)))).failure(server)

        val mismatch = checkNotNull(failure.pinMismatch()) { "not a pin mismatch: $failure" }
        assertEquals("test", mismatch.setId)
        assertEquals(HOST, mismatch.host)
        // Validated up to the anchor the server left out.
        assertEquals(listOf(leaf.certificate, intermediate.certificate, root.certificate), mismatch.chain)
        assertEquals(root.certificate.spki, mismatch.anchorSpki)
        assertEquals(1, mismatches.size)
        assertEquals(0, server.requestBytes.get())
    }

    @Test
    fun `a chain system trust refuses fails as the platform failed, never as a mismatch`() {
        val server = serve(selfSigned, chain = null)

        val failure = client(policy(set(setOf(pinnedCa.certificate.spki)))).failure(server)

        assertNull(failure.pinMismatch())
        assertTrue(mismatches.isEmpty())
        assertEquals(0, server.requestBytes.get())
    }

    @Test
    fun `a set past its expiry no longer enforces`() {
        val server = serve()

        val expired = set(setOf(pinnedCa.certificate.spki), expiresAt = now.minusSeconds(1))

        assertEquals("ok", client(policy(expired)).get(server))
    }

    @Test
    fun `an accepted chain passes, and its acceptance holds no root`() {
        val pins = set(setOf(pinnedCa.certificate.spki))
        val mismatch = client(policy(pins)).failure(serve()).pinMismatch()!!
        val acceptance = PinAcceptance("test", mismatch.acceptableSpkis, appVersion = 30, acceptedAt = 1L)

        assertEquals(setOf(leaf.certificate.spki, intermediate.certificate.spki), acceptance.spkis)
        assertFalse(root.certificate.spki in acceptance.spkis)
        assertEquals("ok", client(policy(pins, listOf(acceptance))).get(serve()))
    }

    @Test
    fun `an acceptance doesn't trust another intermediate under the same root`() {
        val pins = set(setOf(pinnedCa.certificate.spki))
        val mismatch = client(policy(pins)).failure(serve()).pinMismatch()!!
        val acceptance = PinAcceptance("test", mismatch.acceptableSpkis, appVersion = 30, acceptedAt = 1L)
        // A further CA change, under the root the acceptance never stored.
        val renewed = serve(leafSignedBy(renewalCa), chain = renewalCa)

        assertNotNull(client(policy(pins, listOf(acceptance))).failure(renewed).pinMismatch())
    }

    @Test
    fun `an acceptance made under another build number is dropped`() {
        val pins = set(setOf(pinnedCa.certificate.spki))
        val mismatch = client(policy(pins)).failure(serve()).pinMismatch()!!
        val acceptance = PinAcceptance("test", mismatch.acceptableSpkis, appVersion = 29, acceptedAt = 1L)

        assertNotNull(client(policy(pins, listOf(acceptance), version = 30)).failure(serve()).pinMismatch())
    }

    @Test
    fun `a host no set covers gets system trust alone`() {
        val server = serve()
        val elsewhere = PinSet("other", listOf(PinDomain("other.test", false)), setOf(pinnedCa.certificate.spki), now.plusSeconds(3600))

        assertEquals("ok", client(policy(elsewhere)).get(server))
    }

    @Test
    fun `every request over an open connection meets its host's pins in force now`() {
        val school = PinSet("school", listOf(PinDomain("school.test", true)), setOf(pinnedCa.certificate.spki), now.plusSeconds(3600))
        val trust = PinnedTrustManager(systemTrust, AnchoringValidator(systemTrust), { policy(school) }, {}, { now })
        val served = listOf(leaf.certificate, intermediate.certificate, root.certificate)

        // Its chain isn't the school's, whichever host the connection was made for.
        val refused = runCatching { trust.checkExchange("moodle.school.test", served, Any()) }.exceptionOrNull()
        assertTrue("not refused: $refused", refused is javax.net.ssl.SSLPeerUnverifiedException)

        // A chain that meets them, or a host no set covers.
        trust.checkExchange("moodle.school.test", listOf(leafSignedBy(pinnedCa).certificate, pinnedCa.certificate), Any())
        trust.checkExchange("other.test", served, Any())
    }

    @Test
    fun `an open connection's mismatch is one like a handshake's, so it can be refreshed, offered and retried`() {
        val school = PinSet("school", listOf(PinDomain("school.test", true)), setOf(pinnedCa.certificate.spki), now.plusSeconds(3600))
        val trust = PinnedTrustManager(systemTrust, AnchoringValidator(systemTrust), { policy(school) }, mismatches::add, { now })

        val refused = runCatching {
            trust.checkExchange("moodle.school.test", listOf(leaf.certificate, intermediate.certificate), Any())
        }.exceptionOrNull()

        assertTrue("not refused: $refused", refused is javax.net.ssl.SSLPeerUnverifiedException)
        val mismatch = refused?.pinMismatch()
        assertEquals("school", mismatch?.setId)
        assertEquals("moodle.school.test", mismatch?.host)
        // Built up to its anchor, as a handshake's chain is.
        assertEquals(root.certificate, mismatch?.chain?.last())
        assertEquals(listOf(mismatch), mismatches.toList())
    }

    @Test
    fun `an open connection's chain is built up to its anchor, so a root pin matches with the root left out`() {
        val school = PinSet("school", listOf(PinDomain("school.test", true)), setOf(root.certificate.spki), now.plusSeconds(3600))
        val trust = PinnedTrustManager(systemTrust, AnchoringValidator(systemTrust), { policy(school) }, mismatches::add, { now })

        trust.checkExchange("moodle.school.test", listOf(leaf.certificate, intermediate.certificate), Any())

        assertEquals(emptyList<PinMismatch>(), mismatches.toList())
    }

    private companion object {
        const val HOST = "localhost"

        fun leafSignedBy(issuer: HeldCertificate): HeldCertificate =
            HeldCertificate.Builder().addSubjectAlternativeName(HOST).signedBy(issuer).build()
    }
}
