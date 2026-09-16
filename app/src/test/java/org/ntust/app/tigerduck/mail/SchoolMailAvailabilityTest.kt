package org.ntust.app.tigerduck.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchoolMailAvailabilityTest {
    @Test
    fun `hidden in release until released, shown in debug behind the toggle`() {
        assertFalse(SchoolMailAvailability.isVisible(devToggle = true, released = false, debug = false))
        assertTrue(SchoolMailAvailability.isVisible(devToggle = true, released = false, debug = true))
        assertFalse(SchoolMailAvailability.isVisible(devToggle = false, released = false, debug = true))
        assertTrue(SchoolMailAvailability.isVisible(devToggle = false, released = true, debug = false))
    }
}
