package org.ntust.app.tigerduck.clubs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A club's default color, which iOS computes the same way (ClubSubscriptionsDocumentTests). */
class ClubColorsTest {

    @Test
    fun `the hash is 32-bit FNV-1a`() {
        assertEquals(0x811C9DC5u, ClubColors.fnv1a(""))
        assertEquals(0xE40C292Cu, ClubColors.fnv1a("a"))
        assertEquals(0xBF9CF968u, ClubColors.fnv1a("foobar"))
    }

    @Test
    fun `a club's default color hashes its decimal id into the course palette, as on iOS`() {
        val expected = mapOf(
            93 to 0xD35400, 33 to 0xC0392B, 41 to 0x1ABC9C, 128 to 0xF1C40F, 39 to 0x2ECC71, 110 to 0x4ECDC4, 1 to 0xDDA0DD,
        )
        expected.forEach { (id, hex) -> assertEquals("club $id", hex, ClubColors.defaultColor(id)) }
        // The palette index is the hash modulo the palette, in its order: 93 takes 焦橙, the 14th.
        assertEquals(13, ClubColors.palette.indexOf(ClubColors.defaultColor(93)))
        assertEquals(20, ClubColors.palette.size)
    }

    @Test
    fun `colors travel as #RRGGBB`() {
        assertEquals(0xF1C40F, ClubColors.parse("#f1c40f"))
        assertEquals("#0000FF", ClubColors.hex(0x0000FF))
        listOf("red", "#1ABC9G", "#DDA0DD0", "DDA0DD", "").forEach { assertNull(it, ClubColors.parse(it)) }
    }
}
