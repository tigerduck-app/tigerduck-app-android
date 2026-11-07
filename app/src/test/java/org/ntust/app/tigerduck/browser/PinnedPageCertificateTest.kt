package org.ntust.app.tigerduck.browser

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.tls.ChainValidator
import org.ntust.app.tigerduck.shared.tls.PinDomain
import org.ntust.app.tigerduck.shared.tls.PinPolicy
import org.ntust.app.tigerduck.shared.tls.PinSet
import org.ntust.app.tigerduck.shared.tls.PinnedTls
import org.ntust.app.tigerduck.shared.tls.PinnedTrustManager
import org.ntust.app.tigerduck.shared.tls.spki
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Instant
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.concurrent.thread

/**
 * The pinned request whose leaf a page's own is matched with, against a TLS server on the loopback
 * with hand-made certificates: what it sends, and when it gives no leaf.
 */
class PinnedPageCertificateTest {

    private val root = HeldCertificate.Builder().certificateAuthority(1).commonName("Test Root").build()
    private val intermediate = HeldCertificate.Builder().certificateAuthority(0).commonName("Test CA").signedBy(root).build()
    private val otherCa = HeldCertificate.Builder().certificateAuthority(0).commonName("Other CA").signedBy(root).build()
    private val leaf = HeldCertificate.Builder().addSubjectAlternativeName("localhost").signedBy(intermediate).build()

    private val trust = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build().trustManager as X509ExtendedTrustManager
    private val now = Instant.parse("2026-10-25T00:00:00Z")
    private val servers = mutableListOf<ServerSocket>()

    /** Each request's head as the server read it: its request line and headers. */
    private val received = Collections.synchronizedList(mutableListOf<List<String>>())

    @After
    fun close() = servers.forEach { it.close() }

    private fun tls(pins: Set<String>) = PinnedTls(
        PinnedTrustManager(
            platform = trust,
            validator = ChainValidator { chain, authType, _ ->
                trust.checkServerTrusted(chain, authType)
                chain.toList() + root.certificate
            },
            policy = {
                val set = PinSet("test", listOf(PinDomain("localhost", includeSubdomains = false)), pins, now.plusSeconds(86_400))
                PinPolicy(builtIn = listOf(set), appVersion = 30)
            },
            onMismatch = {},
            now = { now },
        ),
    )

    private val passThrough = Interceptor { it.proceed(it.request()) }

    private fun probe(pins: Set<String> = setOf(intermediate.certificate.spki)) = PinnedPageCertificate(
        PinnedPageCertificate.client(tls(pins), passThrough).newBuilder().dns { listOf(InetAddress.getLoopbackAddress()) }.build(),
    )

    /** Answers every request with a redirect elsewhere and a cookie, and closes. */
    private fun serve(served: HeldCertificate = leaf): Int {
        val server = HandshakeCertificates.Builder().heldCertificate(served, intermediate.certificate).build()
            .sslContext().serverSocketFactory.createServerSocket(0, 8, InetAddress.getLoopbackAddress())
        servers += server
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        received += generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }.toList()
                        it.getOutputStream().write(
                            ("HTTP/1.1 302 Found\r\nLocation: https://localhost:${server.localPort}/elsewhere\r\n" +
                                "Set-Cookie: session=made-up; Path=/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(),
                        )
                    }
                }
            }
        }
        return server.localPort
    }

    @Test
    fun `the client is the app's pinned one, with no cookies, no credentials and no redirect followed`() {
        val tls = tls(emptySet())
        val client = PinnedPageCertificate.client(tls, passThrough)

        assertSame(tls.socketFactory, client.sslSocketFactory)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertSame(Authenticator.NONE, client.authenticator)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertTrue("the demo's interceptor", passThrough in client.interceptors)
        assertEquals(TimeUnit.SECONDS.toMillis(5), client.callTimeoutMillis.toLong())
        assertTrue(client.connectTimeoutMillis <= 5_000 && client.readTimeoutMillis <= 5_000)
    }

    @Test
    fun `one HEAD of the page itself, carrying nothing, gives the leaf it was served`() = runBlocking {
        val port = serve()
        val probe = probe()

        val got = probe.leaf("https://localhost:$port/std/index.php/user/login")
        assertArrayEquals(leaf.certificate.encoded, got?.encoded)
        probe.leaf("https://localhost:$port/std/index.php/user/login")

        assertEquals("the redirect is not followed", 2, received.size)
        received.forEach { head ->
            assertEquals("HEAD /std/index.php/user/login HTTP/1.1", head.first())
            val names = head.drop(1).map { it.substringBefore(':').lowercase() }
            assertFalse("no cookie, though one was set", "cookie" in names)
            assertFalse("no credentials", "authorization" in names)
        }
    }

    @Test
    fun `a server that does not meet the pins gives no leaf`() = runBlocking {
        val port = serve()
        assertNull(probe(pins = setOf(otherCa.certificate.spki)).leaf("https://localhost:$port/"))
        assertTrue("no request was sent", received.all { it.isEmpty() })
    }

    @Test
    fun `a certificate for another host gives no leaf`() = runBlocking {
        val port = serve(HeldCertificate.Builder().addSubjectAlternativeName("elsewhere.test").signedBy(intermediate).build())
        assertNull(probe().leaf("https://localhost:$port/"))
        assertTrue("no request was sent", received.all { it.isEmpty() })
    }

    @Test
    fun `no server gives no leaf`() = runBlocking {
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).run { close(); localPort }
        assertNull(probe().leaf("https://localhost:$closed/"))
    }
}
