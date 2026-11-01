package org.ntust.app.tigerduck.wear.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How Now and Next and the complication mark what the school changed, as the iPhone's watch does. */
class ChangeMarksTest {

    @Test
    fun `the ink is the iPhone's change ink`() {
        assertEquals(Color(0xFFFDBA74), ChangeInk)
    }

    @Test
    fun `a moved room is marked and inked, the instructor is not`() {
        val line = classroomLine("TR-411", "Ada", moved = true)
        assertEquals("⇄ TR-411 · Ada", line.text)
        val span = line.spanStyles.single()
        assertEquals(0, span.start)
        assertEquals("⇄ TR-411".length, span.end)
        assertEquals(ChangeInk, span.item.color)
        assertEquals(FontWeight.SemiBold, span.item.fontWeight)
    }

    @Test
    fun `a room the school left alone reads as before`() {
        val line = classroomLine("TR-313", "Ada", moved = false)
        assertEquals("TR-313 · Ada", line.text)
        assertTrue(line.spanStyles.isEmpty())
    }

    @Test
    fun `the complication marks a moved room with the arrows alone`() {
        assertEquals("⇄ TR-411", roomText("TR-411", moved = true))
        assertEquals("TR-313", roomText("TR-313", moved = false))
    }
}
