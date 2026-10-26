package org.ntust.app.tigerduck.shared

import org.junit.Assert.assertEquals
import org.junit.Test

/** The TAT look's pastel, the math the phone's class table and the watch's cards share. */
class PastelTest {

    @Test
    fun `each channel moves its share of the way to white`() {
        assertEquals(0.65f, Pastel.channel(0f, Pastel.WHITE_SHARE), 1e-6f)
        assertEquals(1f, Pastel.channel(1f, Pastel.WHITE_SHARE), 1e-6f)
        assertEquals(0.5f + 0.5f * 0.82f, Pastel.channel(0.5f, Pastel.MUTED_WHITE_SHARE), 1e-6f)
    }

    @Test
    fun `a muted card is paler than a card`() {
        assertEquals(true, Pastel.MUTED_WHITE_SHARE > Pastel.WHITE_SHARE)
    }
}
