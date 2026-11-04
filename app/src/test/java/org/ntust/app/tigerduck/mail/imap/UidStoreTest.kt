package org.ntust.app.tigerduck.mail.imap

import org.junit.Assert.assertEquals
import org.junit.Test

/** The `UID STORE` a batch of mark-reads sends: its UID set names those UIDs and no other. */
class UidStoreTest {
    @Test
    fun `consecutive UIDs make a range, and the set comes in ascending order`() {
        assertEquals("3,5:7,10", uidSet(listOf(10L, 6L, 3L, 5L, 7L)))
        assertEquals("42", uidSet(listOf(42L)))
        assertEquals("1:2", uidSet(listOf(2L, 1L, 2L)))
    }

    @Test
    fun `a range takes no UID between two that are not in the batch`() {
        assertEquals("1,3,5", uidSet(listOf(5L, 1L, 3L)))
    }

    @Test
    fun `the command marks read with +FLAGS and unread with -FLAGS`() {
        assertEquals("UID STORE 1,3:5 +FLAGS (\\Seen)", seenStore(listOf(4L, 1L, 3L, 5L), seen = true))
        assertEquals("UID STORE 8 -FLAGS (\\Seen)", seenStore(listOf(8L), seen = false))
    }
}
