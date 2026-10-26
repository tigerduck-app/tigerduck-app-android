package org.ntust.app.tigerduck.shared.tls

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.Dns
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.concurrent.thread

/** A hand-made Ed25519 key and signed pin documents, for tests only. */
class TestSigner(val keyId: String = "td-pins-test") {
    private val pair = Ed25519KeyPairGenerator().run {
        init(Ed25519KeyGenerationParameters(SecureRandom()))
        generateKeyPair()
    }

    val publicKey: ByteArray = (pair.public as Ed25519PublicKeyParameters).encoded

    val keys: Map<String, ByteArray> get() = mapOf(keyId to publicKey)

    fun sign(bytes: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, pair.private as Ed25519PrivateKeyParameters)
        update(bytes, 0, bytes.size)
        generateSignature()
    }

    /** An envelope over [payload]'s bytes, as TigerSync serves it. */
    fun envelope(
        payload: String,
        format: Int = 1,
        keyId: String = this.keyId,
        signature: ByteArray = sign(payload.toByteArray()),
    ): ByteArray = JsonObject().apply {
        addProperty("format", format)
        addProperty("key_id", keyId)
        addProperty("payload", base64Url(payload.toByteArray()))
        addProperty("signature", base64Url(signature))
    }.toString().toByteArray()

    companion object {
        fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun payload(
            serial: Long,
            expiresAt: Instant,
            sets: List<PinSet>,
            format: Int = 1,
            issuedAt: Instant = expiresAt.minusSeconds(30L * 24 * 3600),
        ): String = JsonObject().apply {
            addProperty("format", format)
            addProperty("serial", serial)
            addProperty("issued_at", issuedAt.toString())
            addProperty("expires_at", expiresAt.toString())
            add(
                "pin_sets",
                JsonArray().apply {
                    sets.forEach { set ->
                        add(
                            JsonObject().apply {
                                addProperty("id", set.id)
                                add(
                                    "domains",
                                    JsonArray().apply {
                                        set.domains.forEach { domain ->
                                            add(
                                                JsonObject().apply {
                                                    addProperty("name", domain.name)
                                                    addProperty("include_subdomains", domain.includeSubdomains)
                                                },
                                            )
                                        }
                                    },
                                )
                                add("pins", JsonArray().apply { set.pins.forEach(::add) })
                                addProperty("expires_at", set.expiresAt.toString())
                            },
                        )
                    }
                },
            )
        }.toString()
    }
}

/**
 * The platform's validation as a JVM test can have it: [trust]'s check, then the chain built up to
 * the trust anchor that issued its last certificate, as Android's X509TrustManagerExtensions
 * returns it, so a root the server leaves out is still in the validated chain.
 */
class AnchoringValidator(private val trust: X509ExtendedTrustManager) : ChainValidator {
    override fun validate(chain: Array<X509Certificate>, authType: String, host: String): List<X509Certificate> {
        trust.checkServerTrusted(chain, authType)
        val built = chain.toMutableList()
        while (true) {
            val last = built.last()
            if (last.subjectX500Principal == last.issuerX500Principal) break
            val anchor = trust.acceptedIssuers.firstOrNull { candidate ->
                candidate.subjectX500Principal == last.issuerX500Principal &&
                    runCatching { last.verify(candidate.publicKey) }.isSuccess
            } ?: throw CertificateException("no anchor for ${last.issuerX500Principal}")
            if (anchor in built) break
            built += anchor
        }
        return built
    }
}

/**
 * "localhost" as [LocalTlsServer] listens on it: IPv4 loopback only, where a Mac's resolver
 * would try IPv6 first and a test client, retrying no route, would give up.
 */
val LOOPBACK_DNS = Dns { listOf(InetAddress.getLoopbackAddress()) }

/** A TLS server on the loopback that serves [leaf] and [chain] and counts the request bytes it reads. */
class LocalTlsServer(
    leaf: HeldCertificate,
    chain: List<X509Certificate>,
    /** Whether a connection stays open for the next request, as a pooled one does. */
    private val keepAlive: Boolean = false,
    /** The response to a request, by its request line. */
    private val reply: (String) -> String = { OK },
) : AutoCloseable {
    private val socket = HandshakeCertificates.Builder()
        .heldCertificate(leaf, *chain.toTypedArray())
        .build()
        .sslContext()
        .serverSocketFactory
        .createServerSocket(0, 4, InetAddress.getLoopbackAddress()) as SSLServerSocket

    val port: Int = socket.localPort

    /** Bytes of request a client sent once its handshake was through. */
    val requestBytes = AtomicInteger()

    /** Each request's line, in the order they came. */
    val requestLines: MutableList<String> = Collections.synchronizedList(mutableListOf())

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() as SSLSocket }.getOrNull() ?: break
                connections.incrementAndGet()
                thread(isDaemon = true) { runCatching { client.use(::serve) } }
            }
        }
    }

    /** Connections a client opened. */
    val connections = AtomicInteger()

    private fun serve(client: SSLSocket) {
        client.soTimeout = 5_000
        client.startHandshake()
        val input = client.inputStream
        do {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) break
                requestBytes.incrementAndGet()
                head.append(b.toChar())
            }
            if (head.isEmpty()) return
            val length = CONTENT_LENGTH.find(head)?.groupValues?.get(1)?.toInt() ?: 0
            repeat(length) { if (input.read() >= 0) requestBytes.incrementAndGet() }
            val line = head.toString().substringBefore("\r\n")
            requestLines += line
            val response = reply(line)
            client.outputStream.write((if (keepAlive) response.replace("Connection: close\r\n", "") else response).toByteArray())
            client.outputStream.flush()
        } while (keepAlive)
    }

    override fun close() = socket.close()

    companion object {
        const val OK = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
        private val CONTENT_LENGTH = Regex("""(?i)\r\ncontent-length: *(\d+)""")
    }
}
