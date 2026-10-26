package org.ntust.app.tigerduck.shared.tls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinAcceptancesTest {

    @Test
    fun `acceptances read back as written`() {
        val acceptances = listOf(
            PinAcceptance("ntust", setOf("a", "b"), appVersion = 30, acceptedAt = 1_793_000_000_000),
            PinAcceptance("library", setOf("c"), appVersion = 30, acceptedAt = 5),
        )

        assertEquals(acceptances, PinAcceptances.decode(PinAcceptances.encode(acceptances)))
    }

    @Test
    fun `unreadable text and broken entries read as none`() {
        assertTrue(PinAcceptances.decode(null).isEmpty())
        assertTrue(PinAcceptances.decode("not json").isEmpty())
        assertEquals(
            listOf(PinAcceptance("ntust", setOf("a"), 30, 1)),
            PinAcceptances.decode(
                """[{"set":"ntust","spkis":["a"],"app_version":30,"accepted_at":1},""" +
                    """{"set":"x"},{"set":"","spkis":["b"],"app_version":30,"accepted_at":1},""" +
                    """{"set":"y","spkis":[],"app_version":30,"accepted_at":1}]""",
            ),
        )
    }
}
