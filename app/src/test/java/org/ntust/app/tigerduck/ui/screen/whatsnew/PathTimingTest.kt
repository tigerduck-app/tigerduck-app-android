package org.ntust.app.tigerduck.ui.screen.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PathTimingTest {

    @Test
    fun `the way to a feature goes at one pace, however long the feature runs`() {
        val short = PathTiming(stops = 1, featureMillis = 3_000)
        val long = PathTiming(stops = 2, featureMillis = 8_000)

        assertEquals(short.millis(short.more), long.millis(long.more), 1f)
        assertEquals(short.millis(short.stop), long.millis(long.stop), 1f)
        // Long enough to see the list scroll and the finger tap a row.
        assertTrue(short.millis(short.stop) >= 2_000f)
        assertEquals(3_000f, short.millis(short.end - short.pathEnd), 1f)
        assertEquals(8_000f, long.millis(long.end - long.pathEnd), 1f)
    }

    private fun PathTiming.millis(part: Float) = part * loopMillis
}
