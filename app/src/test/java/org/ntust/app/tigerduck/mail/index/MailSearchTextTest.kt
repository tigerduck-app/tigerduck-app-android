package org.ntust.app.tigerduck.mail.index

import org.junit.Assert.assertEquals
import org.junit.Test

class MailSearchTextTest {

    @Test
    fun `the plain text, with its line breaks as spaces`() {
        assertEquals("期中考 時間 改到 週五", MailSearchText.of("期中考\n時間\r\n  改到\t週五\n", "<p>ignored</p>"))
    }

    @Test
    fun `a mail with only HTML is read for its text, not its tags`() {
        assertEquals("期末報告 截止", MailSearchText.of(null, "<html><body><p>期末<b>報告</b></p><div>截止</div></body></html>"))
        assertEquals("期末", MailSearchText.of("  \n", "<p>期末</p>"))
    }

    @Test
    fun `a mail with no text searches as empty`() {
        assertEquals("", MailSearchText.of(null, null))
    }

    @Test
    fun `a long mail is kept to its first pages`() {
        assertEquals(MailSearchText.MAX_CHARS, MailSearchText.of("字".repeat(MailSearchText.MAX_CHARS + 10), null).length)
    }
}
