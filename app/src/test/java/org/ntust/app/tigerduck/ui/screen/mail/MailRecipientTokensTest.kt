package org.ntust.app.tigerduck.ui.screen.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.mail.compose.ComposeRules
import org.ntust.app.tigerduck.ui.screen.mail.MailRecipientTokens.Consumed

class MailRecipientTokensTest {

    @Test
    fun `a comma or semicolon finishes a recipient`() {
        assertEquals(Consumed(listOf("a@x.com"), ""), MailRecipientTokens.consume("a@x.com,"))
        assertEquals(Consumed(listOf("a@x.com"), "b@y"), MailRecipientTokens.consume("a@x.com;b@y"))
        assertEquals(Consumed(listOf("a@x.com", "b@y.com"), ""), MailRecipientTokens.consume("a@x.com, b@y.com,"))
    }

    @Test
    fun `a space after an address finishes it`() {
        assertEquals(Consumed(listOf("a@x.com"), ""), MailRecipientTokens.consume("a@x.com "))
        assertEquals(Consumed(listOf("a@x.com"), "b"), MailRecipientTokens.consume("a@x.com b"))
        // A pasted list, one address per line.
        assertEquals(Consumed(listOf("a@x.com"), "b@y.com"), MailRecipientTokens.consume("a@x.com\nb@y.com"))
    }

    /** A name comes before its address, and may itself contain spaces. */
    @Test
    fun `a space in a name does not`() {
        assertEquals(Consumed(emptyList(), "王 大明 "), MailRecipientTokens.consume("王 大明 "))
        assertEquals(Consumed(listOf("Bob Chen <bob@x.com>"), ""), MailRecipientTokens.consume("Bob Chen <bob@x.com> "))
        assertTrue(MailRecipientTokens.consume("\"Chen, Bob\" <b").finished.isEmpty())
        assertTrue(MailRecipientTokens.consume("<bob@x.com , ").finished.isEmpty())
    }

    /** Quotes are followed the way sending follows them, escaped quotes included. */
    @Test
    fun `an escaped quote does not end a quoted name`() {
        assertEquals(
            Consumed(listOf("\"A \\\"B, C\\\" D\" <x@y.tw>"), ""),
            MailRecipientTokens.consume("\"A \\\"B, C\\\" D\" <x@y.tw>,"),
        )
    }

    @Test
    fun `separators alone finish nothing`() {
        assertEquals(Consumed(emptyList(), ""), MailRecipientTokens.consume(" "))
        assertEquals(Consumed(emptyList(), ""), MailRecipientTokens.consume(","))
        assertEquals(Consumed(emptyList(), ""), MailRecipientTokens.consume(", ,"))
    }

    /** The view model keeps one comma-separated string; the bubbles are only its display. */
    @Test
    fun `the field composes to what the view model parses`() {
        assertEquals("a@x.com, Bob <b@y.com>, c@", MailRecipientTokens.compose(listOf("a@x.com", "Bob <b@y.com>"), "c@"))
        assertEquals("", MailRecipientTokens.compose(emptyList(), " "))
        assertEquals("a@x.com", MailRecipientTokens.compose(listOf("a@x.com"), ""))
        val parsed = ComposeRules.parseRecipients(MailRecipientTokens.compose(listOf("\"Chen, Bob\" <b@y.com>", "a@x.com"), ""))
        assertEquals(listOf("b@y.com", "a@x.com"), parsed.addresses.map { it.address })
    }

    /** A reply's prefilled recipients split on commas only: a space there belongs to a name. */
    @Test
    fun `a prefilled value becomes bubbles`() {
        assertEquals(listOf("王 大明 <w@x.com>", "b@y.com"), MailRecipientTokens.tokens("王 大明 <w@x.com>, b@y.com"))
        assertEquals(listOf("\"Chen, Bob\" <b@y.com>"), MailRecipientTokens.tokens("\"Chen, Bob\" <b@y.com>"))
        assertEquals(emptyList<String>(), MailRecipientTokens.tokens(""))
    }

    /** Loading a value and composing it again gives the value back, so a prefill is not an edit. */
    @Test
    fun `a formatted list survives the round trip through bubbles`() {
        val value = "王小明 <a@x.tw>, \"Chen, Bob\" <b@y.tw>, c@z.tw"
        assertEquals(value, MailRecipientTokens.compose(MailRecipientTokens.tokens(value), ""))
    }
}
