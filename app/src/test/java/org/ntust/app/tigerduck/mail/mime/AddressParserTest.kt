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
    fun `address shape check`() {
        assertTrue(AddressParser.looksLikeAddress("b1@mail.ntust.edu.tw"))
        assertFalse(AddressParser.looksLikeAddress("b1@localhost"))
        assertFalse(AddressParser.looksLikeAddress("a b@x.tw"))
    }
}
