package org.ntust.app.tigerduck.ui.screen.mail

import androidx.compose.ui.graphics.Color
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
    fun `recipients are read into name and address`() {
        val list = MailRecipient.from(
            listOf(
                MailAddress("王大明", "a@mail.ntust.edu.tw"),
                MailAddress(" ", "b@gmail.com"),
                MailAddress("Mail Deliver System", ""),
                MailAddress(null, ""),
            ),
            ownAddress = null,
        )
        assertEquals(
            listOf(
                MailRecipient("王大明", "a@mail.ntust.edu.tw", isSelf = false),
                // A blank name counts as none.
                MailRecipient(null, "b@gmail.com", isSelf = false),
                // A bounce's name is kept rather than dropped; an entry with neither is.
                MailRecipient("Mail Deliver System", null, isSelf = false),
            ),
            list,
        )
        assertEquals(emptyList<MailRecipient>(), MailRecipient.from(emptyList(), ownAddress = null))
    }

    @Test
    fun `a recipient reads as name then address, or whichever of the two it has`() {
        val list = MailRecipient.from(
            listOf(MailAddress("王大明", "w@x.com"), MailAddress(null, "b@y.com"), MailAddress("Registry", "")),
            ownAddress = null,
        )
        assertEquals(listOf("王大明 <w@x.com>", "b@y.com", "Registry"), list.map { it.displayText })
    }

    @Test
    fun `the student's own address is the one marked, whatever its case`() {
        val list = MailRecipient.from(
            listOf(MailAddress(null, "B10000000@Mail.NTUST.edu.tw"), MailAddress(null, "b10000001@mail.ntust.edu.tw")),
            ownAddress = "b10000000@mail.ntust.edu.tw",
        )
        assertEquals(listOf(true, false), list.map { it.isSelf })
        assertEquals(listOf(false), MailRecipient.from(listOf(MailAddress(null, "a@x.com")), ownAddress = null).map { it.isSelf })
    }

    /** Collapsed, only the first is shown: the student's own address is that one, wherever the mail lists it. */
    @Test
    fun `the student's own address comes first, the rest as the mail lists them`() {
        val a = MailRecipient("A", "a@x.tw", isSelf = false)
        val b = MailRecipient("B", "b@x.tw", isSelf = false)
        val c = MailRecipient("C", "c@x.tw", isSelf = false)
        val me = MailRecipient(null, "me@x.tw", isSelf = true)
        assertEquals(listOf(me, a, b, c), headerOrder(listOf(a, b, me, c)))
        assertEquals(listOf(a, b, c), headerOrder(listOf(a, b, c)))
    }

    @Test
    fun `every shown recipient starts a line of its own, and the collapsed ones are counted`() {
        val a = MailRecipient("A", "a@x.tw", isSelf = false)
        val me = MailRecipient(null, "me@x.tw", isSelf = true)
        assertEquals("A <a@x.tw>,\nme@x.tw", recipientLine(listOf(a, me), 0, Color.Red).text)
        assertEquals("A <a@x.tw>  +2", recipientLine(listOf(a), 2, Color.Red).text)
    }

    @Test
    fun `only the student's own address is in the accent colour`() {
        val a = MailRecipient("A", "a@x.tw", isSelf = false)
        val me = MailRecipient(null, "me@x.tw", isSelf = true)
        val line = recipientLine(listOf(a, me), 0, Color.Red)
        val styled = line.spanStyles.map { line.text.substring(it.start, it.end) to it.item.color }
        assertEquals(listOf("me@x.tw" to Color.Red), styled)
    }
}
