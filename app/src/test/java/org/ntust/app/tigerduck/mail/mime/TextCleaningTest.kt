package org.ntust.app.tigerduck.mail.mime

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCleaningTest {
    @Test
    fun `bidi controls are stripped (spec A-3)`() {
        val spoof = "invoice‮fdp.exe"
        assertEquals("invoicefdp.exe", TextCleaning.stripBidi(spoof))
        for (c in listOf('‪', '‫', '‬', '‭', '‮', '⁦', '⁧', '⁨', '⁩', '‎', '‏', '؜')) {
            assertEquals("ab", TextCleaning.stripBidi("a${c}b"))
        }
    }

    @Test
    fun `clean also drops control characters and collapses whitespace`() {
        assertEquals("a b c", TextCleaning.clean("  a\r\n\tb  c "))
        assertEquals("", TextCleaning.clean(null))
    }
}
