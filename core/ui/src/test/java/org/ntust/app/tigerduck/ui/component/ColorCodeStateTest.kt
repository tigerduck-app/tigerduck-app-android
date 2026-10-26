package org.ntust.app.tigerduck.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the color code field shows, and the color it reads from it. */
class ColorCodeStateTest {

    @Test
    fun `it opens on the color's code`() {
        assertEquals("#1ABC9C", ColorCodeState(0xFF1ABC9C.toInt()).text)
    }

    @Test
    fun `a code with or without its # and in either case is a color`() {
        val state = ColorCodeState(0)

        assertEquals(0x12AB34, state.type("#12ab34"))
        assertEquals(0x12AB34, state.type("12AB34"))
        assertEquals("12AB34", state.text)
    }

    @Test
    fun `a code that is no color stays as typed and gives none`() {
        val state = ColorCodeState(0)

        assertNull(state.type("#12AB3G"))
        assertEquals("#12AB3G", state.text)
        assertNull(state.type("#12AB"))
    }

    @Test
    fun `a code longer than the field takes is read as the field shows it`() {
        val state = ColorCodeState(0)

        assertEquals(0x12AB34, state.type("#12AB345"))
        assertEquals("#12AB34", state.text)
    }

    @Test
    fun `a code the field cuts short gives no color`() {
        val state = ColorCodeState(0)

        assertNull(state.type("   112233"))
        assertEquals("   1122", state.text)
    }

    @Test
    fun `a color moved another way replaces what was typed`() {
        val state = ColorCodeState(0)
        state.type("#12AB3G")

        state.show(0x1ABC9C)

        assertEquals("#1ABC9C", state.text)
    }
}
