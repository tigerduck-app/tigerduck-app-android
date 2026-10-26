package org.ntust.app.tigerduck.widget.content

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.widget.WidgetScale

/**
 * Covers [tileNameLines]: a TAT period tile on the Week widget names its
 * course in as many lines as its height holds, so no tile shows a second line
 * cut through the middle.
 */
class TileNameLinesTest {

    @Test
    fun `the rows of a 4x2 or 4x3 widget hold one line`() {
        assertEquals(1, tileNameLines(20.dp, courseNameScale = 1f, fontScale = 1f))
        assertEquals(1, tileNameLines(27.dp, courseNameScale = 1f, fontScale = 1f))
    }

    @Test
    fun `a row tall enough for two lines takes two, and never more`() {
        assertEquals(2, tileNameLines(36.dp, courseNameScale = 1f, fontScale = 1f))
        assertEquals(2, tileNameLines(120.dp, courseNameScale = 1f, fontScale = 1f))
    }

    @Test
    fun `a larger name or system font leaves room for fewer lines`() {
        assertEquals(1, tileNameLines(36.dp, courseNameScale = 1.6f, fontScale = 1f))
        assertEquals(1, tileNameLines(36.dp, courseNameScale = 1f, fontScale = 1.3f))
    }

    @Test
    fun `a larger widget text size leaves room for fewer lines`() {
        assertEquals(1, tileNameLines(36.dp, courseNameScale = 1f, fontScale = 1f, scale = WidgetScale(text = 1.3f)))
    }

    @Test
    fun `a larger widget display size takes more of the tile in padding`() {
        assertEquals(1, tileNameLines(36.dp, courseNameScale = 1f, fontScale = 1f, scale = WidgetScale(ui = 1.3f)))
        assertEquals(2, tileNameLines(36.dp, courseNameScale = 1f, fontScale = 1f, scale = WidgetScale(ui = 0.75f)))
    }

    @Test
    fun `a tile too short for even one line still shows one`() {
        assertEquals(1, tileNameLines(8.dp, courseNameScale = 1f, fontScale = 1f))
    }
}
