package org.ntust.app.tigerduck.mail.mime

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCleaningTest {
    @Test
    fun `bidi controls are stripped (spec A-3)`() {
        val spoof = "invoice\u202efdp.exe"
        assertEquals("invoicefdp.exe", TextCleaning.stripBidi(spoof))
        for (c in listOf('\u202a', '\u202b', '\u202c', '\u202d', '\u202e', '\u2066', '\u2067', '\u2068', '\u2069', '\u200e', '\u200f', '\u061c')) {
            assertEquals("ab", TextCleaning.stripBidi("a${c}b"))
        }
    }

    @Test
    fun `clean also drops control characters and collapses whitespace`() {
        assertEquals("a b c", TextCleaning.clean("  a\r\n\tb\u0007  c "))
        assertEquals("", TextCleaning.clean(null))
    }
}
