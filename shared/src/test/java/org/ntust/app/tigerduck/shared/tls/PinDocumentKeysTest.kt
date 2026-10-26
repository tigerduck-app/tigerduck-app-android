package org.ntust.app.tigerduck.shared.tls

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TigerSync's signing keys (TLS pins spec, A3): the current one and the offline standby. */
class PinDocumentKeysTest {

    @Test
    fun `the app accepts the current and the standby key, and no other`() {
        assertEquals(setOf("td-pins-2026a", "td-pins-2026b"), PinDocumentKeys.keys.keys)
    }

    @Test
    fun `each key is a valid 32-byte Ed25519 public key`() {
        PinDocumentKeys.keys.forEach { (id, key) ->
            assertEquals("$id's length", Ed25519PublicKeyParameters.KEY_SIZE, key.size)
            assertTrue("$id isn't a valid Ed25519 point", Ed25519.validatePublicKeyFull(key, 0))
            // The verifier takes it as it is.
            Ed25519PublicKeyParameters(key, 0)
        }
    }

    @Test
    fun `no test key is among them`() {
        val test = TestSigner()
        assertFalse(PinDocumentKeys.keys.values.any { it.contentEquals(test.publicKey) })
    }

    @Test
    fun `a document the tests sign doesn't verify with the app's keys`() {
        val signer = TestSigner(keyId = "td-pins-2026a")
        val envelope = signer.envelope(TestSigner.payload(2, java.time.Instant.now().plusSeconds(3600), BuiltInPins.sets))

        val result = PinDocumentVerifier(PinDocumentKeys.keys).verify(envelope)

        assertEquals(PinDocumentRejection.BAD_SIGNATURE, (result as PinDocumentResult.Rejected).reason)
    }
}
