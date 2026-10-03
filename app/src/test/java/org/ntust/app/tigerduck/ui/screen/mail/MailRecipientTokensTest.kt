package org.ntust.app.tigerduck.ui.screen.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.mail.compose.ComposeRules
import org.ntust.app.tigerduck.ui.screen.mail.MailRecipientTokens.Consumed

class MailRecipientTokensTest {

    private fun RecipientField.texts() = tokens.map { it.text }

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

    /**
     * An `@` inside quotes is part of the name -- the form a reply writes for a name that is an
     * address -- so the space after the name does not cut the recipient in two.
     */
    @Test
    fun `an address quoted as a name is not an address`() {
        val quoted = "\"john@gmail.com\" <john@gmail.com>"
        assertEquals(Consumed(listOf(quoted), ""), MailRecipientTokens.consume("$quoted "))
        assertEquals(Consumed(emptyList(), "\"john@gmail.com\" "), MailRecipientTokens.consume("\"john@gmail.com\" "))
        // What the bubble holds is what sending takes.
        assertEquals(listOf("john@gmail.com"), ComposeRules.parseRecipients(listOf(quoted)).addresses.map { it.address })
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

    /** A reply's prefilled recipients split on commas only: a space there belongs to a name. */
    @Test
    fun `a prefilled value becomes bubbles`() {
        assertEquals(listOf("王 大明 <w@x.com>", "b@y.com"), RecipientField.of("王 大明 <w@x.com>, b@y.com").texts())
        assertEquals(listOf("\"Chen, Bob\" <b@y.com>"), RecipientField.of("\"Chen, Bob\" <b@y.com>").texts())
        assertEquals(emptyList<String>(), RecipientField.of("").texts())
    }

    /** Loading a value and reading it back gives the value back, so a prefill is not an edit. */
    @Test
    fun `a formatted list survives the round trip through bubbles`() {
        val value = "王小明 <a@x.tw>, \"Chen, Bob\" <b@y.tw>, c@z.tw"
        assertEquals(value, RecipientField.of(value).text)
    }

    @Test
    fun `what is being typed is a recipient too, where it stands`() {
        val field = RecipientField.of("a@x.com, b@y.com").typed(emptyList(), "c@")
        assertEquals(listOf("a@x.com", "b@y.com", "c@"), field.entries)
        assertEquals(listOf("a@x.com", "b@y.com"), RecipientField.of("a@x.com, b@y.com").typed(emptyList(), " ").entries)
    }

    /** Tapping a bubble only to look at it changes nothing: it is edited in its own place. */
    @Test
    fun `a bubble taken back to edit keeps its place`() {
        val field = RecipientField.of("a@x.tw, b@x.tw, c@x.tw")
        val editing = field.edit(field.tokens[1].id)
        assertEquals("b@x.tw", editing.draft)
        assertEquals(listOf("a@x.tw", "c@x.tw"), editing.texts())
        assertEquals(field.entries, editing.entries)
        // Finished again, it goes back where it was, and typing moves on to the end.
        val done = editing.finishDraft()
        assertEquals(listOf("a@x.tw", "b@x.tw", "c@x.tw"), done.texts())
        assertEquals(3, done.draftAt)
    }

    @Test
    fun `typing in a bubble's place puts the new ones there`() {
        val field = RecipientField.of("a@x.tw, b@x.tw, c@x.tw")
        val editing = field.edit(field.tokens[1].id)
        val retyped = editing.typed(listOf("d@x.tw", "e@x.tw"), "f")
        assertEquals(listOf("a@x.tw", "d@x.tw", "e@x.tw", "c@x.tw"), retyped.texts())
        assertEquals(listOf("a@x.tw", "d@x.tw", "e@x.tw", "f", "c@x.tw"), retyped.entries)
    }

    /** Opening another bubble finishes the one being typed first, in its place. */
    @Test
    fun `opening a bubble finishes what was being typed`() {
        val field = RecipientField.of("a@x.tw, b@x.tw").typed(emptyList(), "c@x.tw")
        val editing = field.edit(field.tokens[0].id)
        assertEquals("a@x.tw", editing.draft)
        assertEquals(listOf("b@x.tw", "c@x.tw"), editing.texts())
        assertEquals(listOf("a@x.tw", "b@x.tw", "c@x.tw"), editing.entries)
    }

    @Test
    fun `backspace with nothing typed opens the bubble before it`() {
        val field = RecipientField.of("a@x.tw, b@x.tw")
        val editing = field.editPrevious()
        assertEquals("b@x.tw", editing.draft)
        assertEquals(listOf("a@x.tw"), editing.texts())
        // Nothing before the typing, or something typed: nothing to open.
        assertEquals(RecipientField(), RecipientField().editPrevious())
        val typing = field.typed(emptyList(), "c")
        assertEquals(typing, typing.editPrevious())
    }

    /** A tap names its bubble by id, so one that lands after another bubble went finds the right one, or none. */
    @Test
    fun `a tap meant for a bubble already gone changes nothing`() {
        val field = RecipientField.of("a@x.tw, b@x.tw, c@x.tw")
        val (a, b) = field.tokens
        val removed = field.remove(a.id)
        assertEquals(listOf("b@x.tw", "c@x.tw"), removed.texts())
        assertEquals(removed, removed.remove(a.id))
        assertEquals(removed, removed.edit(a.id))
        assertEquals(listOf("c@x.tw"), removed.remove(b.id).texts())
        // A new bubble never takes an old one's id.
        val added = removed.typed(listOf("d@x.tw"), "")
        assertTrue(added.tokens.last().id !in field.tokens.map { it.id })
    }

    @Test
    fun `removing a bubble before the typing keeps the typing where it stands`() {
        val field = RecipientField.of("a@x.tw, b@x.tw, c@x.tw")
        val editing = field.edit(field.tokens[2].id)
        val removed = editing.remove(field.tokens[0].id)
        assertEquals(listOf("b@x.tw", "c@x.tw"), removed.entries)
        assertEquals(1, removed.draftAt)
    }
}
