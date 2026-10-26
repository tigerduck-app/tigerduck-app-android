package org.ntust.app.tigerduck.ui.screen.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.mail.index.MailIndexProgress
import java.util.Locale

class IndexProgressTextTest {

    @Test
    fun `the mail downloaded, of how many were lacking`() {
        assertEquals("0/12", indexProgressText(MailIndexProgress(0, 12), Locale.US))
    }

    @Test
    fun `large counts are grouped as the language groups them`() {
        assertEquals("1,234/5,678", indexProgressText(MailIndexProgress(1_234, 5_678), Locale.US))
        assertEquals("1.234/5.678", indexProgressText(MailIndexProgress(1_234, 5_678), Locale.GERMANY))
    }

    @Test
    fun `no number while the download still counts`() {
        assertNull(indexProgressText(MailIndexProgress.COUNTING, Locale.US))
    }
}
