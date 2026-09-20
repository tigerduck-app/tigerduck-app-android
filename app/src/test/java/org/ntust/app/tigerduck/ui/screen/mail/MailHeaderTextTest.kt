package org.ntust.app.tigerduck.ui.screen.mail

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.mail.model.MailAddress

class MailHeaderTextTest {

    @Test
    fun `name and address show on two lines`() {
        assertEquals("王小明" to "b10000001@mail.ntust.edu.tw",
            senderLines(MailAddress("王小明", "b10000001@mail.ntust.edu.tw")))
    }

    @Test
    fun `no display name shows the address once, not twice`() {
        assertEquals("noreply@example.com" to null,
            senderLines(MailAddress(null, "noreply@example.com")))
    }

    @Test
    fun `a blank display name counts as no name`() {
        assertEquals("noreply@example.com" to null,
            senderLines(MailAddress("   ", "noreply@example.com")))
    }

    @Test
    fun `a bounce keeps its name and draws no address line`() {
        assertEquals("Mail Deliver System" to null,
            senderLines(MailAddress("Mail Deliver System", "")))
    }

    @Test
    fun `no sender at all`() {
        assertEquals(null to null, senderLines(null))
    }

    @Test
    fun `recipients show addresses, iOS-style`() {
        val list = listOf(
            MailAddress("王小明", "a@x.tw"),
            MailAddress(null, "b@x.tw"),
        )
        assertEquals("a@x.tw, b@x.tw", recipientText(list))
    }

    @Test
    fun `a non-routable recipient falls back to its name instead of an empty slot`() {
        val list = listOf(MailAddress("Mail Deliver System", ""), MailAddress(null, "b@x.tw"))
        assertEquals("Mail Deliver System, b@x.tw", recipientText(list))
    }

    @Test
    fun `a recipient with neither name nor address is dropped`() {
        val list = listOf(MailAddress(null, ""), MailAddress(null, "b@x.tw"))
        assertEquals("b@x.tw", recipientText(list))
    }
}
