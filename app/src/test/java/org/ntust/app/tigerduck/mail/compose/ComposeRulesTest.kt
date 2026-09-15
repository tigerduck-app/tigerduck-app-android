package org.ntust.app.tigerduck.mail.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailSummary

class ComposeRulesTest {
    private fun summary(
        from: MailAddress? = MailAddress("A", "a@x.tw"),
        replyTo: List<MailAddress> = emptyList(),
        to: List<MailAddress> = emptyList(),
        cc: List<MailAddress> = emptyList(),
        messageId: String? = "<m1@x>",
        references: String? = null,
    ) = MailSummary(1, from, replyTo, to, cc, "s", null, null, MailFlags.NONE, 0, false, messageId, null, references)

    @Test
    fun `subject prefixes are added once`() {
        assertEquals("Re: 期中考", ComposeRules.replySubject("期中考"))
        assertEquals("RE: 期中考", ComposeRules.replySubject("RE: 期中考"))
        assertEquals("Re：期中考", ComposeRules.replySubject("Re：期中考"))
        assertEquals("Fwd: 期中考", ComposeRules.forwardSubject(" 期中考 "))
        assertEquals("FW: 期中考", ComposeRules.forwardSubject("FW: 期中考"))
    }

    @Test
    fun `quoting prefixes every line`() {
        assertEquals("\n\n於 9/15 10:00，A 寫道：\n> one\n> two", ComposeRules.quote("one\ntwo", "於 9/15 10:00，A 寫道："))
    }

    @Test
    fun `reply goes to Reply-To, else From`() {
        assertEquals(listOf(MailAddress("A", "a@x.tw")), ComposeRules.replyRecipients(summary()))
        assertEquals(listOf(MailAddress(null, "r@x.tw")), ComposeRules.replyRecipients(summary(replyTo = listOf(MailAddress(null, "r@x.tw")))))
    }

    @Test
    fun `reply all copies To and Cc without me or duplicates`() {
        val s = summary(
            to = listOf(MailAddress(null, "ME@mail.ntust.edu.tw"), MailAddress(null, "b@x.tw"), MailAddress(null, "a@x.tw")),
            cc = listOf(MailAddress(null, "B@x.tw"), MailAddress(null, "c@x.tw")),
        )
        val (to, cc) = ComposeRules.replyAllRecipients(s, "me@mail.ntust.edu.tw")
        assertEquals(listOf("a@x.tw"), to.map { it.address })
        assertEquals(listOf("b@x.tw", "c@x.tw"), cc.map { it.address })
    }

    @Test
    fun `references chain`() {
        assertEquals("<r0@x> <m1@x>", ComposeRules.references(summary(references = "<r0@x>")))
        assertEquals("<m1@x>", ComposeRules.references(summary()))
        assertNull(ComposeRules.references(summary(messageId = null)))
    }

    @Test
    fun `recipient parsing reports what it could not read`() {
        val parsed = ComposeRules.parseRecipients("a@x.tw; \"B, C\" <b@y.tw>,  , nope, A@X.tw")
        assertEquals(listOf("a@x.tw", "b@y.tw"), parsed.addresses.map { it.address })
        assertEquals(listOf("nope"), parsed.invalid)
        assertEquals("a@x.tw, B, C <b@y.tw>", ComposeRules.formatRecipients(parsed.addresses))
    }

    @Test
    fun `size limit counts base64 growth`() {
        assertTrue(ComposeRules.fitsSizeLimit("hi", listOf(30L * 1024 * 1024)))
        assertFalse(ComposeRules.fitsSizeLimit("hi", listOf(38L * 1024 * 1024)))
        assertFalse(ComposeRules.fitsSizeLimit("hi", listOf(20L * 1024 * 1024, 20L * 1024 * 1024)))
    }
}
