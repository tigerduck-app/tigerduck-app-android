package org.ntust.app.tigerduck.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The course-reset watermark parser. 0 is the load-bearing value: it means
 * "no information", and applyCourseReset skips the reset rather than
 * wiping local courses, so every unparseable shape has to land on it.
 */
class ParseIsoTimestampTest {

    @Test
    fun `ISO timestamps parse as UTC, and anything unparseable reads as zero`() {
        assertEquals(0L, parseIsoTimestamp(""))
        assertEquals(0L, parseIsoTimestamp("not a date"))
        assertEquals(1_700_000_000_000L, parseIsoTimestamp("2023-11-14T22:13:20"))
        // A fractional part is truncated rather than rejected.
        assertEquals(
            1_700_000_000_000L,
            parseIsoTimestamp("2023-11-14T22:13:20.123456Z"),
        )
    }
}
