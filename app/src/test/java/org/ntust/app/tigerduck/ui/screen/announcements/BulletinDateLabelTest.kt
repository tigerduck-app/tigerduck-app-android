package org.ntust.app.tigerduck.ui.screen.announcements

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

class BulletinDateLabelTest {
    private lateinit var originalZone: TimeZone

    @Before
    fun setPhoneZone() {
        // A student's phone set to Los Angeles still shows Taipei's date for a bulletin.
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun `bulletin date is shown in Taipei time, not the device zone`() {
        // 17:30 UTC is 01:30 the next day in Taipei, and still the previous day in Los Angeles.
        assertEquals("11/1", bulletinDateLabel("2026-10-31T17:30:00Z"))
    }

    @Test
    fun `unparseable date falls back to the text before the T`() {
        assertEquals("not a date", bulletinDateLabel("not a date"))
    }
}
