package org.ntust.app.tigerduck.wear.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How Now and Next and the complication mark what the school changed, as the iPhone's watch does. */
class ChangeMarksTest {

    @Test
    fun `the ink is the iPhone's change ink`() {
        assertEquals(Color(0xFFFDBA74), ChangeInk)
    }

    private val courseInk = Color(0xFF34C759)

    @Test
    fun `a moved room is marked and inked, with no instructor after it`() {
        val line = roomLine("TR-411", moved = true, courseInk = courseInk)!!
        assertEquals("⇄ TR-411", line.text)
        val span = line.spanStyles.single()
        assertEquals(0, span.start)
        assertEquals(line.length, span.end)
        assertEquals(ChangeInk, span.item.color)
        assertEquals(FontWeight.Bold, span.item.fontWeight)
    }

    @Test
    fun `a room the school left alone is the room alone, bold in the course's ink`() {
        val line = roomLine("TR-313", moved = false, courseInk = courseInk)!!
        assertEquals("TR-313", line.text)
        val span = line.spanStyles.single()
        assertEquals(line.length, span.end)
        assertEquals(courseInk, span.item.color)
        assertEquals(FontWeight.Bold, span.item.fontWeight)
    }

    @Test
    fun `an online class shows Online as a moved room`() {
        assertEquals("⇄ Online", roomLine("Online", moved = true, courseInk = courseInk)!!.text)
    }

    @Test
    fun `a class with no room has no room line`() {
        assertNull(roomLine("", moved = false, courseInk = courseInk))
    }

    @Test
    fun `the complication marks a moved room with the arrows alone`() {
        assertEquals("⇄ TR-411", roomText("TR-411", moved = true))
        assertEquals("TR-313", roomText("TR-313", moved = false))
    }
}
