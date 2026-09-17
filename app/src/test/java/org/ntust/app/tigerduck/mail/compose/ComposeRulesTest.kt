package org.ntust.app.tigerduck.mail.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailSummary
import java.util.Date

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
        // "B, C" contains a comma (an RFC 5322 special) so it must round-trip through quoting.
        assertEquals("a@x.tw, \"B, C\" <b@y.tw>", ComposeRules.formatRecipients(parsed.addresses))
    }

    @Test
    fun `formatRecipients output round-trips through parseRecipients`() {
        val list = listOf(
            MailAddress("B, C", "b@y.tw"),
            MailAddress("a \"quoted\" name", "q@y.tw"),
            // Backslashes are quoted-pairs too: an unescape that chains `replace` calls turns
            // the `\` of an escaped backslash back into an escape for whatever follows it.
            MailAddress("Wang\\Da", "slash@y.tw"),
            MailAddress("a backslash\\ then a \"quote\"", "mix@y.tw"),
            MailAddress("ends with a backslash\\", "tail@y.tw"),
            MailAddress("王小明", "wang@y.tw"),
            MailAddress(null, "bare@y.tw"),
        )
        val parsed = ComposeRules.parseRecipients(ComposeRules.formatRecipients(list))
        assertEquals(list, parsed.addresses)
        assertTrue(parsed.invalid.isEmpty())
    }

    @Test
    fun `a non-ASCII address is reported invalid -- the school server has no SMTPUTF8`() {
        val parsed = ComposeRules.parseRecipients("中文@x.tw, 王小明 <a@中文.tw>, ok@x.tw")
        assertEquals(listOf("ok@x.tw"), parsed.addresses.map { it.address })
        assertEquals(listOf("中文@x.tw", "王小明 <a@中文.tw>"), parsed.invalid)
    }

    @Test
    fun `size limit counts base64 growth`() {
        assertTrue(ComposeRules.fitsSizeLimit("hi", listOf(30L * 1024 * 1024)))
        assertFalse(ComposeRules.fitsSizeLimit("hi", listOf(38L * 1024 * 1024)))
        assertFalse(ComposeRules.fitsSizeLimit("hi", listOf(20L * 1024 * 1024, 20L * 1024 * 1024)))
    }

    @Test
    fun `an already-encoded attachment size is added as-is, not grown again`() {
        // 49 MB of already-encoded bytes fits: growing it again (as if it were still raw) would
        // put it near 67 MB and wrongly reject it.
        assertTrue(ComposeRules.fitsSizeLimit("hi", emptyList(), listOf(49L * 1024 * 1024)))
        assertFalse(ComposeRules.fitsSizeLimit("hi", emptyList(), listOf(51L * 1024 * 1024)))
        // Mixing a raw (locally picked) attachment with an encoded (forwarded) one applies
        // growth to only the raw one.
        assertTrue(ComposeRules.fitsSizeLimit("hi", listOf(1024L), listOf(49L * 1024 * 1024)))
    }

    @Test
    fun `size estimate bounds the real quoted-printable encoding of a large CJK body`() {
        val body = "測試郵件內容".repeat(8_000) // 48,000 UTF-16 chars, ~144,000 UTF-8 bytes
        assertEncodedEstimateCoversRealMessage(body)
    }

    @Test
    fun `size estimate bounds the real quoted-printable encoding of an ascii body`() {
        val body = "The quick brown fox jumps over the lazy dog.\n".repeat(2_000)
        assertEncodedEstimateCoversRealMessage(body)
    }

    private fun assertEncodedEstimateCoversRealMessage(body: String) {
        val builder = MessageBuilder(newId = { "fixed" }, now = { Date(0) })
        val mail = OutgoingMail(
            from = MailAddress(null, "b10000001@mail.ntust.edu.tw"),
            to = listOf(MailAddress(null, "a@x.tw")),
            cc = emptyList(),
            bcc = emptyList(),
            subject = "s",
            body = body,
        )
        val actual = builder.build(mail).toBytes().size.toLong()
        val estimate = ComposeRules.estimateEncodedSize(body, emptyList())
        assertTrue("estimate $estimate must be >= actual $actual", estimate >= actual)
    }
}
