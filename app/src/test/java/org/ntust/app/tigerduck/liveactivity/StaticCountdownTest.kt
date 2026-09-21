package org.ntust.app.tigerduck.liveactivity

import org.junit.Assert.assertEquals
import org.junit.Test

class StaticCountdownTest {

    private val now = 1_000_000_000L
    private fun sec(s: Long) = s * 1_000L

    @Test
    fun `minutes round up so the text never runs ahead`() {
        assertEquals(38, StaticCountdown.minutesLeft(now + sec(37 * 60 + 1), now))
        assertEquals(1, StaticCountdown.minutesLeft(now + sec(30), now))
        assertEquals(1, StaticCountdown.minutesLeft(now + 1, now))
    }

    @Test
    fun `a whole minute left reads as that minute`() {
        assertEquals(2, StaticCountdown.minutesLeft(now + sec(120), now))
    }

    @Test
    fun `the next post lands when the displayed minute drops`() {
        // 2m05s left shows "3"; it becomes "2" five seconds from now.
        val target = now + sec(125)
        val next = StaticCountdown.nextChangeAt(target, now)
        assertEquals(now + sec(5), next)
        assertEquals(3, StaticCountdown.minutesLeft(target, now))
        assertEquals(2, StaticCountdown.minutesLeft(target, next))
    }

    @Test
    fun `on an exact minute the next change is a full minute away`() {
        val target = now + sec(120)
        assertEquals(now + sec(60), StaticCountdown.nextChangeAt(target, now))
    }

    @Test
    fun `the scheduler's one-second pad still reads the new minute`() {
        // LiveActivityManager fires a second after each boundary it is given.
        val target = now + sec(125)
        val firesAt = StaticCountdown.nextChangeAt(target, now) + sec(1)
        assertEquals(2, StaticCountdown.minutesLeft(target, firesAt))
    }
}
