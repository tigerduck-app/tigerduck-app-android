package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The periods grid's drag: the first cell decides whether a drag selects or clears, and a fast
 * drag applies that to every cell between two updates. As the iPhone's `CourseSearchTests` drag
 * cases.
 */
class CoursePeriodGridTest {

    /** Cells 50 by 30 after a 28-point label column and a 24-point header. */
    private val grid = PeriodGridGeometry(origin = Offset(28f, 24f), cell = Size(50f, 30f))

    private fun center(column: Int, row: Int) = Offset(28f + 50f * column + 25f, 24f + 30f * row + 15f)

    private fun slots(vararg pairs: Pair<Int, String>) = pairs.map { CourseSlot(it.first, it.second) }

    @Test
    fun `a drag that starts on an empty cell selects every cell it crosses`() {
        val (a, b, c) = slots(1 to "1", 1 to "2", 1 to "3")
        val picked = mutableSetOf(b)
        val drag = PeriodDrag()
        for (slot in listOf(a, b, c, b)) drag.touch(slot, picked)
        assertEquals(setOf(a, b, c), picked)
    }

    @Test
    fun `a drag that starts on a selected cell clears every cell it crosses`() {
        val (a, b, c) = slots(1 to "1", 1 to "2", 2 to "2")
        val picked = mutableSetOf(a, c)
        val drag = PeriodDrag()
        for (slot in listOf(a, b, c)) drag.touch(slot, picked)
        assertTrue(picked.isEmpty())

        PeriodDrag().touch(b, picked)
        assertEquals(setOf(b), picked)
    }

    @Test
    fun `a fast vertical drag crosses every period between two updates`() {
        assertEquals(
            slots(1 to "1", 1 to "2", 1 to "3", 1 to "4", 1 to "5", 1 to "6"),
            grid.slots(center(0, 0), center(0, 5)),
        )
        assertEquals(slots(3 to "D", 3 to "C", 3 to "B"), grid.slots(center(2, 13), center(2, 11)))
    }

    @Test
    fun `a fast diagonal drag crosses the cells on its line`() {
        assertEquals(slots(1 to "1", 2 to "2", 3 to "3", 4 to "4"), grid.slots(center(0, 0), center(3, 3)))
        assertEquals(slots(1 to "1", 2 to "1", 2 to "2", 3 to "2"), grid.slots(center(0, 0), center(2, 1)))
    }

    @Test
    fun `a drag from outside the grid crosses only the cells inside it`() {
        assertEquals(slots(1 to "1", 2 to "1", 3 to "1"), grid.slots(Offset(5f, 39f), center(2, 0)))
        assertTrue(grid.slots(Offset(5f, 5f), Offset(20f, 10f)).isEmpty())
    }

    @Test
    fun `a drag applies its first cell's choice to every cell between two far updates`() {
        val picked = slots(1 to "3").toMutableSet()
        val drag = PeriodDrag()
        drag.move(center(0, 0), grid, picked)
        drag.move(center(0, 4), grid, picked)
        assertEquals(slots(1 to "1", 1 to "2", 1 to "3", 1 to "4", 1 to "5").toSet(), picked)

        val clearing = PeriodDrag()
        clearing.move(center(0, 4), grid, picked)
        clearing.move(center(0, 1), grid, picked)
        assertEquals(slots(1 to "1").toSet(), picked)
    }
}
