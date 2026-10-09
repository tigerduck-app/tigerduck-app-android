package org.ntust.app.tigerduck.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.SchoolDataFreshness.AUTO_RETRY_AFTER_MS
import org.ntust.app.tigerduck.data.SchoolDataFreshness.BACKGROUND_MAX_AGE_MS
import org.ntust.app.tigerduck.data.SchoolDataFreshness.FOREGROUND_MAX_AGE_MS

class SchoolDataFreshnessTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Test
    fun `data never fetched is stale`() {
        assertTrue(SchoolDataFreshness.isStale(0L, now, FOREGROUND_MAX_AGE_MS))
    }

    @Test
    fun `data turns stale exactly at its maximum age`() {
        assertFalse(SchoolDataFreshness.isStale(now - FOREGROUND_MAX_AGE_MS + 1, now, FOREGROUND_MAX_AGE_MS))
        assertTrue(SchoolDataFreshness.isStale(now - FOREGROUND_MAX_AGE_MS, now, FOREGROUND_MAX_AGE_MS))
    }

    @Test
    fun `a stamp from the future reads as stale`() {
        assertTrue(SchoolDataFreshness.isStale(now + minute, now, FOREGROUND_MAX_AGE_MS))
    }

    @Test
    fun `the worker tolerates older data than the foreground`() {
        val twentyMinutesAgo = now - 20 * minute
        assertTrue(SchoolDataFreshness.isStale(twentyMinutesAgo, now, FOREGROUND_MAX_AGE_MS))
        assertFalse(SchoolDataFreshness.isStale(twentyMinutesAgo, now, BACKGROUND_MAX_AGE_MS))
    }

    @Test
    fun `fresh data is not fetched on its own`() {
        assertFalse(SchoolDataFreshness.shouldAutoRefresh(now - minute, lastAttemptMs = 0L, now))
    }

    @Test
    fun `stale data is fetched when the screen has not tried yet`() {
        assertTrue(SchoolDataFreshness.shouldAutoRefresh(now - 20 * minute, lastAttemptMs = 0L, now))
    }

    @Test
    fun `fresh data whose cache is gone is fetched anyway`() {
        assertTrue(
            SchoolDataFreshness.shouldAutoRefresh(now - minute, lastAttemptMs = 0L, now, cacheEmpty = true)
        )
    }

    @Test
    fun `an empty cache still waits out a try that did not land`() {
        assertFalse(
            SchoolDataFreshness.shouldAutoRefresh(now - minute, now - minute, now, cacheEmpty = true)
        )
    }

    @Test
    fun `a try that did not land holds the next one back for a while`() {
        val stale = now - 20 * minute
        assertFalse(SchoolDataFreshness.shouldAutoRefresh(stale, now - minute, now))
        assertTrue(SchoolDataFreshness.shouldAutoRefresh(stale, now - AUTO_RETRY_AFTER_MS, now))
    }
}
