package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.data.sharing.ImportedPerson
import org.ntust.app.tigerduck.data.sharing.ImportedPerson.Placement
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableListRows.Header
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableListRows.Row
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableListRows.User
import java.time.Instant

/** The Class tables list's sections and its drags, as iOS `ClassTableListTests`. */
class ClassTableListRowsTest {

    private fun person(name: String, placement: Placement = Placement.ROTATION) =
        ImportedPerson(name, null, name, Instant.EPOCH, null, emptyList(), placement)

    private val ming = person("Ming")
    private val hua = person("Hua", Placement.OTHER)
    private val wen = person("Wen")

    private fun names(rows: List<Row>?) = rows?.map {
        when (it) {
            is Header -> it.placement.stored
            User -> "me"
            is ClassTableListRows.Person -> it.person.nickname
        }
    }

    private val rows = ClassTableListRows.of(listOf(ming, hua, wen))

    @Test
    fun `the sections run rotation, long-press menu, Other, the user first and empty ones kept`() {
        assertEquals(listOf("rotation", "me", "Ming", "Wen", "longPressMenu", "other", "Hua"), names(rows))
    }

    @Test
    fun `a drag moves a person past the next row, into an empty section under its header too`() {
        val down = ClassTableListRows.step(rows, 3, down = true)
        assertEquals(listOf("rotation", "me", "Ming", "longPressMenu", "Wen", "other", "Hua"), names(down))
        assertEquals(Placement.LONG_PRESS_MENU to null, ClassTableListRows.target(down!!, 4))

        val further = ClassTableListRows.step(down, 4, down = true)
        assertEquals(listOf("rotation", "me", "Ming", "longPressMenu", "other", "Wen", "Hua"), names(further))
        assertEquals(Placement.OTHER to "Hua", ClassTableListRows.target(further!!, 5))
    }

    @Test
    fun `a drag up goes to the end of the section above, and never above the user`() {
        val up = ClassTableListRows.step(rows, 6, down = false)
        assertEquals(listOf("rotation", "me", "Ming", "Wen", "longPressMenu", "Hua", "other"), names(up))
        assertEquals(Placement.LONG_PRESS_MENU to null, ClassTableListRows.target(up!!, 5))

        val top = ClassTableListRows.step(rows, 3, down = false)!!
        assertEquals(Placement.ROTATION to "Ming", ClassTableListRows.target(top, 2))
        assertNull("not above the user", ClassTableListRows.step(top, 2, down = false))
        assertNull("not past the end", ClassTableListRows.step(rows, 6, down = true))
        assertNull("the user stays", ClassTableListRows.step(rows, 1, down = true))
        assertNull("a header stays", ClassTableListRows.step(rows, 4, down = true))
    }

    private fun height(row: Row): Float = if (row is Header) 40f else 56f

    @Test(timeout = 5_000)
    fun `a drag of exactly half the next row stays put rather than swapping back and forth`() {
        val (still, left) = ClassTableListRows.dragged(rows, "Wen", 20f, ::height)
        assertEquals(names(rows), names(still))
        assertEquals(20f, left, 0f)
    }

    @Test
    fun `a drag passes each row it has gone more than half of, and keeps the rest of the way`() {
        val (once, left) = ClassTableListRows.dragged(rows, "Wen", 21f, ::height)
        assertEquals(listOf("rotation", "me", "Ming", "longPressMenu", "Wen", "other", "Hua"), names(once))
        assertEquals(-19f, left, 0f)

        val (twice, rest) = ClassTableListRows.dragged(rows, "Wen", 61f, ::height)
        assertEquals(listOf("rotation", "me", "Ming", "longPressMenu", "other", "Wen", "Hua"), names(twice))
        assertEquals(-19f, rest, 0f)

        val (up, back) = ClassTableListRows.dragged(rows, "Wen", -200f, ::height)
        assertEquals(names(rows.toMutableList().apply { add(2, removeAt(3)) }), names(up))
        assertEquals(-144f, back, 0f)
    }
}
