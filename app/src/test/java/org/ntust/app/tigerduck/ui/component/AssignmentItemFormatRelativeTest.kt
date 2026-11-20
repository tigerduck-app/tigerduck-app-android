package org.ntust.app.tigerduck.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class AssignmentItemFormatRelativeTest {
    private val now = Date(1_794_000_000_000L)

    private fun dueOffsetMs(offsetMs: Long): Date = Date(now.time + offsetMs)

    @Test
    fun `1 second before deadline rounds up to 1 minute later`() {
        assertEquals(
            RelativeTime(1, RelativeTimeUnit.MINUTES, isPast = false),
            resolveRelativeTime(dueOffsetMs(1_000L), now),
        )
    }

    @Test
    fun `59 seconds before deadline rounds up to 1 minute later`() {
        assertEquals(
            RelativeTime(1, RelativeTimeUnit.MINUTES, isPast = false),
            resolveRelativeTime(dueOffsetMs(59_000L), now),
        )
    }

    @Test
    fun `60 seconds before deadline stays 1 minute later`() {
        assertEquals(
            RelativeTime(1, RelativeTimeUnit.MINUTES, isPast = false),
            resolveRelativeTime(dueOffsetMs(60_000L), now),
        )
    }

    @Test
    fun `61 seconds before deadline rounds up to 2 minutes later`() {
        assertEquals(
            RelativeTime(2, RelativeTimeUnit.MINUTES, isPast = false),
            resolveRelativeTime(dueOffsetMs(61_000L), now),
        )
    }

    @Test
    fun `5 minutes before deadline stays in minutes rather than hours`() {
        assertEquals(
            RelativeTime(5, RelativeTimeUnit.MINUTES, isPast = false),
            resolveRelativeTime(dueOffsetMs(5 * 60_000L), now),
        )
    }

    @Test
    fun `59 minutes 30 seconds before deadline promotes to 1 hour later`() {
        assertEquals(
            RelativeTime(1, RelativeTimeUnit.HOURS, isPast = false),
            resolveRelativeTime(dueOffsetMs(59 * 60_000L + 30_000L), now),
        )
    }

    @Test
    fun `at exact deadline shows 1 minute ago`() {
        assertEquals(
            RelativeTime(1, RelativeTimeUnit.MINUTES, isPast = true),
            resolveRelativeTime(dueOffsetMs(0L), now),
        )
    }

    @Test
    fun `1 second past deadline shows at least 1 minute ago`() {
        assertEquals(
            RelativeTime(1, RelativeTimeUnit.MINUTES, isPast = true),
            resolveRelativeTime(dueOffsetMs(-1_000L), now),
        )
    }

    @Test
    fun `59 seconds past deadline shows at least 1 minute ago`() {
        assertEquals(
            RelativeTime(1, RelativeTimeUnit.MINUTES, isPast = true),
            resolveRelativeTime(dueOffsetMs(-59_000L), now),
        )
    }

    @Test
    fun `2 minutes past deadline shows 2 minutes ago`() {
        assertEquals(
            RelativeTime(2, RelativeTimeUnit.MINUTES, isPast = true),
            resolveRelativeTime(dueOffsetMs(-2 * 60_000L), now),
        )
    }

    @Test
    fun `hours and days past or ahead step units naturally`() {
        assertEquals(
            RelativeTime(2, RelativeTimeUnit.HOURS, isPast = true),
            resolveRelativeTime(dueOffsetMs(-2 * 3_600_000L), now),
        )
        assertEquals(
            RelativeTime(4, RelativeTimeUnit.DAYS, isPast = false),
            resolveRelativeTime(dueOffsetMs(4 * 86_400_000L), now),
        )
    }
}
