package org.ntust.app.tigerduck.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Grey never wins" is the whole reason this is a worst-of and not a
 * first-of: three sources sit behind one mark now, and a backend nobody has
 * contacted yet must not be able to report the page as healthy — or, when
 * switched off, as broken.
 */
class SyncStatusDotTest {

    @Test
    fun `a single failure decides the dot, whatever else is fine`() {
        assertEquals(
            ServerStatus.FAILED,
            summarize(listOf(ServerStatus.OK, ServerStatus.FAILED, ServerStatus.OK)),
        )
    }

    @Test
    fun `unknown never outranks a source that actually answered`() {
        assertEquals(
            ServerStatus.OK,
            summarize(listOf(ServerStatus.UNKNOWN, ServerStatus.OK)),
        )
        assertEquals(
            ServerStatus.FAILED,
            summarize(listOf(ServerStatus.UNKNOWN, ServerStatus.FAILED)),
        )
    }

    @Test
    fun `unknown wins only when nothing else is known`() {
        assertEquals(ServerStatus.UNKNOWN, summarize(listOf(ServerStatus.UNKNOWN)))
        assertEquals(ServerStatus.UNKNOWN, summarize(emptyList()))
    }

    /**
     * The dot is hidden until the auth collector in `TigerDuckApp` reports,
     * which is the safe way round: a `true` default would paint a header on
     * a signed-out launch using whatever the previous account left behind.
     */
    @Test
    fun `signed-in starts false so a launch cannot flash a stale dot`() {
        assertEquals(false, ServerStatusTracker.signedIn.value)
    }

    /**
     * Logout clears the map, and the tracker is a process-wide singleton —
     * so a status set before logout has to actually go, not merely stop
     * being drawn while it waits for the next account to inherit it.
     */
    @Test
    fun `reset drops a status the next account would otherwise inherit`() {
        ServerStatusTracker.set(ServerStatus.OK, ServerKind.MOODLE)
        assertEquals(ServerStatus.OK, ServerStatusTracker.status(ServerKind.MOODLE))

        ServerStatusTracker.reset()

        assertEquals(ServerStatus.UNKNOWN, ServerStatusTracker.status(ServerKind.MOODLE))
    }

    // --- dataAge -------------------------------------------------------------

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Test
    fun `data never fetched has no age`() {
        assertEquals(DataAge.Never, dataAge(0L, now))
    }

    @Test
    fun `under a minute reads as just now`() {
        assertEquals(DataAge.JustNow, dataAge(now - minute + 1, now))
    }

    @Test
    fun `each step counts whole units, rounding down`() {
        assertEquals(DataAge.Minutes(1), dataAge(now - minute, now))
        assertEquals(DataAge.Minutes(59), dataAge(now - 60 * minute + 1, now))
        assertEquals(DataAge.Hours(1), dataAge(now - 60 * minute, now))
        assertEquals(DataAge.Hours(23), dataAge(now - 24 * 60 * minute + 1, now))
        assertEquals(DataAge.Days(2), dataAge(now - 2 * 24 * 60 * minute, now))
    }

    @Test
    fun `a stamp ahead of the clock reads as just now`() {
        assertEquals(DataAge.JustNow, dataAge(now + 5 * minute, now))
    }
}
