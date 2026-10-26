package org.ntust.app.tigerduck.shared.tls

import android.net.http.X509TrustManagerExtensions
import okhttp3.ConnectionPool
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import java.util.WeakHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * A pinned host's chain passed system trust but shares no SPKI with its set's effective pins
 * (TLS pins spec, A4): what a prompt shows and what Continue stores.
 */
class PinMismatch(
    val setId: String,
    val host: String,
    /** The set's domains, which a Continue covers. */
    val domains: List<PinDomain>,
    /** The chain the platform validated, leaf first, up to its trust anchor. */
    val chain: List<X509Certificate>,
    /** Every SPKI of [chain]. */
    val spkis: Set<String>,
) {
    /** The SPKI of the chain's trust anchor, its last certificate, which D18 checks. */
    val anchorSpki: String get() = chain.last().spki

    /**
     * What a Continue stores (D17): the leaf's and the intermediates' SPKIs, never the
     * anchor's. A chain with no intermediate stores its leaf alone.
     */
    val acceptableSpkis: Set<String>
        get() = (if (chain.size > 1) chain.dropLast(1) else chain).map { it.spki }.toSet()
}

/** Thrown in the handshake for a [PinMismatch], before a byte of the request goes out. */
class PinMismatchException(val mismatch: PinMismatch) :
    CertificateException("certificate pins don't match for ${mismatch.host}")

/** The [PinMismatch] among this throwable's causes, as a failed call wraps it, or null. */
fun Throwable.pinMismatch(): PinMismatch? =
    generateSequence(this) { it.cause }.take(16).filterIsInstance<PinMismatchException>().firstOrNull()?.mismatch

/** The base64 SHA-256 of a certificate's SubjectPublicKeyInfo DER, the form pins take. */
val X509Certificate.spki: String
    get() = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(publicKey.encoded))

/**
 * Validates a server's chain with the platform's system trust for [host], returning the chain it
 * built up to its trust anchor, or throwing as the platform does.
 */
fun interface ChainValidator {
    fun validate(chain: Array<X509Certificate>, authType: String, host: String): List<X509Certificate>
}

/**
 * Every TigerDuck HTTPS client's trust (A9). The platform's network-security-config trust
 * manager decides system trust, as it always has; this applies the pins on top (A4):
 *
 * - any chain holding one of [CompromisedCertificates]: refused first, whatever the platform's
 *   store and the pins say;
 * - a host no pin set covers, or a call naming no host: the platform's check alone;
 * - a pinned host: the platform validates the chain and builds it up to its anchor, so a root
 *   pin matches even when the server leaves the root out. A chain it refuses fails as the
 *   platform failed, never as a mismatch. A validated chain is allowed when its set has expired
 *   or any of its SPKIs is an effective pin; otherwise [onMismatch] hears of it and
 *   [PinMismatchException] fails the handshake.
 */
class PinnedTrustManager(
    private val platform: X509ExtendedTrustManager,
    private val validator: ChainValidator,
    private val policy: () -> PinPolicy,
    private val onMismatch: (PinMismatch) -> Unit,
    private val now: () -> Instant = Instant::now,
) : X509ExtendedTrustManager() {

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        check(chain, authType, (socket as? SSLSocket)?.handshakeSession?.peerHost) {
            platform.checkServerTrusted(chain, authType, socket)
        }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        check(chain, authType, engine?.handshakeSession?.peerHost ?: engine?.peerHost) {
            platform.checkServerTrusted(chain, authType, engine)
        }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        CompromisedCertificates.check(chain.asList())
        platform.checkServerTrusted(chain, authType)
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        platform.checkClientTrusted(chain, authType, socket)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        platform.checkClientTrusted(chain, authType, engine)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        platform.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers

    /** Each open connection's chain as validated for a host, once, with its SPKIs. */
    private val validatedByConnection = WeakHashMap<Any, MutableMap<String, Pair<List<X509Certificate>, Set<String>>>>()

    /**
     * For each request OkHttp writes, over a connection just made or one it reuses, pooled or
     * coalesced from another host's (HTTP/2): the connection's [peerCertificates] must meet the
     * pins in force now for [host], which a newer document, or one that expired, may have changed
     * since the handshake. They are built up to their anchor for [host] once per [connection], as
     * its own handshake's would be, and a mismatch is one like a handshake's, which [onMismatch]
     * hears of and a retry can clear, carried by an [SSLPeerUnverifiedException]. Throws before
     * the request is written.
     */
    fun checkExchange(host: String, peerCertificates: List<Certificate>, connection: Any) {
        val rules = policy()
        val at = now()
        val set = rules.setFor(host, at) ?: return
        val (validated, spkis) = validated(connection, host, peerCertificates)
        mismatchOf(rules, set, validated, spkis, host, at)?.let { mismatch ->
            throw SSLPeerUnverifiedException("pins for $host not met on an open connection")
                .apply { initCause(PinMismatchException(mismatch)) }
        }
    }

    private fun validated(connection: Any, host: String, peerCertificates: List<Certificate>): Pair<List<X509Certificate>, Set<String>> {
        synchronized(validatedByConnection) { validatedByConnection[connection]?.get(host) }?.let { return it }
        val served = peerCertificates.filterIsInstance<X509Certificate>().toTypedArray()
        val validated = try {
            if (served.isEmpty()) throw CertificateException("no certificates")
            validator.validate(served, OPEN_CONNECTION_AUTH_TYPE, host)
        } catch (e: CertificateException) {
            throw SSLPeerUnverifiedException("$host's trust not met on an open connection").apply { initCause(e) }
        }
        val entry = validated to validated.map { it.spki }.toSet()
        synchronized(validatedByConnection) { validatedByConnection.getOrPut(connection) { HashMap() }[host] = entry }
        return entry
    }

    private fun check(chain: Array<X509Certificate>, authType: String, host: String?, unpinned: () -> Unit) {
        CompromisedCertificates.check(chain.asList())
        val rules = policy()
        val at = now()
        val set = host?.let { rules.setFor(it, at) }
        if (host == null || set == null) {
            unpinned()
            // The platform may have completed the chain with an intermediate the server left out:
            // built here as it would be, so that one is checked too. A call with no host has no
            // chain to build, and only the one served is checked.
            if (host != null) CompromisedCertificates.check(validator.validate(chain, authType, host))
            return
        }
        val validated = validator.validate(chain, authType, host)
        // The chain the platform built may hold an intermediate the server left out.
        CompromisedCertificates.check(validated)
        mismatchOf(rules, set, validated, validated.map { it.spki }.toSet(), host, at)?.let { throw PinMismatchException(it) }
    }

    /** The mismatch [validated], with [spkis], makes with [set]'s pins, which [onMismatch] hears of, or null. */
    private fun mismatchOf(
        rules: PinPolicy,
        set: PinSet,
        validated: List<X509Certificate>,
        spkis: Set<String>,
        host: String,
        at: Instant,
    ): PinMismatch? {
        val decision = rules.decide(set, spkis, at) as? PinDecision.Mismatch ?: return null
        return PinMismatch(decision.set.id, host, decision.set.domains, validated, spkis).also { runCatching { onMismatch(it) } }
    }

    private companion object {
        /**
         * An open connection's key exchange auth type, which only key-usage checks read: its own
         * handshake ran those.
         */
        const val OPEN_CONNECTION_AUTH_TYPE = "UNKNOWN"
    }
}

/**
 * What a client does once a call failed on a [PinMismatch]: whether to try it once more, which
 * may wait for a newer pin document or, in the foreground, the student's answer (A4, A6).
 */
fun interface PinMismatchResolver {
    /** Whether to try once more; [isCanceled] says the call was given up, by its timeout or its caller. */
    fun mayRetry(mismatch: PinMismatch, isCanceled: () -> Boolean): Boolean
}

/** The TLS every TigerDuck HTTPS client takes: [PinnedTrustManager]'s socket factory and the manager. */
class PinnedTls(
    val trustManager: X509TrustManager,
    val resolver: PinMismatchResolver = PinMismatchResolver { _, _ -> false },
    /** The pins [trustManager] reads, whose changes close the idle connections. */
    state: PinState? = null,
) {

    /** The pool every client made by [pinnedClientBuilder] shares. */
    val connectionPool = ConnectionPool()

    init {
        // A new document, or a debug simulation, can change the pins: the idle connections, made
        // under the old ones, are closed so the next request makes a handshake under the new ones.
        // One busy with a call meets them request by request (PinnedTrustManager.checkExchange).
        // An acceptance only adds pins, so it leaves them open. Off the updating thread, which may
        // be the main one: closing a TLS socket writes its close_notify.
        state?.onChange { old, new ->
            if (old.document !== new.document || old.simulatedMismatch != new.simulatedMismatch) {
                thread(isDaemon = true, name = "tls-pins-evict") { connectionPool.evictAll() }
            }
        }
    }

    val socketFactory: SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory

    companion object {
        /**
         * The platform's trust, network_security_config.xml applied, with [state]'s pins on top.
         * The platform builds the validated chain through [X509TrustManagerExtensions], public
         * since API 17.
         */
        fun forPlatform(
            state: PinState,
            onMismatch: (PinMismatch) -> Unit,
            resolver: PinMismatchResolver,
        ): PinnedTls {
            val platform = platformTrust()
            val extensions = X509TrustManagerExtensions(platform)
            val validator = ChainValidator { chain, authType, host -> extensions.checkServerTrusted(chain, authType, host) }
            return PinnedTls(PinnedTrustManager(platform, validator, { state.policy }, onMismatch), resolver, state)
        }

        /** The platform's default trust manager: on Android, the one network_security_config.xml configures. */
        fun platformTrust(): X509ExtendedTrustManager =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as KeyStore?) }
                .trustManagers
                .filterIsInstance<X509ExtendedTrustManager>()
                .first()
    }
}
