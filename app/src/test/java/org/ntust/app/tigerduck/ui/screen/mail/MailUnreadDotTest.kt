package org.ntust.app.tigerduck.ui.screen.mail

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The unread dot before a mail's sender grows with the sender's text, as the iPhone's does (#752). */
class MailUnreadDotTest {

    @Test
    fun `seven dp beside the sender at the default text size`() {
        assertEquals(7.dp, unreadDotSize(Density(density = 2f, fontScale = 1f), 12.sp))
    }

    @Test
    fun `twice the size beside a sender twice the size`() {
        assertEquals(14.dp, unreadDotSize(Density(density = 2f, fontScale = 2f), 12.sp))
    }
}
