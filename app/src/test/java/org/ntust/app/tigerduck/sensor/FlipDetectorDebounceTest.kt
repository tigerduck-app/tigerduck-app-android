package org.ntust.app.tigerduck.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val DEBOUNCE_NANOS = FlipDetector.DEBOUNCE_NANOS

class FlipDetectorDebounceTest {

    @Test
    fun `unknown stays unknown until the debounce window elapses`() {
        val s0 = FlipDetector.DetectorState.initial()
        val s1 = FlipDetector.nextState(s0, isFaceDown = true, timestampNanos = 0L)
        assertEquals(FlipDetector.Phase.Unknown, s1.phase)
        assertFalse(s1.fired)

        // Halfway through the window: still Unknown.
        val s2 = FlipDetector.nextState(s1, isFaceDown = true, timestampNanos = DEBOUNCE_NANOS / 2)
        assertEquals(FlipDetector.Phase.Unknown, s2.phase)
    }

    @Test
    fun `cold start face down does not fire the callback`() {
        // Even after holding face-down past the debounce window, an
        // Unknown -> FaceDown transition must not fire — the user did not
        // intentionally flip from upright.
        val s0 = FlipDetector.DetectorState.initial()
        val s1 = FlipDetector.nextState(s0, isFaceDown = true, timestampNanos = 0L)
        val s2 = FlipDetector.nextState(s1, isFaceDown = true, timestampNanos = DEBOUNCE_NANOS + 1)
        assertEquals(FlipDetector.Phase.FaceDown, s2.phase)
        assertFalse("Unknown -> FaceDown must not fire", s2.fired)
    }

    @Test
    fun `flip from upright to face down fires after debounce window`() {
        // Seed: hold upright past the window so state settles to Upright.
        var s = FlipDetector.DetectorState.initial()
        s = FlipDetector.nextState(s, isFaceDown = false, timestampNanos = 0L)
        s = FlipDetector.nextState(s, isFaceDown = false, timestampNanos = DEBOUNCE_NANOS + 1)
        assertEquals(FlipDetector.Phase.Upright, s.phase)
        assertFalse(s.fired)

        // Start flipping at t = window + 1ns.
        val flipStart = DEBOUNCE_NANOS + 1
        s = FlipDetector.nextState(s, isFaceDown = true, timestampNanos = flipStart + 1)
        // Still Upright — window not elapsed yet.
        assertEquals(FlipDetector.Phase.Upright, s.phase)
        assertFalse(s.fired)

        // Past the window: transition fires.
        s = FlipDetector.nextState(s, isFaceDown = true, timestampNanos = flipStart + DEBOUNCE_NANOS + 1)
        assertEquals(FlipDetector.Phase.FaceDown, s.phase)
        assertTrue("Upright -> FaceDown must fire", s.fired)
    }

    @Test
    fun `brief face down flicker does not fire`() {
        // Seed Upright.
        var s = FlipDetector.DetectorState.initial()
        s = FlipDetector.nextState(s, isFaceDown = false, timestampNanos = 0L)
        s = FlipDetector.nextState(s, isFaceDown = false, timestampNanos = DEBOUNCE_NANOS + 1)
        assertEquals(FlipDetector.Phase.Upright, s.phase)

        // Face-down for half the window, then back to upright.
        val flicker = DEBOUNCE_NANOS + 1
        s = FlipDetector.nextState(s, isFaceDown = true, timestampNanos = flicker)
        s = FlipDetector.nextState(s, isFaceDown = false, timestampNanos = flicker + DEBOUNCE_NANOS / 2)
        assertEquals(FlipDetector.Phase.Upright, s.phase)
        assertFalse(s.fired)
    }

    @Test
    fun `subsequent flip after rearm fires again`() {
        var s = FlipDetector.DetectorState.initial()

        // Seed Upright.
        s = FlipDetector.nextState(s, isFaceDown = false, timestampNanos = 0L)
        s = FlipDetector.nextState(s, isFaceDown = false, timestampNanos = DEBOUNCE_NANOS + 1)

        // First flip fires.
        val t1 = DEBOUNCE_NANOS + 1
        s = FlipDetector.nextState(s, isFaceDown = true, timestampNanos = t1)
        s = FlipDetector.nextState(s, isFaceDown = true, timestampNanos = t1 + DEBOUNCE_NANOS + 1)
        assertTrue(s.fired)
        assertEquals(FlipDetector.Phase.FaceDown, s.phase)

        // Re-arm: hold upright past the window.
        val t2 = t1 + DEBOUNCE_NANOS + 1
        s = FlipDetector.nextState(s, isFaceDown = false, timestampNanos = t2)
        s = FlipDetector.nextState(s, isFaceDown = false, timestampNanos = t2 + DEBOUNCE_NANOS + 1)
        assertEquals(FlipDetector.Phase.Upright, s.phase)
        assertFalse("rearm transition must not fire", s.fired)

        // Second flip fires again.
        val t3 = t2 + DEBOUNCE_NANOS + 1
        s = FlipDetector.nextState(s, isFaceDown = true, timestampNanos = t3)
        s = FlipDetector.nextState(s, isFaceDown = true, timestampNanos = t3 + DEBOUNCE_NANOS + 1)
        assertTrue(s.fired)
        assertEquals(FlipDetector.Phase.FaceDown, s.phase)
    }
}
