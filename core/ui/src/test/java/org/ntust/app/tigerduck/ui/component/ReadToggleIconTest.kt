package org.ntust.app.tigerduck.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadToggleIconTest {

    @Test
    fun `an unread item offers to mark it read`() {
        assertEquals(Icons.Filled.MarkEmailRead, readToggleIcon(isRead = false))
    }

    @Test
    fun `a read item offers to mark it unread`() {
        assertEquals(Icons.Filled.MarkEmailUnread, readToggleIcon(isRead = true))
    }
}
