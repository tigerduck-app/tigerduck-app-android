package org.ntust.app.tigerduck.network.tls

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.tls.HeldCertificate
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.ntust.app.tigerduck.shared.tls.PinDomain
import org.ntust.app.tigerduck.shared.tls.PinMismatch
import org.ntust.app.tigerduck.shared.tls.PinSet
import org.ntust.app.tigerduck.shared.tls.spki
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/** A hand-made chain for host, intermediate and root, and mismatches made from it. */
class PinTestChain(val host: String = "moodle2.ntust.edu.tw", rootName: String = "Public Root") {
    val root: HeldCertificate = HeldCertificate.Builder().certificateAuthority(1).commonName(rootName)
        .organizationalUnit("Root Org").build()
    val intermediate: HeldCertificate = HeldCertificate.Builder().certificateAuthority(0).commonName("Issuing CA")
        .organizationalUnit("Issuing Org").signedBy(root).build()
    val leaf: HeldCertificate = HeldCertificate.Builder().addSubjectAlternativeName(host).commonName(host).signedBy(intermediate).build()

    val certificates get() = listOf(leaf.certificate, intermediate.certificate, root.certificate)

    fun mismatch(setId: String = "ntust", domains: List<PinDomain> = listOf(PinDomain("ntust.edu.tw", true))) =
        PinMismatch(setId, host, domains, certificates, certificates.map { it.spki }.toSet())
}

/** Ed25519-signed pin documents for tests. */
class PinTestSigner(val keyId: String = "td-pins-test") {
    private val pair = Ed25519KeyPairGenerator().run {
        init(Ed25519KeyGenerationParameters(SecureRandom()))
        generateKeyPair()
    }

    val keys: Map<String, ByteArray> get() = mapOf(keyId to (pair.public as Ed25519PublicKeyParameters).encoded)

    fun envelope(serial: Long, expiresAt: Instant, sets: List<PinSet>, promptRoots: Set<String> = emptySet()): ByteArray {
        val payload = JsonObject().apply {
            addProperty("format", 1)
            addProperty("serial", serial)
            addProperty("issued_at", expiresAt.minusSeconds(86_400).toString())
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
                                        set.domains.forEach {
                                            add(JsonObject().apply { addProperty("name", it.name); addProperty("include_subdomains", it.includeSubdomains) })
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
            add("prompt_roots", JsonArray().apply { promptRoots.forEach(::add) })
        }.toString().toByteArray()
        val signature = Ed25519Signer().run {
            init(true, pair.private as Ed25519PrivateKeyParameters)
            update(payload, 0, payload.size)
            generateSignature()
        }
        val url = Base64.getUrlEncoder().withoutPadding()
        return JsonObject().apply {
            addProperty("format", 1)
            addProperty("key_id", keyId)
            addProperty("payload", url.encodeToString(payload))
            addProperty("signature", url.encodeToString(signature))
        }.toString().toByteArray()
    }
}
