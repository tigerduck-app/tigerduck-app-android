package org.ntust.app.tigerduck.ui.screen.mail

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class MailDateFormatTest {
    private val sent = Instant.parse("2026-09-15T10:00:42Z") // 18:00:42 in Taipei

    @Test
    fun `today shows the time, other days the date, in Taipei time`() {
        assertEquals("18:00", MailDateFormat.short(sent, now = Instant.parse("2026-09-15T15:00:00Z")))
        assertEquals("9/15", MailDateFormat.short(sent, now = Instant.parse("2026-09-15T16:30:00Z"))) // already 9/16 in Taipei
        assertEquals("", MailDateFormat.short(null))
        assertEquals("2026/09/15 18:00", MailDateFormat.full(sent))
    }
}
