package org.ntust.app.tigerduck.debug

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.ntust.app.tigerduck.shared.tls.BuiltInPins
import org.ntust.app.tigerduck.shared.tls.PinDocument
import org.ntust.app.tigerduck.shared.tls.PinDocumentResult
import org.ntust.app.tigerduck.shared.tls.PinDocumentVerifier
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/**
 * Debug builds only: a pin document signed with a key made on the spot, standing in for
 * TigerSync's until a production one is published, so a mismatch's prompt (D18) can be seen. It
 * lists the built-in sets as they are and offers the two TWCA roots NTUST's chains end in.
 * TigerSync's keys refuse it, so it is never stored: a restart drops it.
 */
internal object DebugPinDocument {

    /** TWCA Global Root CA and TWCA CYBER Root CA. */
    val TWCA_ROOTS = setOf(
        "xES1tmzl1x4bXkDyc4XJXL/SSgW1b3DKwJkvD1DDN5w=",
        "BmALlMAxi7aWfwx3h8yKEDKhecTpXjxXYLMuKQ9/7Js=",
    )

    private const val KEY_ID = "td-pins-debug"

    class Made(val envelope: ByteArray, val document: PinDocument)

    /** A document one serial past [heldSerial], in force for a day from [now]. */
    fun make(heldSerial: Long, now: Instant): Made {
        val pair = Ed25519KeyPairGenerator().run {
            init(Ed25519KeyGenerationParameters(SecureRandom()))
            generateKeyPair()
        }
        val payload = JsonObject().apply {
            addProperty("format", 1)
            addProperty("serial", heldSerial + 1)
            addProperty("issued_at", now.toString())
            addProperty("expires_at", now.plusSeconds(86_400).toString())
            add("pin_sets", JsonArray().apply { BuiltInPins.sets.forEach { add(it.toJson()) } })
            add("prompt_roots", JsonArray().apply { TWCA_ROOTS.forEach(::add) })
        }.toString().toByteArray()
        val signature = Ed25519Signer().run {
            init(true, pair.private as Ed25519PrivateKeyParameters)
            update(payload, 0, payload.size)
            generateSignature()
        }
        val url = Base64.getUrlEncoder().withoutPadding()
        val envelope = JsonObject().apply {
            addProperty("format", 1)
            addProperty("key_id", KEY_ID)
            addProperty("payload", url.encodeToString(payload))
            addProperty("signature", url.encodeToString(signature))
        }.toString().toByteArray()
        val key = mapOf(KEY_ID to (pair.public as Ed25519PublicKeyParameters).encoded)
        val verified = PinDocumentVerifier(key) { now }.verify(envelope) as PinDocumentResult.Verified
        return Made(envelope, verified.document)
    }

    private fun org.ntust.app.tigerduck.shared.tls.PinSet.toJson() = JsonObject().apply {
        addProperty("id", id)
        add(
            "domains",
            JsonArray().apply {
                domains.forEach { domain ->
                    add(JsonObject().apply { addProperty("name", domain.name); addProperty("include_subdomains", domain.includeSubdomains) })
                }
            },
        )
        add("pins", JsonArray().apply { pins.forEach(::add) })
        addProperty("expires_at", expiresAt.toString())
    }
}
