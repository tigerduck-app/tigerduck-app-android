package org.ntust.app.tigerduck.ui.component

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AssignmentItemFormatAbsoluteTest {
    private lateinit var originalZone: TimeZone
    private lateinit var originalLocale: Locale

    @Before
    fun setPhoneZoneAndLocale() {
        originalZone = TimeZone.getDefault()
        originalLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreZoneAndLocale() {
        TimeZone.setDefault(originalZone)
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `zero seconds shows Taipei time without seconds`() {
        // 2026-11-07 23:59:00 Taipei (UTC+8) = 2026-11-07 07:59:00 Los Angeles (UTC-8).
        val due = Date.from(Instant.parse("2026-11-07T15:59:00Z"))
        assertEquals("11/7 23:59", formatAbsolute(due))
    }

    @Test
    fun `non-zero seconds shows Taipei time with seconds`() {
        // 2026-11-07 23:59:30 Taipei (UTC+8).
        val due = Date.from(Instant.parse("2026-11-07T15:59:30Z"))
        assertEquals("11/7 23:59:30", formatAbsolute(due))
    }

    @Test
    fun `cross-day boundary uses Taipei date and time`() {
        // 17:30 UTC is 01:30 the next day in Taipei, and still 09:30 the same day in Los Angeles.
        val due = Date.from(Instant.parse("2026-11-07T17:30:00Z"))
        assertEquals("11/8 01:30", formatAbsolute(due))
    }
}
