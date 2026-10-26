package org.ntust.app.tigerduck.push

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * What the TigerSync status card records as its latest error when a
 * registration or a push preference update fails. An exception without a
 * message used to record its class name, which a release build shortens to
 * R8's name for it, such as "v44"; it now records "", which the card shows as
 * the generic "Failed" (LatestErrorTextTest).
 */
class PushDiagnosticTextTest {

    @Test
    fun `a failure without a message records no text of its own`() {
        assertEquals("", IOException().diagnosticText())
        assertEquals("", IOException("  ").diagnosticText())
    }

    @Test
    fun `a failure's own message is still what it records`() {
        assertEquals("timeout", IOException("timeout").diagnosticText())
    }
}
