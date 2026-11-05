package org.ntust.app.tigerduck.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.shared.account.SignInSeries

/** The backend's clock at each field's last acknowledged write, for the sign-in that made it (LWW D12). */
class AckClockTest {

    private val series = SignInSeries()
    private val clock = AckClock()
    private val at = EARLIEST_CLOCK_US + 1_000_000

    @Test
    fun `each field keeps the latest clock that acknowledged it, whichever reply lands first`() {
        val signIn = series.current
        clock.acknowledged("a", at + 2, signIn)
        clock.acknowledged("a", at + 1, signIn)
        clock.acknowledged("b", at + 1, signIn)
        clock.acknowledged("b", at + 3, signIn)

        assertEquals(at + 2, clock.lastAck("a"))
        assertEquals(at + 3, clock.lastAck("b"))
        assertNull(clock.lastAck("c"))
    }

    @Test
    fun `an acknowledgment is the sign-in's alone - gone once it ends, and none from an ended one`() {
        val first = series.current
        clock.acknowledged("a", at, first)
        series.end()
        assertNull(clock.lastAck("a"))

        clock.acknowledged("a", at + 1, first)
        series.open()
        assertNull(clock.lastAck("a"))

        val second = series.current
        clock.acknowledged("b", at + 2, second)
        clock.acknowledged("a", at + 3, first)
        assertNull(clock.lastAck("a"))
        assertEquals(at + 2, clock.lastAck("b"))
    }

    @Test
    fun `a value no clock can hold is ignored`() {
        val signIn = series.current
        for (wrong in listOf(0L, -1L, EARLIEST_CLOCK_US - 1, LATEST_CLOCK_US + 1, Long.MAX_VALUE)) {
            clock.acknowledged("a", wrong, signIn)
        }

        assertNull(clock.lastAck("a"))
    }
}
