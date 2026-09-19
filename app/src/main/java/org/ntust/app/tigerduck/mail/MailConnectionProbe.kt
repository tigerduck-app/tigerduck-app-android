package org.ntust.app.tigerduck.mail

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import jakarta.mail.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.mail.store.MailCredentialStore
import org.ntust.app.tigerduck.mail.warning.MailWarnings
import java.io.BufferedReader
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Properties
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Developer -> Email's "Test connection": what actually happens when the overridden mail
 * server will not connect.
 *
 * The answer is a block of text, not a structured result, on purpose. Everything that
 * spells out a stage, a verdict or an exception lives in the implementation below, which a
 * release build never constructs (`MailModule.connectionProbe`), so R8 drops the class and
 * every one of those strings with it -- the "absent, not hidden" bar the override store
 * itself had to meet.
 */
interface MailConnectionProbe {
    /**
     * Tests [draft] -- what is on screen, not what is applied -- and returns the report to
     * show. Never throws for a connection failure: a failure *is* the answer. Cancelling
     * the calling coroutine closes whatever socket is in flight.
     */
    suspend fun test(draft: MailDevServerSettings): String

    /** What a release build is given, so no probe, and no text of one, is built into the APK. */
    object Unavailable : MailConnectionProbe {
        override suspend fun test(draft: MailDevServerSettings): String = "Debug builds only."
    }
}

/**
 * Connects by hand, one stage at a time, and reports each stage separately for IMAP and for
 * SMTP: did the name resolve, did TCP connect, did TLS hand-shake, did LOGIN/AUTH pass.
 *
 * Deliberately does **not** go through [MailErrors.classify]. That layer exists to turn any
 * failure into one of five sentences a student can act on, which is exactly why it cannot
 * answer "why does this server not connect" -- a refused port, a filtered port and a dead
 * DNS record all arrive as the same [MailError.Network]. Here the raw exception class, its
 * message and its whole cause chain are the product.
 *
 * The AUTH stage goes through Angus with [MailProperties], so it is the app's own
 * connection and not an approximation of it; the stages before it are hand-rolled because
 * Angus reports "connect failed" for all of them at once.
 */
class SocketMailConnectionProbe(
    private val credentials: MailCredentialStore,
    private val site: MailSite,
) : MailConnectionProbe {

    override suspend fun test(draft: MailDevServerSettings): String {
        if (!draft.enabled) return REFUSED_OFF
        if (!draft.isComplete) return REFUSED_INCOMPLETE
        val config = draft.toConfig()
        if (MailWarnings.isSchoolDomain(config.imap.host) || MailWarnings.isSchoolDomain(config.smtp.host)) {
            return REFUSED_SCHOOL
        }
        // Read once, so both legs are judged against the same applied configuration even if
        // something else changes it mid-run.
        val inForce = site.config()
        return withContext(Dispatchers.IO) {
            val legs = Leg.entries.map { leg -> probe(leg, config, inForce) }
            legs.joinToString("\n\n") { it.joinToString("\n") }
        }
    }

    // --- one endpoint, stage by stage ---------------------------------------------------

    private suspend fun probe(leg: Leg, config: MailServerConfig, inForce: MailServerConfig): List<String> {
        val endpoint = leg.endpoint(config)
        val steps = mutableListOf<Step>()

        val addresses = try {
            InetAddress.getAllByName(endpoint.host)
        } catch (e: Exception) {
            steps += Step(DNS, MailProbeVerdict.DNS_FAILED, probeRawChain(e))
            return render(leg, endpoint, steps)
        }
        steps += Step(DNS, MailProbeVerdict.OK, listOf(addresses.joinToString(", ") { it.hostAddress ?: it.toString() }))

        steps += wire(leg, endpoint)
        if (steps.any { it.verdict.failed }) return render(leg, endpoint, steps)

        steps += auth(leg, config, inForce)
        return render(leg, endpoint, steps)
    }

    /** TCP, then TLS, on one socket -- the TLS stage needs the connection the TCP stage opened. */
    private suspend fun wire(leg: Leg, endpoint: MailEndpoint): List<Step> {
        val steps = mutableListOf<Step>()
        val socket = Socket()
        try {
            return closingOnCancel(socket) {
                val startedAt = System.nanoTime()
                try {
                    socket.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS)
                } catch (e: Exception) {
                    steps += Step(TCP, probeTcpVerdict(e), probeRawChain(e))
                    return@closingOnCancel steps
                }
                steps += Step(TCP, MailProbeVerdict.OK, listOf("connected in ${millisSince(startedAt)} ms"))
                socket.soTimeout = READ_TIMEOUT_MS
                steps += try {
                    handshake(leg, endpoint, socket)
                } catch (e: Exception) {
                    Step(TLS, MailProbeVerdict.TLS_FAILED, probeRawChain(e))
                }
                steps
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * The greeting is read in every scheme, not only in the STARTTLS dialogue that needs
     * it: a port that accepts TCP and then says nothing recognizable is a different problem
     * from one that refuses, and without this the difference is invisible whenever there is
     * no password to take the AUTH stage any further.
     */
    private fun handshake(leg: Leg, endpoint: MailEndpoint, socket: Socket): Step {
        val detail = mutableListOf<String>()
        return when (endpoint.security) {
            MailTransportSecurity.NONE -> {
                detail += "the draft asks for no TLS, so none was attempted"
                detail += "greeting: ${greetingOf(socket, leg)}"
                Step(TLS, MailProbeVerdict.SKIPPED, detail)
            }
            MailTransportSecurity.IMPLICIT_TLS -> {
                val tls = upgrade(socket, endpoint)
                detail += describe(tls)
                detail += "greeting: ${greetingOf(tls, leg)}"
                Step(TLS, MailProbeVerdict.OK, detail)
            }
            MailTransportSecurity.STARTTLS -> {
                val wire = Wire(socket)
                detail += "greeting: ${wire.greeting(leg)}"
                wire.startTls(leg, socket)
                val tls = upgrade(socket, endpoint)
                detail += describe(tls)
                Step(TLS, MailProbeVerdict.OK, detail)
            }
        }
    }

    /**
     * Never fatal where TLS is already up or was never asked for: a server that hands back
     * nothing recognizable is worth saying so about, but it is not a failed handshake, and
     * labelling it one would point the reader at the wrong stage. In the STARTTLS dialogue
     * the greeting is read by [Wire.startTls]'s own path instead, where it *is* part of
     * getting TLS up and a failure there belongs to this stage.
     */
    private fun greetingOf(socket: Socket, leg: Leg): String =
        runCatching { Wire(socket).greeting(leg) }.getOrElse { e -> probeRawChain(e).joinToString("; ") }

    /**
     * `endpointIdentificationAlgorithm = "HTTPS"` is the raw-socket spelling of
     * [MailProperties]' `ssl.checkserveridentity`. Without it this stage would pass on a
     * certificate the AUTH stage below then rejects, which is the one way a staged report
     * could point at the wrong stage.
     */
    private fun upgrade(socket: Socket, endpoint: MailEndpoint): SSLSocket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val tls = factory.createSocket(socket, endpoint.host, endpoint.port, true) as SSLSocket
        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        tls.startHandshake()
        return tls
    }

    private fun describe(tls: SSLSocket): List<String> {
        val session = tls.session
        val lines = mutableListOf("${session.protocol} ${session.cipherSuite}")
        val certificate = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
        if (certificate != null) {
            lines += "cert ${certificate.subjectX500Principal.name}"
            lines += "issued by ${certificate.issuerX500Principal.name}, expires ${certificate.notAfter.toInstant()}"
        }
        return lines
    }

    // --- the authentication stage -------------------------------------------------------

    /**
     * The stored password belongs to whatever server it was typed against, so it is offered
     * only to that same host. Otherwise testing a third-party draft while signed in to the
     * school would hand the school password to someone else's server -- the mirror image of
     * the school host this whole probe refuses, and just as much not worth a diagnostic.
     */
    private suspend fun auth(leg: Leg, config: MailServerConfig, inForce: MailServerConfig): Step {
        val id = credentials.mailStudentId
        val password = credentials.mailPassword
        if (id.isNullOrBlank() || password.isNullOrEmpty()) {
            return Step(AUTH, MailProbeVerdict.SKIPPED, listOf("not attempted: no password is stored to try"))
        }
        val storedFor = leg.endpoint(inForce).host
        val draftHost = leg.endpoint(config).host
        if (!storedFor.equals(draftHost, ignoreCase = true)) {
            return Step(
                AUTH,
                MailProbeVerdict.SKIPPED,
                listOf(
                    "not attempted: the stored password was typed for $storedFor, not $draftHost",
                    "save the draft and sign in there if you want LOGIN tested too",
                ),
            )
        }
        val creds = MailCredentials(id, password, config.domain)
        val startedAt = System.nanoTime()
        return try {
            connect(leg, config, creds)
            Step(
                AUTH,
                MailProbeVerdict.OK,
                listOf("accepted \"${creds.loginName}\" in ${millisSince(startedAt)} ms"),
            )
        } catch (e: AuthenticationFailedException) {
            Step(AUTH, MailProbeVerdict.AUTH_FAILED, listOf("as \"${creds.loginName}\"") + probeRawChain(e))
        } catch (e: Exception) {
            Step(AUTH, MailProbeVerdict.FAILED, listOf("as \"${creds.loginName}\"") + probeRawChain(e))
        }
    }

    /** Angus with the app's own properties: this stage is the app's connection, not a copy of it. */
    private suspend fun connect(leg: Leg, config: MailServerConfig, creds: MailCredentials) {
        val protocol = when (leg) {
            Leg.IMAP -> MailProperties.imapProtocol(config)
            Leg.SMTP -> MailProperties.smtpProtocol(config)
        }
        val properties = when (leg) {
            Leg.IMAP -> MailProperties.imap(config)
            Leg.SMTP -> MailProperties.smtp(config)
        }.impatient(protocol)
        val session = Session.getInstance(properties)
        val service = when (leg) {
            Leg.IMAP -> session.getStore(protocol)
            Leg.SMTP -> session.getTransport(protocol)
        }
        val endpoint = leg.endpoint(config)
        try {
            closingOnCancel(Closeable { runCatching { service.close() } }) {
                service.connect(endpoint.host, endpoint.port, creds.loginName, creds.password)
            }
        } finally {
            runCatching { service.close() }
        }
    }

    /** The app waits up to a minute; a person watching a diagnostic should not have to. */
    private fun Properties.impatient(protocol: String) = apply {
        put("mail.$protocol.connectiontimeout", CONNECT_TIMEOUT_MS.toString())
        put("mail.$protocol.timeout", READ_TIMEOUT_MS.toString())
        put("mail.$protocol.writetimeout", READ_TIMEOUT_MS.toString())
    }

    // --- rendering ----------------------------------------------------------------------

    private fun render(leg: Leg, endpoint: MailEndpoint, steps: List<Step>): List<String> {
        val lines = mutableListOf("${leg.label} ${endpoint.host}:${endpoint.port} ${endpoint.security.spelling}")
        steps.forEach { step ->
            val head = "  ${step.name.padEnd(4)}  ${step.verdict.label.padEnd(12)}  "
            val indent = " ".repeat(head.length)
            if (step.detail.isEmpty()) {
                lines += head.trimEnd()
            } else {
                step.detail.forEachIndexed { index, line -> lines += (if (index == 0) head else indent) + line }
            }
        }
        return lines
    }

    private fun millisSince(startedAt: Long) = (System.nanoTime() - startedAt) / 1_000_000

    /**
     * Closes [closeable] if the caller cancels. A blocking socket read does not notice a
     * cancelled coroutine; closing the socket under it is what turns "Cancel" into
     * something that happens now rather than when the timeout runs out.
     */
    private suspend fun <T> closingOnCancel(closeable: Closeable, block: () -> T): T {
        val handle = currentCoroutineContext().job.invokeOnCompletion { runCatching { closeable.close() } }
        return try {
            block()
        } finally {
            handle.dispose()
        }
    }

    private companion object {
        const val DNS = "DNS"
        const val TCP = "TCP"
        const val TLS = "TLS"
        const val AUTH = "AUTH"

        const val REFUSED_OFF =
            "The override switch is off, so there is nothing to test. Turn it on and test again — " +
                "you do not have to save first."
        const val REFUSED_INCOMPLETE =
            "Fill in the address domain and both hosts, with ports between 1 and 65535, before testing."
        const val REFUSED_SCHOOL =
            "Refusing to test ${MailServerConfig.DOMAIN}. This button is for third-party servers: aiming it " +
                "at the school would send a real mail password there for the sake of a diagnostic, and the " +
                "school path is not what this page debugs."
    }
}

/** One stage of one endpoint's probe. */
private class Step(val name: String, val verdict: MailProbeVerdict, val detail: List<String>)

private enum class Leg(val label: String) {
    IMAP("IMAP"),
    SMTP("SMTP"),
    ;

    fun endpoint(config: MailServerConfig): MailEndpoint = when (this) {
        IMAP -> config.imap
        SMTP -> config.smtp
    }
}

private val MailTransportSecurity.spelling: String
    get() = when (this) {
        MailTransportSecurity.IMPLICIT_TLS -> "implicit TLS"
        MailTransportSecurity.STARTTLS -> "STARTTLS"
        MailTransportSecurity.NONE -> "no TLS"
    }

/**
 * How a stage came out. The five failures are the whole point of the button: they are what
 * [MailError] collapses into one another, and telling them apart is the diagnosis.
 */
internal enum class MailProbeVerdict(val label: String, val failed: Boolean = false) {
    OK("ok"),
    SKIPPED("skipped"),
    DNS_FAILED("DNS failed", failed = true),
    TCP_REFUSED("TCP refused", failed = true),
    TCP_TIMED_OUT("TCP timed out", failed = true),
    TLS_FAILED("TLS failed", failed = true),
    AUTH_FAILED("AUTH failed", failed = true),

    /** Something the stages above do not name -- the raw exception is printed regardless. */
    FAILED("failed", failed = true),
}

/**
 * Refused or timed out, told apart by what the platform threw.
 *
 * A connect timeout arrives two ways: [SocketTimeoutException] when our own connect timeout
 * expires first, and a [ConnectException] carrying `ETIMEDOUT` when the kernel gives up
 * first. Both mean the same thing to whoever is reading -- the packets went nowhere --
 * whereas a refusal means something answered. The message is matched, not just the class,
 * because Android spells both as `ConnectException`.
 */
internal fun probeTcpVerdict(t: Throwable): MailProbeVerdict {
    if (t is SocketTimeoutException) return MailProbeVerdict.TCP_TIMED_OUT
    val text = generateSequence(t) { it.cause }.take(4).mapNotNull { it.message }.joinToString(" ").lowercase()
    return when {
        "etimedout" in text || "timed out" in text -> MailProbeVerdict.TCP_TIMED_OUT
        "econnrefused" in text || "refused" in text -> MailProbeVerdict.TCP_REFUSED
        else -> MailProbeVerdict.FAILED
    }
}

/**
 * The exception exactly as it came off the wire, outermost first, down the cause chain.
 *
 * Angus hangs the interesting exception off `MessagingException.nextException`, which is
 * what "closer to the wire" means here: `jakarta.mail.MessagingException: Connect failed`
 * on its own says nothing, while the `java.net.ConnectException` under it says everything.
 */
internal fun probeRawChain(t: Throwable): List<String> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val lines = mutableListOf<String>()
    var current: Throwable? = t
    while (current != null && seen.add(current) && lines.size < 6) {
        lines += (if (lines.isEmpty()) "" else "caused by ") + "${current.javaClass.name}: ${current.flattened()}"
        current = (current as? MessagingException)?.nextException ?: current.cause
    }
    return lines
}

/**
 * The message on one line. `MessagingException.getMessage` splices its nested exception in
 * over three lines, which would land unindented in the middle of a stage's block and read
 * as if it belonged to the next one. Nothing is dropped -- the runs of whitespace are
 * collapsed, and the nested exception gets its own `caused by` line anyway.
 */
private fun Throwable.flattened(): String? = message?.replace(WHITESPACE, " ")?.trim()

private val WHITESPACE = Regex("\\s+")

/**
 * Just enough IMAP and SMTP to read a greeting and ask for STARTTLS. Angus cannot be used
 * for this: it does the whole connect in one call, so a STARTTLS that the server refuses
 * and a password it rejects come back indistinguishable.
 */
private class Wire(socket: Socket) {
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
    private val output = socket.getOutputStream()

    fun line(): String =
        reader.readLine() ?: throw EOFException("the server closed the connection without replying")

    fun send(line: String) {
        output.write("$line\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.flush()
    }

    fun greeting(leg: Leg): String = when (leg) {
        Leg.IMAP -> line()
        Leg.SMTP -> smtpReply().first()
    }.trim()

    /** SMTP continuation lines are `250-`; the last one is `250 `. */
    private fun smtpReply(): List<String> {
        val lines = mutableListOf(line())
        while (lines.size < MAX_LINES && lines.last().length >= 4 && lines.last()[3] == '-') lines += line()
        return lines
    }

    fun startTls(leg: Leg, socket: Socket) {
        when (leg) {
            Leg.IMAP -> {
                send("$TAG STARTTLS")
                var reply = line()
                var read = 1
                while (!reply.startsWith("$TAG ") && read < MAX_LINES) {
                    reply = line()
                    read++
                }
                if (!reply.startsWith("$TAG OK", ignoreCase = true)) {
                    throw IOException("the server refused STARTTLS: ${reply.trim()}")
                }
            }
            Leg.SMTP -> {
                // RFC 5321 lets a client with no resolvable name identify itself by address literal.
                send("EHLO [${socket.localAddress.hostAddress}]")
                val ehlo = smtpReply()
                if (!ehlo.last().startsWith("250")) throw IOException("the server refused EHLO: ${ehlo.last().trim()}")
                send("STARTTLS")
                val reply = smtpReply()
                if (!reply.last().startsWith("220")) {
                    throw IOException("the server refused STARTTLS: ${reply.last().trim()}")
                }
            }
        }
    }

    private companion object {
        const val TAG = "T1"
        const val MAX_LINES = 64
    }
}

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 10_000
