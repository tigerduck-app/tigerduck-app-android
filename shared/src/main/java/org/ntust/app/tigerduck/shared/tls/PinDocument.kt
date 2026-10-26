package org.ntust.app.tigerduck.shared.tls

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.time.Instant
import java.util.Base64

/**
 * A pin document TigerSync signed (TLS pins spec, A3), verified: its sets, its serial and
 * expiry, and the envelope bytes exactly as received, which are what is stored and sent on to
 * the watch.
 */
class PinDocument(
    val serial: Long,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val sets: List<PinSet>,
    /**
     * The SPKIs of Mozilla's TLS roots (D18): a mismatch is offered to the student only when the
     * validated chain's anchor is one of them.
     */
    val promptRoots: Set<String>,
    val envelope: ByteArray,
)

/** Why a document was refused; every one leaves the app on what it already has. */
enum class PinDocumentRejection {
    /** Not the envelope or payload the format describes. */
    MALFORMED,

    /** An envelope or payload format this build doesn't know. */
    UNKNOWN_FORMAT,

    /** Signed with a key this build doesn't carry. */
    UNKNOWN_KEY,

    /** The signature doesn't verify over the payload bytes. */
    BAD_SIGNATURE,

    /** Past its own `expires_at` by the device clock. */
    EXPIRED,
}

sealed interface PinDocumentResult {
    class Verified(val document: PinDocument) : PinDocumentResult
    class Rejected(val reason: PinDocumentRejection) : PinDocumentResult
}

/**
 * Checks a pin document envelope: its format, its `key_id` against [keys], the Ed25519
 * signature over the payload's bytes before they are parsed, and the payload's expiry by
 * [now]. The envelope's integrity rests on the signature alone; it is fetched without pins.
 */
class PinDocumentVerifier(
    /** `key_id` → the raw 32-byte Ed25519 public key. */
    private val keys: Map<String, ByteArray>,
    private val now: () -> Instant = Instant::now,
) {

    /**
     * [envelope]'s document, or why not. With [allowExpired], one past its `expires_at` still
     * verifies: a stored document is loaded that way, so its serial keeps an older one out after
     * it expires, while [PinPolicy.documentAt] no longer applies it.
     */
    fun verify(envelope: ByteArray, allowExpired: Boolean = false): PinDocumentResult {
        val outer = parseObject(String(envelope, Charsets.UTF_8)) ?: return rejected(PinDocumentRejection.MALFORMED)
        val format = outer.int("format") ?: return rejected(PinDocumentRejection.MALFORMED)
        if (format != FORMAT) return rejected(PinDocumentRejection.UNKNOWN_FORMAT)
        val keyId = outer.string("key_id") ?: return rejected(PinDocumentRejection.MALFORMED)
        val payload = outer.string("payload")?.let(::base64Url) ?: return rejected(PinDocumentRejection.MALFORMED)
        val signature = outer.string("signature")?.let(::base64Url) ?: return rejected(PinDocumentRejection.MALFORMED)
        val key = keys[keyId] ?: return rejected(PinDocumentRejection.UNKNOWN_KEY)
        if (!verifies(key, payload, signature)) return rejected(PinDocumentRejection.BAD_SIGNATURE)

        // Only now, over bytes the signature vouches for, is the payload read.
        val body = parseObject(String(payload, Charsets.UTF_8)) ?: return rejected(PinDocumentRejection.MALFORMED)
        val payloadFormat = body.int("format") ?: return rejected(PinDocumentRejection.MALFORMED)
        if (payloadFormat != FORMAT) return rejected(PinDocumentRejection.UNKNOWN_FORMAT)
        val serial = body.long("serial")?.takeIf { it >= 0 } ?: return rejected(PinDocumentRejection.MALFORMED)
        val issuedAt = body.instant("issued_at") ?: return rejected(PinDocumentRejection.MALFORMED)
        val expiresAt = body.instant("expires_at") ?: return rejected(PinDocumentRejection.MALFORMED)
        val sets = body.array("pin_sets")?.map { it.asPinSet() ?: return rejected(PinDocumentRejection.MALFORMED) }
            ?: return rejected(PinDocumentRejection.MALFORMED)
        if (sets.map { it.id }.toSet().size != sets.size) return rejected(PinDocumentRejection.MALFORMED)
        // Absent, no mismatch is offered to the student; present, it must read.
        val promptRoots = body.get("prompt_roots")?.let { element ->
            val array = element.takeIf { it.isJsonArray }?.asJsonArray ?: return rejected(PinDocumentRejection.MALFORMED)
            array.map { it.asSpki() ?: return rejected(PinDocumentRejection.MALFORMED) }.toSet()
        }.orEmpty()
        if (!allowExpired && !now().isBefore(expiresAt)) return rejected(PinDocumentRejection.EXPIRED)
        return PinDocumentResult.Verified(PinDocument(serial, issuedAt, expiresAt, sets, promptRoots, envelope.copyOf()))
    }

    private fun verifies(key: ByteArray, payload: ByteArray, signature: ByteArray): Boolean {
        if (key.size != Ed25519PublicKeyParameters.KEY_SIZE || signature.size != SIGNATURE_SIZE) return false
        return runCatching {
            Ed25519Signer().run {
                init(false, Ed25519PublicKeyParameters(key, 0))
                update(payload, 0, payload.size)
                verifySignature(signature)
            }
        }.getOrDefault(false)
    }

    private fun JsonElement.asPinSet(): PinSet? {
        val set = this as? JsonObject ?: return null
        val id = set.string("id")?.takeIf { it.isNotBlank() } ?: return null
        val domains = set.array("domains")?.map { element ->
            val domain = element as? JsonObject ?: return null
            val name = domain.string("name")?.trimEnd('.')?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
            val subdomains = domain.boolean("include_subdomains") ?: return null
            PinDomain(name, subdomains)
        }?.takeIf { it.isNotEmpty() } ?: return null
        val pins = set.array("pins")?.map { it.asSpki() ?: return null }?.takeIf { it.isNotEmpty() }?.toSet() ?: return null
        val expiresAt = set.instant("expires_at") ?: return null
        return PinSet(id, domains, pins, expiresAt)
    }

    /** A pin: standard, padded base64 of a 32-byte SHA-256. */
    private fun JsonElement.asSpki(): String? {
        val pin = takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
        val digest = runCatching { Base64.getDecoder().decode(pin) }.getOrNull() ?: return null
        return pin.takeIf { digest.size == SHA256_SIZE }
    }

    private fun rejected(reason: PinDocumentRejection) = PinDocumentResult.Rejected(reason)

    private fun parseObject(text: String): JsonObject? =
        runCatching { JsonParser.parseString(text) }.getOrNull() as? JsonObject

    private fun JsonObject.primitive(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive

    private fun JsonObject.string(name: String): String? = primitive(name)?.takeIf { it.isString }?.asString

    private fun JsonObject.boolean(name: String): Boolean? = primitive(name)?.takeIf { it.isBoolean }?.asBoolean

    private fun JsonObject.long(name: String): Long? =
        primitive(name)?.takeIf { it.isNumber }?.let { runCatching { it.asBigDecimal.longValueExact() }.getOrNull() }

    private fun JsonObject.int(name: String): Int? = long(name)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    private fun JsonObject.instant(name: String): Instant? = string(name)?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun JsonObject.array(name: String) = get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun base64Url(text: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()

    companion object {
        /** The envelope and payload format this build reads. */
        const val FORMAT = 1
        private const val SIGNATURE_SIZE = 64
        private const val SHA256_SIZE = 32

        /**
         * Whether [candidate] may replace [held] (A5): a greater serial, or the same serial with
         * the very same bytes. Never a lower one.
         */
        fun supersedes(candidate: PinDocument, held: PinDocument?): Boolean = when {
            held == null -> true
            candidate.serial > held.serial -> true
            candidate.serial == held.serial -> candidate.envelope.contentEquals(held.envelope)
            else -> false
        }
    }
}
