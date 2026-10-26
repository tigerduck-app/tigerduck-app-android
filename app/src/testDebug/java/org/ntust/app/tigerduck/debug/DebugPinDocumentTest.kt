package org.ntust.app.tigerduck.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.tls.BuiltInPins
import org.ntust.app.tigerduck.shared.tls.PinDocumentKeys
import org.ntust.app.tigerduck.shared.tls.PinDocumentResult
import org.ntust.app.tigerduck.shared.tls.PinDocumentVerifier
import java.io.File
import java.time.Instant

/** The debug build's stand-in for TigerSync's document, until a production one is published. */
class DebugPinDocumentTest {

    private val now = Instant.parse("2026-10-25T04:00:00Z")

    @Test
    fun `it verifies with its own key only, newer than the one held, offering the TWCA roots`() {
        val made = DebugPinDocument.make(heldSerial = 4, now = now)

        val document = made.document
        assertEquals(5L, document.serial)
        assertEquals(BuiltInPins.sets.map { it.id }, document.sets.map { it.id })
        assertEquals(DebugPinDocument.TWCA_ROOTS, document.promptRoots)
        assertTrue(DebugPinDocument.TWCA_ROOTS.all { root -> root in BuiltInPins.sets.single { it.id == "ntust" }.pins })
        assertTrue(now.isBefore(document.expiresAt))
        // TigerSync's keys refuse it.
        assertFalse(PinDocumentVerifier(PinDocumentKeys.keys) { now }.verify(made.envelope) is PinDocumentResult.Verified)
    }

    @Test
    fun `no release source reaches it`() {
        val mentions = listOf("src/main", "src/play", "src/fdroid").flatMap { dir ->
            File(dir).walkTopDown().filter { it.isFile && "DebugPinDocument" in it.readText() }.map { it.path }.toList()
        }

        assertEquals(emptyList<String>(), mentions)
    }
}
