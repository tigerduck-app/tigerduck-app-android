package org.ntust.app.tigerduck.ui.screen.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.ui.screen.mail.SchoolMailMessageViewModel.ViewMode

class MailLightModeTest {

    @Test
    fun `a dark app offers light mode over the formatted view of an HTML mail`() {
        assertTrue(offersLightMode(isDark = true, mode = ViewMode.FORMATTED, canFormat = true))
    }

    @Test
    fun `a light app does not -- its surface already is a light page`() {
        assertFalse(offersLightMode(isDark = false, mode = ViewMode.FORMATTED, canFormat = true))
    }

    @Test
    fun `plain text and source are the app's own text, with no page to change`() {
        assertFalse(offersLightMode(isDark = true, mode = ViewMode.PLAIN, canFormat = true))
        assertFalse(offersLightMode(isDark = true, mode = ViewMode.SOURCE, canFormat = true))
    }

    @Test
    fun `a mail with no HTML part has no page at all`() {
        assertFalse(offersLightMode(isDark = true, mode = ViewMode.FORMATTED, canFormat = false))
    }
}
