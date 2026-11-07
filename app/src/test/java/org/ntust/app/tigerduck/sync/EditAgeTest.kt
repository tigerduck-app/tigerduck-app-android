package org.ntust.app.tigerduck.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An edit's `age_ms` (backend 4.3.0): the milliseconds from the edit to its send, by the elapsed
 * clock when both fall in one boot, else by the wall clock, never below 0, read again at every send.
 */
class EditAgeTest {

    private var wall = 1_790_000_000_000L
    private var elapsed: Long? = 5_000_000L
    private var boot: Int? = 7
    private val clocks = DeviceClocks({ wall }, { elapsed }, { boot })

    @Test
    fun `within one boot the age is the elapsed clock's`() {
        val edit = clocks.now()
        wall += 3_000
        elapsed = elapsed!! + 2_500

        assertEquals(2_500L, clocks.ageMs(edit))
    }

    @Test
    fun `a wall clock moved back after the edit does not change an age within one boot`() {
        val edit = clocks.now()
        wall -= 3_600_000
        elapsed = elapsed!! + 1_200

        assertEquals(1_200L, clocks.ageMs(edit))
    }

    @Test
    fun `after another boot the age is the wall clock's`() {
        val edit = clocks.now()
        boot = 8
        elapsed = 40_000L
        wall += 90_000

        assertEquals(90_000L, clocks.ageMs(edit))
    }

    @Test
    fun `with the boot count unknown, on either side, the age is the wall clock's`() {
        boot = null
        val unknownAtEdit = clocks.now()
        boot = 7
        val knownAtEdit = clocks.now()
        elapsed = elapsed!! + 500
        wall += 4_000

        assertEquals(4_000L, clocks.ageMs(unknownAtEdit))
        boot = null
        assertEquals(4_000L, clocks.ageMs(knownAtEdit))
    }

    @Test
    fun `an edit an older build kept, with its wall time alone, ages by the wall clock`() {
        val older = EditTime(wall - 61_000)

        assertEquals(61_000L, clocks.ageMs(older))
    }

    @Test
    fun `an age is never negative`() {
        val ahead = EditTime(wall + 10_000)
        val sameBootAhead = EditTime(wall, elapsed!! + 10_000, boot)

        assertEquals(0L, clocks.ageMs(ahead))
        assertEquals(0L, clocks.ageMs(sameBootAhead))
    }

    @Test
    fun `an edit made now, with no time kept, is 0 old`() {
        assertEquals(0L, clocks.ageMs(null))
    }

    @Test
    fun `the age is read again at each send, so a retry sends a grown one`() {
        val edit = clocks.now()
        elapsed = elapsed!! + 1_000
        val first = clocks.ageMs(edit)
        elapsed = elapsed!! + 30_000
        val retry = clocks.ageMs(edit)

        assertEquals(listOf(1_000L, 31_000L), listOf(first, retry))
    }

    @Test
    fun `the oldest edit is as old as the wall clock is`() {
        assertEquals(wall, clocks.ageMs(EditTime.OLDEST))
    }

    @Test
    fun `one moment is before another by the elapsed clock within one boot, else by the wall clock`() {
        val first = EditTime(wallMs = 2_000, elapsedMs = 100, boot = 7)
        val second = EditTime(wallMs = 1_000, elapsedMs = 200, boot = 7)
        val nextBoot = EditTime(wallMs = 1_500, elapsedMs = 50, boot = 8)

        assertTrue(first.isBefore(second))
        assertFalse(second.isBefore(first))
        assertTrue(nextBoot.isBefore(first))
        assertFalse(first.isBefore(first))
    }
}
