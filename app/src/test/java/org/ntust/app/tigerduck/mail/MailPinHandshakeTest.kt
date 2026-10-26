package org.ntust.app.tigerduck.mail

import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.mail.compose.MessageBuilder
import org.ntust.app.tigerduck.mail.compose.OutgoingMail
import org.ntust.app.tigerduck.mail.imap.AngusMailSessionFactory
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.smtp.AngusMailTransport
import org.ntust.app.tigerduck.network.tls.PinTestChain
import org.ntust.app.tigerduck.shared.tls.ChainValidator
import org.ntust.app.tigerduck.shared.tls.PinDomain
import org.ntust.app.tigerduck.shared.tls.PinMismatch
import org.ntust.app.tigerduck.shared.tls.PinPolicy
import org.ntust.app.tigerduck.shared.tls.PinSet
import org.ntust.app.tigerduck.shared.tls.PinnedTls
import org.ntust.app.tigerduck.shared.tls.PinnedTrustManager
import org.ntust.app.tigerduck.shared.tls.pinMismatch
import org.ntust.app.tigerduck.shared.tls.spki
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.concurrent.thread

/**
 * School Mail's TLS meets the pins in the handshake itself, not only in Angus's properties.
 * [MailDevServerTest] shows the pinned factory is put there; that alone never proves a pin is
 * checked. [PinnedTrustManager] applies pins in the `checkServerTrusted` overloads that take the
 * socket or the engine, which read the host from the handshake session; the two-argument one has
 * no host and gives a pinned host the platform's check alone. Which of them runs, and with what
 * host, is up to how Angus makes its sockets and layers TLS over them.
 *
 * So Angus connects here, through [AngusMailSessionFactory] and [AngusMailTransport] given
 * [PinnedTls]'s socket factory as MailModule gives it to them (AllHttpsClientsPinnedTest holds
 * MailModule to that), to hand-made servers on the loopback serving a chain under a root the test
 * trusts. A pin set for localhost naming another CA fails the handshake on PinMismatchException
 * before the login goes out, over implicit TLS (IMAPS, SMTPS) and over SMTP's STARTTLS upgrade.
 * The same connections with the server's own root pinned are the controls: they get through to
 * the login, so each failure is the pin's and nothing else's.
 *
 * The TLS here is the JVM's, not the phone's Conscrypt: what this holds down is Angus's side, the
 * sockets it makes through the factory and the host it connects them to.
 */
class MailPinHandshakeTest {

    /** The server's chain: a leaf for localhost under an intermediate under a root the test trusts. */
    private val chain = PinTestChain(host = HOST, rootName = "Test Mail Root")

    /** A CA that issued nothing the server serves, which the mismatching pin set names. */
    private val otherRoot = HeldCertificate.Builder().certificateAuthority(1).commonName("Some Other Root").build()

    /** The platform's trust as the test has it: the server's root and nothing else. */
    private val trust = HandshakeCertificates.Builder().addTrustedCertificate(chain.root.certificate).build()
        .trustManager as X509ExtendedTrustManager

    /**
     * The platform's check, then the chain built up to the root the server leaves out, as Android's
     * X509TrustManagerExtensions returns it (:shared's AnchoringValidator, which :app's tests can't
     * see), so a root pin matches as it would on a phone.
     */
    private val validator = ChainValidator { served, authType, _ ->
        trust.checkServerTrusted(served, authType)
        served.toList() + chain.root.certificate
    }

    /** What the trust manager's onMismatch heard: only the overloads that know the host tell it. */
    private val mismatches = CopyOnWriteArrayList<PinMismatch>()

    /** Serves the leaf and the intermediate, as a real server does, leaving out the root. */
    private val server = LoopbackMailServer(
        HandshakeCertificates.Builder().heldCertificate(chain.leaf, chain.intermediate.certificate).build().sslContext(),
        InetAddress.getByName(HOST),
    )

    private val credentials = MailCredentials(studentId = "B00015999", password = "pw-not-real")

    @After
    fun close() = server.close()

    /** PinnedTls's socket factory, as School Mail is given it, with one pin set for [HOST] pinning [pin]. */
    private fun pinnedFactory(pin: X509Certificate): SSLSocketFactory {
        val set = PinSet(SET_ID, listOf(PinDomain(HOST, false)), setOf(pin.spki), Instant.now().plus(Duration.ofDays(365)))
        val policy = PinPolicy(builtIn = listOf(set), appVersion = 1)
        return PinnedTls(PinnedTrustManager(trust, validator, { policy }, mismatches::add)).socketFactory
    }

    private fun config(imap: MailEndpoint = UNUSED, smtp: MailEndpoint = UNUSED) =
        MailServerConfig(domain = MailServerConfig.DOMAIN, imap = imap, smtp = smtp)

    /** Opens an IMAP session and closes it again, returning what that threw. */
    private fun openImap(tls: SSLSocketFactory): Throwable? = runCatching {
        val endpoint = MailEndpoint(HOST, server.port, MailTransportSecurity.IMPLICIT_TLS)
        AngusMailSessionFactory(MailServerConfigSource { config(imap = endpoint) }, tls).open(credentials).close()
    }.exceptionOrNull()

    /** Sends one mail, returning what that threw. */
    private fun send(tls: SSLSocketFactory, security: MailTransportSecurity): Throwable? = runCatching {
        val endpoint = MailEndpoint(HOST, server.port, security)
        val mail = OutgoingMail(
            from = MailAddress(null, credentials.address),
            to = listOf(MailAddress(null, "someone@example.com")),
            cc = emptyList(), bcc = emptyList(), subject = "hi", body = "body",
        )
        AngusMailTransport(MailServerConfigSource { config(smtp = endpoint) }, tls).send(credentials, MessageBuilder().build(mail))
    }.exceptionOrNull()

    /** [error] is the pin's: a certificate failure thrown in the handshake, carrying the mismatch onMismatch heard. */
    private fun assertFailedOnThePin(error: Throwable?) {
        assertTrue("a certificate failure, not $error", error is MailError.Certificate)
        val mismatch = error!!.pinMismatch()
        assertNotNull("no PinMismatchException under ${error.stackTraceToString()}", mismatch)
        assertEquals(HOST, mismatch!!.host)
        assertEquals(SET_ID, mismatch.setId)
        assertTrue(
            "thrown in the handshake: ${error.stackTraceToString()}",
            generateSequence(error) { it.cause }.take(16).any { it is SSLHandshakeException },
        )
        assertSame("the overload that read the host from the handshake threw it", mismatch, mismatches.single())
        assertFalse("the server's side of the handshake never finished", server.handshakeDone)
    }

    // --- implicit TLS: IMAPS, as the school's 993 -----------------------------------------

    @Test
    fun `IMAPS to a server whose chain misses the pins fails in the handshake, before LOGIN`() {
        server.serve { imaps() }
        val error = openImap(pinnedFactory(otherRoot.certificate))
        server.awaitEnd()

        assertFailedOnThePin(error)
        assertEquals("nothing reached the server, LOGIN least of all", emptyList<String>(), server.received.toList())
    }

    @Test
    fun `IMAPS with the server's own root pinned gets through the handshake to LOGIN`() {
        server.serve { imaps() }
        val error = openImap(pinnedFactory(chain.root.certificate))
        server.awaitEnd()

        assertNull("connected: ${error?.stackTraceToString()}", error)
        assertTrue(server.handshakeDone)
        assertTrue("LOGIN in ${server.received}", server.received.any { it.imapVerb() == "LOGIN" })
        assertEquals(emptyList<PinMismatch>(), mismatches.toList())
    }

    // --- implicit TLS: SMTPS, as the school's 465 -----------------------------------------

    @Test
    fun `SMTPS to a server whose chain misses the pins fails in the handshake, before AUTH`() {
        server.serve { smtp(implicitTls = true) }
        val error = send(pinnedFactory(otherRoot.certificate), MailTransportSecurity.IMPLICIT_TLS)
        server.awaitEnd()

        assertFailedOnThePin(error)
        assertEquals("nothing reached the server, AUTH least of all", emptyList<String>(), server.received.toList())
    }

    @Test
    fun `SMTPS with the server's own root pinned gets through the handshake and sends`() {
        server.serve { smtp(implicitTls = true) }
        val error = send(pinnedFactory(chain.root.certificate), MailTransportSecurity.IMPLICIT_TLS)
        server.awaitEnd()

        assertNull("sent: ${error?.stackTraceToString()}", error)
        assertTrue(server.handshakeDone)
        val verbs = server.received.map { it.smtpVerb() }
        assertTrue("AUTH and DATA in $verbs", "AUTH" in verbs && "DATA" in verbs)
        assertEquals(emptyList<PinMismatch>(), mismatches.toList())
    }

    // --- STARTTLS: SMTP upgraded, as a developer's override server may be ---------------------

    @Test
    fun `STARTTLS to a server whose chain misses the pins fails in the upgrade, before AUTH`() {
        server.serve { smtp(implicitTls = false) }
        val error = send(pinnedFactory(otherRoot.certificate), MailTransportSecurity.STARTTLS)
        server.awaitEnd()

        assertFailedOnThePin(error)
        assertEquals(
            "only the plaintext greeting and the upgrade reached the server",
            listOf("EHLO", "STARTTLS"),
            server.received.map { it.smtpVerb() },
        )
    }

    @Test
    fun `STARTTLS with the server's own root pinned upgrades and sends`() {
        server.serve { smtp(implicitTls = false) }
        val error = send(pinnedFactory(chain.root.certificate), MailTransportSecurity.STARTTLS)
        server.awaitEnd()

        assertNull("sent: ${error?.stackTraceToString()}", error)
        assertTrue(server.handshakeDone)
        val verbs = server.received.map { it.smtpVerb() }
        assertTrue("AUTH after STARTTLS in $verbs", verbs.indexOf("STARTTLS") in 0 until verbs.indexOf("AUTH"))
        assertTrue("DATA in $verbs", "DATA" in verbs)
        assertEquals(emptyList<PinMismatch>(), mismatches.toList())
    }

    private companion object {
        const val HOST = "localhost"
        const val SET_ID = "mail-test"

        /** The leg a test doesn't use: nothing listens there and nothing connects to it. */
        val UNUSED = MailEndpoint(HOST, 1, MailTransportSecurity.NONE)
    }
}

/**
 * One mail conversation on the loopback, made by hand so the test says when TLS starts: at once,
 * for implicit TLS, or at a STARTTLS. Records every line the client sent and whether the server's
 * side of a TLS handshake finished, which a client refusing the certificate never lets it do.
 */
private class LoopbackMailServer(private val tls: SSLContext, address: InetAddress) : AutoCloseable {
    private val listening = ServerSocket(0, 1, address)
    private var serving: Thread? = null

    val port: Int get() = listening.localPort

    /** Every line the client sent, before TLS and over it, in order. */
    val received: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var handshakeDone = false
        private set

    fun serve(script: Conversation.() -> Unit) {
        serving = thread(isDaemon = true) {
            // A handshake the client refuses throws here, which ends the conversation.
            runCatching { listening.accept().use { Conversation(it).script() } }
        }
    }

    /** Waits for the conversation to end: the client hung up, or it refused the handshake. */
    fun awaitEnd() {
        val conversation = checkNotNull(serving) { "nothing served" }
        conversation.join(10_000)
        check(!conversation.isAlive) { "the conversation is still going" }
    }

    override fun close() = listening.close()

    inner class Conversation(private var socket: Socket) {
        init {
            socket.soTimeout = 10_000
        }

        /** Whether TLS is up, from the start or since a STARTTLS. */
        var secure = false
            private set

        fun say(vararg lines: String) {
            val out = socket.getOutputStream()
            lines.forEach { out.write("$it\r\n".toByteArray(Charsets.US_ASCII)) }
            out.flush()
        }

        /** The client's next line, read a byte at a time so nothing past a STARTTLS is read ahead. */
        fun read(): String? {
            val input = socket.getInputStream()
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) break
                line.write(b)
            }
            return line.toString(Charsets.US_ASCII.name()).removeSuffix("\r").also { received += it }
        }

        /** The server's side of a TLS handshake, over the connection as it stands. */
        fun startTls() {
            val layered = tls.socketFactory.createSocket(socket, socket.inetAddress.hostAddress, socket.port, true) as SSLSocket
            layered.useClientMode = false
            layered.startHandshake()
            socket = layered
            secure = true
            handshakeDone = true
        }
    }
}

/** IMAP over implicit TLS, answering every command OK: as much as Angus's LOGIN and LOGOUT need. */
private fun LoopbackMailServer.Conversation.imaps() {
    startTls()
    say("* OK [CAPABILITY IMAP4rev1] test.example ready")
    while (true) {
        val line = read() ?: return
        val tag = line.substringBefore(' ')
        when (line.imapVerb()) {
            "CAPABILITY" -> say("* CAPABILITY IMAP4rev1", "$tag OK done")
            "LOGOUT" -> return say("* BYE test.example", "$tag OK done")
            else -> say("$tag OK done")
        }
    }
}

/** SMTP, over TLS from the start or from a STARTTLS, taking one mail: as much as Angus's send needs. */
private fun LoopbackMailServer.Conversation.smtp(implicitTls: Boolean) {
    if (implicitTls) startTls()
    say("220 test.example ESMTP")
    while (true) {
        val line = read() ?: return
        when (line.smtpVerb()) {
            // AUTH only once the connection is secure, as a server offering STARTTLS does.
            "EHLO" -> if (secure) say("250-test.example", "250 AUTH LOGIN PLAIN") else say("250-test.example", "250 STARTTLS")
            "STARTTLS" -> {
                say("220 2.0.0 ready to start TLS")
                startTls()
            }
            "AUTH" -> {
                if (line.uppercase().startsWith("AUTH LOGIN")) {
                    say("334 VXNlcm5hbWU6")
                    read()
                    say("334 UGFzc3dvcmQ6")
                    read()
                }
                say("235 2.7.0 accepted")
            }
            "DATA" -> {
                say("354 go ahead")
                do {
                    val data = read() ?: return
                } while (data != ".")
                say("250 2.0.0 queued")
            }
            "QUIT" -> return say("221 bye")
            else -> say("250 ok")
        }
    }
}

/** An IMAP command's name, after its tag. */
private fun String.imapVerb(): String? = split(' ').getOrNull(1)?.uppercase()

/** An SMTP command's name. */
private fun String.smtpVerb(): String = substringBefore(' ').uppercase()
