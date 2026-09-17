package org.ntust.app.tigerduck.mail.mime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.mail.model.MailAddress

class AddressParserTest {
    @Test
    fun `display name in encoded words is decoded`() {
        assertEquals(
            listOf(MailAddress("中文", "b1xxxxxxx@mail.ntust.edu.tw")),
            AddressParser.parseList("\"=?utf-8?B?5Lit5paH?=\" <b1xxxxxxx@mail.ntust.edu.tw>"),
        )
    }

    @Test
    fun `lists split on commas outside quotes and brackets`() {
        val parsed = AddressParser.parseList("\"Wang, Da\" <a@x.tw>, b@y.tw; C <c@z.tw>")
        assertEquals(
            listOf(MailAddress("Wang, Da", "a@x.tw"), MailAddress(null, "b@y.tw"), MailAddress("C", "c@z.tw")),
            parsed,
        )
    }

    @Test
    fun `invalid entries are skipped`() {
        assertEquals(listOf(MailAddress(null, "ok@x.tw")), AddressParser.parseList("nonsense, ok@x.tw, <also bad>"))
        assertNull(AddressParser.parseOne("   "))
    }

    @Test
    fun `quoted-pairs are undone in one left-to-right pass`() {
        // On the wire, the name `Wang\Da` is written with the backslash escaped.
        assertEquals(MailAddress("Wang\\Da", "a@x.tw"), AddressParser.parseOne("\"Wang\\\\Da\" <a@x.tw>"))
        assertEquals(MailAddress("say \"hi\"", "a@x.tw"), AddressParser.parseOne("\"say \\\"hi\\\"\" <a@x.tw>"))
        // The `\` an escaped backslash produces must never act as an escape itself:
        // `\\` then `\"` is a backslash followed by a quote, not an escaped quote.
        assertEquals(MailAddress("A\\\"B", "a@x.tw"), AddressParser.parseOne("\"A\\\\\\\"B\" <a@x.tw>"))
    }

    @Test
    fun `address shape check`() {
        assertTrue(AddressParser.looksLikeAddress("b1@mail.ntust.edu.tw"))
        assertFalse(AddressParser.looksLikeAddress("b1@localhost"))
        assertFalse(AddressParser.looksLikeAddress("a b@x.tw"))
    }

    @Test
    fun `a non-ASCII address still parses -- only compose rejects it for sending`() {
        // An incoming header with a non-ASCII local part or domain must still show a sender:
        // rejecting it here would hide the mail's "From" behind "no sender". ComposeRules is
        // what refuses to send to one (the school server has no SMTPUTF8).
        assertTrue(AddressParser.looksLikeAddress("中文@x.tw"))
        assertTrue(AddressParser.looksLikeAddress("a@中文.tw"))
        assertEquals(MailAddress(null, "中文@x.tw"), AddressParser.parseOne("中文@x.tw"))
        assertEquals(MailAddress("王小明", "a@中文.tw"), AddressParser.parseOne("王小明 <a@中文.tw>"))
    }
}
