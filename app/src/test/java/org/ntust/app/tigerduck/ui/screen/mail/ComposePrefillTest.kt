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
    fun `a draft reopens as it was saved`() {
        val draft = ComposePrefill.draft(original, "draft body")
        assertEquals("$self, c@x.tw", draft.to)
        assertEquals("D <d@x.tw>", draft.cc)
        assertEquals("期中考", draft.subject)
        assertEquals("draft body", draft.body)
    }
}
