package org.ntust.app.tigerduck.wear.data

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.tls.BuiltInPins
import org.ntust.app.tigerduck.shared.tls.PinAcceptance
import org.ntust.app.tigerduck.shared.tls.PinAcceptances
import org.ntust.app.tigerduck.shared.tls.PinDocumentVerifier
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/** The watch's TLS pins (TLS pins spec, A8): the forwarded envelope verified again, and acceptances by build. */
@RunWith(RobolectricTestRunner::class)
class WatchTlsPinsTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("wear_tls_pins_test", Context.MODE_PRIVATE)
    private val now = Instant.parse("2026-10-25T04:00:00Z")

    private val pair = Ed25519KeyPairGenerator().run {
        init(Ed25519KeyGenerationParameters(SecureRandom()))
        generateKeyPair()
    }
    private val verifier = PinDocumentVerifier(mapOf("k" to (pair.public as Ed25519PublicKeyParameters).encoded)) { now }

    private fun pins(phoneBuild: Long = 30) = WatchTlsPins(prefs, verifier, phoneBuild)

    private fun envelope(serial: Long, pin: String, signer: Ed25519PrivateKeyParameters = pair.private as Ed25519PrivateKeyParameters): ByteArray {
        val payload = JsonObject().apply {
            addProperty("format", 1)
            addProperty("serial", serial)
            addProperty("issued_at", now.toString())
            addProperty("expires_at", now.plusSeconds(86_400).toString())
            add(
                "pin_sets",
                JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty("id", "library")
                            add("domains", JsonArray().apply { add(JsonObject().apply { addProperty("name", "api.lib.ntust.edu.tw"); addProperty("include_subdomains", true) }) })
                            add("pins", JsonArray().apply { add(pin) })
                            addProperty("expires_at", "2027-05-04T00:00:00Z")
                        },
                    )
                },
            )
        }.toString().toByteArray()
        val signature = Ed25519Signer().run {
            init(true, signer)
            update(payload, 0, payload.size)
            generateSignature()
        }
        val url = Base64.getUrlEncoder().withoutPadding()
        return JsonObject().apply {
            addProperty("format", 1)
            addProperty("key_id", "k")
            addProperty("payload", url.encodeToString(payload))
            addProperty("signature", url.encodeToString(signature))
        }.toString().toByteArray()
    }

    private fun pin(n: Int) = Base64.getEncoder().encodeToString(ByteArray(32) { n.toByte() })

    private fun libraryPins(watch: WatchTlsPins) = watch.state.policy.setFor("api.lib.ntust.edu.tw", now)?.pins

    @Test
    fun `a forwarded envelope that verifies is taken, and kept`() {
        pins().applyPush(envelope(BuiltInPins.BASELINE_SERIAL + 2, pin(9)), acceptances = null, version = 1)

        assertEquals(setOf(pin(9)), libraryPins(pins()))
    }

    @Test
    fun `an envelope that doesn't verify on the watch is ignored`() {
        val stranger = Ed25519KeyPairGenerator().run { init(Ed25519KeyGenerationParameters(SecureRandom())); generateKeyPair() }

        pins().applyPush(envelope(BuiltInPins.BASELINE_SERIAL + 2, pin(9), stranger.private as Ed25519PrivateKeyParameters), acceptances = null, version = 1)

        assertNull(pins().state.policy.document)
    }

    @Test
    fun `an older envelope or an older push is dropped`() {
        val watch = pins()
        watch.applyPush(envelope(BuiltInPins.BASELINE_SERIAL + 5, pin(5)), null, version = 2)
        watch.applyPush(envelope(BuiltInPins.BASELINE_SERIAL + 4, pin(4)), null, version = 3)
        assertEquals(setOf(pin(5)), libraryPins(watch))

        watch.applyPush(envelope(BuiltInPins.BASELINE_SERIAL + 6, pin(6)), null, version = 2)
        assertEquals(setOf(pin(5)), libraryPins(watch))
    }

    @Test
    fun `an acceptance applies only while the watch's build pairs with the phone build it was made under`() {
        val acceptance = PinAcceptances.encode(listOf(PinAcceptance("library", setOf(pin(7)), appVersion = 30, acceptedAt = 1)))
        pins(phoneBuild = 30).applyPush(null, acceptance, version = 1)

        assert(pin(7) in libraryPins(pins(phoneBuild = 30))!!)
        assert(pin(7) !in libraryPins(pins(phoneBuild = 31))!!)
    }
}
