package org.ntust.app.tigerduck.shared.tls

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * TigerSync's first production pin document, as served on 2026-10-25: the one the built-in table
 * was copied from, so it verifies and overrides nothing.
 */
class ProductionPinDocumentTest {

    private val envelope = requireNotNull(javaClass.getResourceAsStream("/tls/tls-pins-1792888799.json")).use { it.readBytes() }

    /** A moment it is in force. */
    private val during = Instant.parse("2026-10-26T00:00:00Z")

    private val current = mapOf("td-pins-2026a" to PinDocumentKeys.keys.getValue("td-pins-2026a"))

    private val document: PinDocument
        get() = (PinDocumentVerifier(current) { during }.verify(envelope) as PinDocumentResult.Verified).document

    @Test
    fun `it is signed with the current key and verifies with the app's copy of it`() {
        val keyId = JsonParser.parseString(String(envelope)).asJsonObject.get("key_id").asString

        assertEquals("td-pins-2026a", keyId)
        assertTrue(PinDocumentVerifier(PinDocumentKeys.keys) { during }.verify(envelope) is PinDocumentResult.Verified)
    }

    @Test
    fun `its sets are the built-in table, and its serial the table's baseline`() {
        assertEquals(BuiltInPins.sets.associateBy { it.id }, document.sets.associateBy { it.id })
        assertEquals(BuiltInPins.BASELINE_SERIAL, document.serial)
    }

    @Test
    fun `so it overrides nothing, and offers both TWCA roots the school's chains end in`() {
        val rules = PinPolicy(document = document, appVersion = 30)

        assertEquals(BuiltInPins.sets, rules.sets(during))
        // TWCA Global Root CA and TWCA CYBER Root CA, so a school chain under either may be offered (D18).
        val twcaRoots = setOf("xES1tmzl1x4bXkDyc4XJXL/SSgW1b3DKwJkvD1DDN5w=", "BmALlMAxi7aWfwx3h8yKEDKhecTpXjxXYLMuKQ9/7Js=")
        assertTrue(document.promptRoots.containsAll(twcaRoots))
    }
}
