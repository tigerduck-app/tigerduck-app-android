package org.ntust.app.tigerduck.shared.tls

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Pin document verification (A12, document), over documents signed with a hand-made key. */
class PinDocumentVerifierTest {

    private val signer = TestSigner()
    private val now = Instant.parse("2026-10-25T04:00:00Z")
    private val verifier = PinDocumentVerifier(signer.keys) { now }

    private val ntust = PinSet(
        "ntust",
        listOf(PinDomain("ntust.edu.tw", true)),
        setOf("9VZ7Yd685RTXsE6rL/puuMbnejYaXwaZasGL7c+Uolc="),
        Instant.parse("2027-08-18T00:00:00Z"),
    )
    private val rootPin = "sLVjNUaFYfW7n6EtgBeEpjOlcnBdNPMrZDRF36iwBdE="

    private fun payload(serial: Long = 1793001600, expiresAt: Instant = now.plusSeconds(86_400), format: Int = 1) =
        TestSigner.payload(serial, expiresAt, listOf(ntust), format).let { text ->
            JsonParser.parseString(text).asJsonObject.apply {
                add("prompt_roots", com.google.gson.JsonArray().apply { add(rootPin) })
            }.toString()
        }

    private fun verified(envelope: ByteArray): PinDocument =
        (verifier.verify(envelope) as? PinDocumentResult.Verified)?.document
            ?: throw AssertionError("refused: ${(verifier.verify(envelope) as PinDocumentResult.Rejected).reason}")

    private fun rejection(envelope: ByteArray): PinDocumentRejection? =
        (verifier.verify(envelope) as? PinDocumentResult.Rejected)?.reason

    @Test
    fun `a valid document verifies, keeping its envelope's bytes`() {
        val envelope = signer.envelope(payload())

        val document = verified(envelope)

        assertEquals(1793001600L, document.serial)
        assertEquals(listOf(ntust), document.sets)
        assertEquals(setOf(rootPin), document.promptRoots)
        assertArrayEquals(envelope, document.envelope)
    }

    @Test
    fun `a bad signature is refused`() {
        val signature = signer.sign(payload().toByteArray()).also { it[0] = (it[0] + 1).toByte() }

        assertEquals(PinDocumentRejection.BAD_SIGNATURE, rejection(signer.envelope(payload(), signature = signature)))
    }

    @Test
    fun `a payload changed after signing is refused`() {
        val envelope = signer.envelope(payload(), signature = signer.sign(payload(serial = 1).toByteArray()))

        assertEquals(PinDocumentRejection.BAD_SIGNATURE, rejection(envelope))
    }

    @Test
    fun `a document signed by a key this build doesn't carry is refused`() {
        val stranger = TestSigner(keyId = "td-pins-2026a")

        assertEquals(PinDocumentRejection.UNKNOWN_KEY, rejection(stranger.envelope(payload())))
        // The right name doesn't help a different key.
        assertEquals(PinDocumentRejection.BAD_SIGNATURE, rejection(TestSigner(signer.keyId).envelope(payload())))
    }

    @Test
    fun `an unknown format is refused, in the envelope or the payload`() {
        assertEquals(PinDocumentRejection.UNKNOWN_FORMAT, rejection(signer.envelope(payload(), format = 2)))
        assertEquals(PinDocumentRejection.UNKNOWN_FORMAT, rejection(signer.envelope(payload(format = 2))))
    }

    @Test
    fun `an expired document is refused, unless only its serial is wanted`() {
        val expired = signer.envelope(payload(expiresAt = now))

        assertEquals(PinDocumentRejection.EXPIRED, rejection(expired))
        assertTrue(verifier.verify(expired, allowExpired = true) is PinDocumentResult.Verified)
    }

    @Test
    fun `a document that isn't the format described is refused`() {
        assertEquals(PinDocumentRejection.MALFORMED, rejection("not json".toByteArray()))
        val badPin = JsonParser.parseString(payload()).asJsonObject.apply {
            getAsJsonArray("pin_sets")[0].asJsonObject.add("pins", com.google.gson.JsonArray().apply { add("short") })
        }.toString()
        assertEquals(PinDocumentRejection.MALFORMED, rejection(signer.envelope(badPin)))
        val noSets = JsonObject().apply {
            addProperty("format", 1)
            addProperty("serial", 2)
            addProperty("issued_at", now.toString())
            addProperty("expires_at", now.plusSeconds(60).toString())
        }.toString()
        assertEquals(PinDocumentRejection.MALFORMED, rejection(signer.envelope(noSets)))
    }

    @Test
    fun `a document with no prompt roots offers no prompt`() {
        val document = verified(signer.envelope(TestSigner.payload(2, now.plusSeconds(60), listOf(ntust))))

        assertTrue(document.promptRoots.isEmpty())
    }

    @Test
    fun `only a greater serial, or the same bytes again, replace a held document`() {
        val held = verified(signer.envelope(payload(serial = 10)))

        assertTrue(PinDocumentVerifier.supersedes(verified(signer.envelope(payload(serial = 11))), held))
        assertFalse(PinDocumentVerifier.supersedes(verified(signer.envelope(payload(serial = 9))), held))
        // Same serial, other bytes: a later expiry.
        assertFalse(
            PinDocumentVerifier.supersedes(
                verified(signer.envelope(payload(serial = 10, expiresAt = now.plusSeconds(172_800)))),
                held,
            ),
        )
        assertTrue(PinDocumentVerifier.supersedes(verified(held.envelope), held))
        assertTrue(PinDocumentVerifier.supersedes(held, null))
    }
}
