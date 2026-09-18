package org.ntust.app.tigerduck.ui.screen.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.mail.mailSummary
import org.ntust.app.tigerduck.mail.model.MailAddress

class ComposePrefillTest {
    private val labels = ComposePrefill.Labels(
        quoteHeader = { date, sender -> "On $date, $sender wrote:" },
        forwardedHeader = "-- Forwarded --",
        from = { "From: $it" },
        date = { "Date: $it" },
        subject = { "Subject: $it" },
        to = { "To: $it" },
    )
    private val self = "b10000001@mail.ntust.edu.tw"
    private val original = mailSummary(
        5,
        subject = "期中考",
        to = listOf(MailAddress(null, self), MailAddress(null, "c@x.tw")),
        cc = listOf(MailAddress("D", "d@x.tw")),
    )

    @Test
    fun `reply quotes the original under a dated header`() {
        val draft = ComposePrefill.reply(original, "line1\nline2", all = false, selfAddress = self, labels = labels)
        assertEquals("教務處 <office@mail.ntust.edu.tw>", draft.to)
        assertEquals("", draft.cc)
        assertEquals("Re: 期中考", draft.subject)
        assertEquals("\n\nOn 2026/09/15 18:00, 教務處 <office@mail.ntust.edu.tw> wrote:\n> line1\n> line2", draft.body)
        assertEquals("<m5@x>", draft.inReplyTo)
        assertEquals("<m5@x>", draft.references)
    }

    @Test
    fun `reply all copies the other recipients without me`() {
        val draft = ComposePrefill.reply(original, "x", all = true, selfAddress = self.uppercase(), labels = labels)
        assertEquals("c@x.tw, D <d@x.tw>", draft.cc)
    }

    @Test
    fun `forward carries a header block and no recipients`() {
        val draft = ComposePrefill.forward(original, "body", labels)
        assertEquals("", draft.to)
        assertEquals("Fwd: 期中考", draft.subject)
        assertTrue(draft.body.startsWith("\n\n-- Forwarded --\nFrom: 教務處 <office@mail.ntust.edu.tw>\nDate: 2026/09/15 18:00\nSubject: 期中考\nTo: "))
        assertTrue(draft.body.endsWith("\n\nbody"))
        assertNull(draft.inReplyTo)
    }

    @Test
    fun `replying to a bounce addresses nobody and forwarding names the sender without an address`() {
        val bounce = mailSummary(
            9,
            subject = "Returned Mail: Hostname cannot be resolved",
            from = MailAddress("Mail Deliver System", ""),
            to = listOf(MailAddress(null, self)),
        )
        for (all in listOf(false, true)) {
            val draft = ComposePrefill.reply(bounce, "body", all = all, selfAddress = self, labels = labels)
            assertEquals("", draft.to)
            assertEquals("", draft.cc)
            assertTrue("no draft may be addressed to MAILER-DAEMON", "MAILER-DAEMON" !in draft.to + draft.cc)
            // The quote header still names who it came from, it just has no address to print.
            assertTrue(draft.body.startsWith("\n\nOn 2026/09/15 18:00, Mail Deliver System wrote:"))
        }
        val forwarded = ComposePrefill.forward(bounce, "body", labels)
        assertEquals("", forwarded.to)
        assertTrue(forwarded.body.contains("From: Mail Deliver System\n"))
        assertTrue("<>" !in forwarded.body)
    }

    @Test
    fun `a draft reopens as it was saved`() {
        val draft = ComposePrefill.draft(original, "draft body")
        assertEquals("$self, c@x.tw", draft.to)
        assertEquals("D <d@x.tw>", draft.cc)
        assertEquals("期中考", draft.subject)
        assertEquals("draft body", draft.body)
    }
}
