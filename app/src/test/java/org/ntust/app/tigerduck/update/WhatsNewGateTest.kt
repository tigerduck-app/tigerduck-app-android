package org.ntust.app.tigerduck.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.AppPreferences

class WhatsNewGateTest {

    @Test
    fun `does not show on a fresh install`() {
        assertFalse(
            WhatsNewGate.shouldShow(
                lastSeenVersionCode = AppPreferences.WHATS_NEW_UNSET,
                currentVersionCode = 21,
            )
        )
    }

    @Test
    fun `shows after an upgrade`() {
        assertTrue(WhatsNewGate.shouldShow(lastSeenVersionCode = 20, currentVersionCode = 21))
    }

    @Test
    fun `does not show when already on the current version`() {
        assertFalse(WhatsNewGate.shouldShow(lastSeenVersionCode = 21, currentVersionCode = 21))
    }

    @Test
    fun `does not show when last seen is somehow newer`() {
        assertFalse(WhatsNewGate.shouldShow(lastSeenVersionCode = 22, currentVersionCode = 21))
    }
}
