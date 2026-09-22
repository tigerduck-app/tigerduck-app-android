package org.ntust.app.tigerduck.mail.mime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MailCharsetsTest {
    private val big5 = charset("Big5")

    @Test
    fun `big5 labels decode as Big5-HKSCS`() {
        val bytes = "中文郵件".toByteArray(big5)
        for (label in listOf("big5", "BIG5", "big-5", "cn-big5", "x-x-big5", "\"big5\"")) {
            assertEquals(label, "中文郵件", MailCharsets.decode(bytes, label))
        }
        assertEquals("Big5-HKSCS", MailCharsets.resolve("big5")!!.name())
    }

    @Test
    fun `an HKSCS-only character survives a big5 label`() {
        // 0x8840 exists only in HKSCS; plain Big5 would turn it into U+FFFD.
        val decoded = MailCharsets.decode(byteArrayOf(0x88.toByte(), 0x40), "big5")
        assertEquals(1, decoded.length)
        assertFalse(decoded.contains('�'))
    }

    @Test
    fun `gb labels decode as GB18030`() {
        val bytes = "简体中文".toByteArray(charset("GB2312"))
        assertEquals("简体中文", MailCharsets.decode(bytes, "gb2312"))
        assertEquals("GB18030", MailCharsets.resolve("x-gbk")!!.name())
    }

    @Test
    fun `unlabelled text tries UTF-8, then Big5-HKSCS, then ISO-8859-1`() {
        assertEquals("中文", MailCharsets.decode("中文".toByteArray(Charsets.UTF_8), null))
        assertEquals("中文", MailCharsets.decode("中文".toByteArray(big5), ""))
        val junk = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x80.toByte())
        assertEquals(3, MailCharsets.decode(junk, "x-unknown-charset").length)
    }
}
