package org.ntust.app.tigerduck.mail.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MailFoldersTest {
    /** What LIST returned for a real account (spec §1.2), as Angus reports it: decoded. */
    private val decoded = listOf(
        "INBOX", "寄件備份匣", "草稿匣", "回收筒", "廣告信匣", "Sent Messages", "Drafts", "Sent", "Junk",
        "Trash", "Archive", "Deleted Messages", "Moodle 課程討論區", "Moodle Login",
    )

    @Test
    fun `native Mail2000 folders win over look-alikes other clients created`() {
        val r = MailFolders.resolve(decoded)
        assertEquals("INBOX", r.nameOf(SpecialFolder.INBOX))
        assertEquals("寄件備份匣", r.nameOf(SpecialFolder.SENT))
        assertEquals("草稿匣", r.nameOf(SpecialFolder.DRAFTS))
        assertEquals("廣告信匣", r.nameOf(SpecialFolder.JUNK))
        assertEquals("回收筒", r.nameOf(SpecialFolder.TRASH))
        assertEquals(SpecialFolder.TRASH, r.kindOf("回收筒"))
        assertNull(r.kindOf("Trash"))
        assertEquals(
            listOf("Archive", "Deleted Messages", "Drafts", "Junk", "Moodle Login", "Moodle 課程討論區", "Sent", "Sent Messages", "Trash"),
            r.others,
        )
    }

    @Test
    fun `raw modified UTF-7 names are recognised too`() {
        val r = MailFolders.resolve(listOf("INBOX", "&W8RO9lCZTv1TIw-", "&g0l6P1Mj-", "&Vt5lNntS-", "&XuNUSk,hUyM-"))
        assertEquals("&W8RO9lCZTv1TIw-", r.nameOf(SpecialFolder.SENT))
        assertEquals("&Vt5lNntS-", r.nameOf(SpecialFolder.TRASH))
    }

    @Test
    fun `a missing native folder stays missing and is never substituted`() {
        val r = MailFolders.resolve(listOf("INBOX", "Sent", "Trash"))
        assertNull(r.nameOf(SpecialFolder.SENT))
        assertNull(r.nameOf(SpecialFolder.TRASH))
        assertEquals(listOf("Sent", "Trash"), r.others)
    }
}
