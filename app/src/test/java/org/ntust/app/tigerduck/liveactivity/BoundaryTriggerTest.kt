// Pins when the Live Update's two alarms fire.
//
// A static countdown's minute change has to land on time — the island shows
// nothing newer until it does — while every other boundary keeps the 30s
// floor that stops a near-instant one from waking the phone in a loop.

package org.ntust.app.tigerduck.liveactivity

import org.junit.Assert.assertEquals
import org.junit.Test

class BoundaryTriggerTest {

    private val now = 1_000_000_000L
    private fun sec(s: Long) = s * 1_000L

    @Test
    fun `a boundary seconds away is held to the floor`() {
        assertEquals(now + sec(30), boundaryTriggerAt(now, listOf(now + sec(5))))
    }

    @Test
    fun `a boundary past the floor lands just after it`() {
        assertEquals(now + sec(41), boundaryTriggerAt(now, listOf(now + sec(40), now + sec(120))))
    }

    @Test
    fun `the minute change skips the floor`() {
        // A progress tick posted with 4m05s left: the island reads "5m" and
        // must read "4m" five seconds from now, not thirty.
        assertEquals(now + sec(6), minuteChangeTriggerAt(now + sec(4 * 60 + 5), now))
    }

    @Test
    fun `a post on the minute waits for the next one`() {
        assertEquals(now + sec(61), minuteChangeTriggerAt(now + sec(4 * 60), now))
    }

    @Test
    fun `nothing ahead still gets the watchdog refresh`() {
        assertEquals(now + 30 * 60_000L, boundaryTriggerAt(now, emptyList()))
        assertEquals(now + 30 * 60_000L, boundaryTriggerAt(now, listOf(now - sec(5))))
    }
}
