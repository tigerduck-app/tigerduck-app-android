// Pins when the Live Update's boundary alarm fires.
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
        assertEquals(now + sec(30), boundaryTriggerAt(now, listOf(now + sec(5)), minuteChange = null))
    }

    @Test
    fun `the minute change skips the floor`() {
        // A progress tick posted with 4m05s left: the island reads "5m" and
        // must read "4m" five seconds from now, not thirty.
        assertEquals(
            now + sec(6),
            boundaryTriggerAt(now, listOf(now + sec(120)), minuteChange = now + sec(5)),
        )
    }

    @Test
    fun `a sooner boundary past the floor still comes first`() {
        assertEquals(
            now + sec(41),
            boundaryTriggerAt(now, listOf(now + sec(40)), minuteChange = now + sec(50)),
        )
    }

    @Test
    fun `a floored boundary gives way to an earlier minute change`() {
        // The boundary ten seconds out would wait for the floor at 30s; the
        // minute change at 20s fires first and sees it anyway.
        assertEquals(
            now + sec(21),
            boundaryTriggerAt(now, listOf(now + sec(10)), minuteChange = now + sec(20)),
        )
    }

    @Test
    fun `nothing ahead still gets the watchdog refresh`() {
        assertEquals(now + 30 * 60_000L, boundaryTriggerAt(now, emptyList(), minuteChange = null))
        assertEquals(now + 30 * 60_000L, boundaryTriggerAt(now, listOf(now - sec(5)), minuteChange = null))
    }
}
