package org.ntust.app.tigerduck.ui.screen.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MailDiagnosticsTest {
    @Test
    fun `entries render in Taipei time`() {
        assertEquals("09/15 18:00:42 · WORKER · Failed:Network", MailDiagnostics.format("1789466442000|WORKER|Failed:Network"))
        assertNull(MailDiagnostics.format("not|a"))
        assertNull(MailDiagnostics.format("x|ALARM|NoChange"))
    }
}
