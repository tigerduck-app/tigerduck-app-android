package org.ntust.app.tigerduck.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** RFC 5545 text escaping and line folding. Ported from iOS `ClassTableCalendarFileTests`. */
class IcsFormatTest {

    @Test
    fun `folding breaks only past 75 octets and starts each continuation with a space`() {
        val exact = "a".repeat(75)
        assertEquals(exact, IcsFormat.folded(exact))
        assertEquals("$exact\r\n b", IcsFormat.folded(exact + "b"))
        assertEquals("表".repeat(25) + "\r\n 表", IcsFormat.folded("表".repeat(26)))
    }

    @Test
    fun `a continuation line counts its space, and a four-octet character is never split`() {
        val folded = IcsFormat.folded("a".repeat(200))
        assertEquals(listOf(75, 75, 52), folded.split("\r\n").map { it.length })
        // 18 four-octet characters fill 72 octets; the 19th would pass 75.
        val emoji = "😀".repeat(19)
        assertEquals("😀".repeat(18) + "\r\n 😀", IcsFormat.folded(emoji))
    }

    @Test
    fun `text escapes a backslash, a semicolon and a comma, writes line breaks as n and drops other control characters`() {
        assertEquals("a\\\\b\\;c\\,d\\ne\\nf\tg", IcsFormat.escaped("a\\b;c,d\r\ne\nf\tg\u0007\u007F"))
        assertEquals("one\\ntwo", IcsFormat.escaped("one\rtwo"))
    }
}
