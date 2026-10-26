package org.ntust.app.tigerduck.ui.screen.mail

import androidx.compose.ui.platform.AccessibilityManager
import org.junit.Assert.assertEquals
import org.junit.Test

class SendHoldTest {

    /** The "Time to take action" a student set in the accessibility settings, as the platform answers it. */
    private fun asking(millis: Long) = object : AccessibilityManager {
        override fun calculateRecommendedTimeoutMillis(
            originalTimeoutMillis: Long,
            containsIcons: Boolean,
            containsText: Boolean,
            containsControls: Boolean,
        ): Long = maxOf(originalTimeoutMillis, if (containsControls) millis else 0)
    }

    @Test
    fun `the hold is five seconds, or as long as the accessibility settings ask for an action`() {
        assertEquals(SchoolMailComposeViewModel.SEND_HOLD_MS, sendHoldMillis(null))
        assertEquals(SchoolMailComposeViewModel.SEND_HOLD_MS, sendHoldMillis(asking(0)))
        assertEquals(30_000L, sendHoldMillis(asking(30_000)))
    }
}
