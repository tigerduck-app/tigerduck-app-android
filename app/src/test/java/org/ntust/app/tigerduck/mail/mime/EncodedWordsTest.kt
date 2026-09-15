package org.ntust.app.tigerduck.mail.mime

import org.junit.Assert.assertEquals
import org.junit.Test

class EncodedWordsTest {
    @Test
    fun `base64 UTF-8 word`() {
        assertEquals("中文", EncodedWords.decode("=?UTF-8?B?5Lit5paH?="))
    }

    @Test
    fun `Q-encoded big5 word decodes through Big5-HKSCS`() {
        assertEquals("中文", EncodedWords.decode("=?big5?Q?=A4=A4=A4=E5?="))
    }

    @Test
    fun `whitespace between adjacent words is dropped and plain text is kept`() {
        assertEquals("中文", EncodedWords.decode("=?UTF-8?B?5Lit?= =?UTF-8?B?5paH?="))
        assertEquals("Re: 中文 test", EncodedWords.decode("Re: =?UTF-8?B?5Lit5paH?= test"))
    }

    @Test
    fun `folded headers are unfolded and underscores are spaces in Q`() {
        assertEquals("a b c", EncodedWords.decode("=?UTF-8?Q?a_b?=\r\n c"))
    }

    @Test
    fun `a broken word is left as written`() {
        assertEquals("=?UTF-8?B?@@@?=", EncodedWords.decode("=?UTF-8?B?@@@?="))
    }
}
