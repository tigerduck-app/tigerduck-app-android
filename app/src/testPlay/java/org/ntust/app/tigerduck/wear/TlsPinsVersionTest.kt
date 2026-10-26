package org.ntust.app.tigerduck.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The TLS pins push's version stays ahead of what the watch took, a reinstall included (A8). */
class TlsPinsVersionTest {

    @Test
    fun `a reinstalled phone, its counter gone, starts ahead of the watch's last`() {
        val watchTook = 1_793_000_000_000L
        val afterReinstall = tlsPinsVersion(last = 0, now = watchTook + 60_000)

        assertTrue(afterReinstall > watchTook)
    }

    @Test
    fun `a clock behind the last push still moves forward`() {
        assertEquals(1_000_001L, tlsPinsVersion(last = 1_000_000, now = 5))
    }
}
