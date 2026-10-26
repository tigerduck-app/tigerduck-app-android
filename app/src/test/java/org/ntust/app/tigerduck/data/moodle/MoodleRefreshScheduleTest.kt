package org.ntust.app.tigerduck.data.moodle

import org.junit.Assert.assertEquals
import org.junit.Test

class MoodleRefreshScheduleTest {
    private val ttl = 300_000L

    @Test
    fun `nothing fetched yet is due at once`() {
        assertEquals(0, MoodleRefreshSchedule.waitMs(null, null, ttl, 1_000_000))
    }

    @Test
    fun `fresh data waits out the rest of its interval`() {
        assertEquals(200_000, MoodleRefreshSchedule.waitMs(900_000, null, ttl, 1_000_000))
    }

    @Test
    fun `a failed try holds the next one back a full interval`() {
        assertEquals(ttl, MoodleRefreshSchedule.waitMs(100_000, 1_000_000, ttl, 1_000_000))
    }

    @Test
    fun `data stamped ahead of the clock counts as old`() {
        assertEquals(0, MoodleRefreshSchedule.waitMs(5_000_000, null, ttl, 1_000_000))
    }
}
