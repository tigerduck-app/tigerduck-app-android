package org.ntust.app.tigerduck.ui.screen.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MailWebViewTest {
    @Test
    fun `parseLinkIndex accepts only the exact synthetic form, in range`() {
        assertEquals(0, parseLinkIndex("https://link.invalid/0", linkCount = 3))
        assertEquals(2, parseLinkIndex("https://link.invalid/2", linkCount = 3))
    }

    @Test
    fun `parseLinkIndex ignores a URL that is not the synthetic form`() {
        assertNull(parseLinkIndex("https://evil.example", linkCount = 3))
        assertNull(parseLinkIndex("https://link.invalid/1/extra", linkCount = 3))
        assertNull(parseLinkIndex("http://link.invalid/1", linkCount = 3))
        assertNull(parseLinkIndex("https://link.invalid/01", linkCount = 3))
        assertNull(parseLinkIndex("https://link.invalid/-1", linkCount = 3))
        assertNull(parseLinkIndex("https://link.invalid/1?x=1", linkCount = 3))
        assertNull(parseLinkIndex("https://link.invalid/1#f", linkCount = 3))
        assertNull(parseLinkIndex("https://link.invalid/", linkCount = 3))
        assertNull(parseLinkIndex("https://LINK.INVALID/1", linkCount = 3))
    }

    @Test
    fun `parseLinkIndex ignores an out-of-range index`() {
        assertNull(parseLinkIndex("https://link.invalid/3", linkCount = 3))
        assertNull(parseLinkIndex("https://link.invalid/100", linkCount = 3))
        assertNull(parseLinkIndex("https://link.invalid/0", linkCount = 0))
    }
}
