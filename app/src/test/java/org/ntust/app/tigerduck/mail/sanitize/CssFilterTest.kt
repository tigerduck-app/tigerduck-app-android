package org.ntust.app.tigerduck.mail.sanitize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CssFilterTest {
    @Test
    fun `allowed properties are kept in order`() {
        assertEquals("color: red; font-size: 14px", CssFilter.filter("color: red; font-size: 14px"))
        assertEquals("margin-top: 4px; border-left: 1px solid #ccc", CssFilter.filter("MARGIN-TOP:4px;border-left:1px solid #ccc"))
    }

    @Test
    fun `dangerous values drop the whole declaration`() {
        for (bad in listOf("background-color: url(x)", "width: expression(1)", "color: red; @import x", "font: \\66 oo", "color: /* x */ red", "color: javascript:x")) {
            val out = CssFilter.filter(bad)
            assertEquals(bad, true, out == null || ("url(" !in out && "expression" !in out && "\\" !in out && "/*" !in out && "javascript:" !in out))
        }
    }

    @Test
    fun `css image loading functions drop the declaration`() {
        assertNull(CssFilter.filter("border-image-source: image-set(\"https://t.example/p.gif\" 1x)"))
        assertNull(CssFilter.filter("list-style: image-set('https://t.example/q.gif' 1x)"))
    }

    @Test
    fun `unlisted properties and unsafe display values are dropped`() {
        assertEquals("color: red", CssFilter.filter("position: fixed; color: red; z-index: 99; background-image: none"))
        assertNull(CssFilter.filter("display: flex"))
        assertEquals("display: block", CssFilter.filter("display: block"))
        assertNull(CssFilter.filter(""))
        assertNull(CssFilter.filter("nonsense"))
    }
}
