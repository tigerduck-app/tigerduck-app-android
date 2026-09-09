package org.ntust.app.tigerduck.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.ntust.app.tigerduck.data.model.HomeSection

/**
 * Order itself is carried by list position — nothing in the app sorts on
 * `sortOrder`. These cases still assert the field because it is part of the
 * persisted shape, and a layout stored with duplicate or gapped values cannot
 * be told apart from a corrupt one if a reader is ever added.
 */
class HomeSectionLayoutTest {

    private fun section(id: String, order: Int) = HomeSection(
        id = id,
        type = HomeSection.HomeSectionType.CUSTOM,
        title = id,
        sortOrder = order,
        isVisible = true,
    )

    private val threeSections = listOf(section("a", 0), section("b", 1), section("c", 2))

    @Test
    fun `removing renumbers what is left`() {
        val out = HomeSectionLayout.remove(threeSections, "a")
        assertEquals(listOf("b", "c"), out.map { it.id })
        assertEquals(listOf(0, 1), out.map { it.sortOrder })
    }

    @Test
    fun `removing an unknown id changes nothing`() {
        val out = HomeSectionLayout.remove(threeSections, "zzz")
        assertEquals(threeSections.map { it.id }, out.map { it.id })
        assertEquals(listOf(0, 1, 2), out.map { it.sortOrder })
    }

    @Test
    fun `moving down places the section at the target's slot`() {
        val out = HomeSectionLayout.move(threeSections, fromId = "a", toId = "c")
        assertEquals(listOf("b", "c", "a"), out.map { it.id })
        assertEquals(listOf(0, 1, 2), out.map { it.sortOrder })
    }

    @Test
    fun `moving up places the section at the target's slot`() {
        val out = HomeSectionLayout.move(threeSections, fromId = "c", toId = "a")
        assertEquals(listOf("c", "a", "b"), out.map { it.id })
        assertEquals(listOf(0, 1, 2), out.map { it.sortOrder })
    }

    @Test
    fun `a move onto itself returns the very same list`() {
        // Identity, not just equality: HomeViewModel uses `!==` to decide
        // whether to write preferences, so a no-op has to be the same object.
        assertSame(threeSections, HomeSectionLayout.move(threeSections, "b", "b"))
    }

    @Test
    fun `a move with an unknown endpoint returns the very same list`() {
        assertSame(threeSections, HomeSectionLayout.move(threeSections, "a", "zzz"))
        assertSame(threeSections, HomeSectionLayout.move(threeSections, "zzz", "a"))
    }

    @Test
    fun `adding appends with the next sort order`() {
        val out = HomeSectionLayout.add(
            sections = threeSections,
            id = "new",
            type = HomeSection.HomeSectionType.CUSTOM,
            title = "New",
        )
        assertEquals(listOf("a", "b", "c", "new"), out.map { it.id })
        assertEquals(3, out.last().sortOrder)
        assertEquals("New", out.last().title)
    }

    @Test
    fun `adding to an empty layout starts at zero`() {
        val out = HomeSectionLayout.add(
            sections = emptyList(),
            id = "new",
            type = HomeSection.HomeSectionType.TODAY_COURSES,
            title = "Today",
        )
        assertEquals(0, out.single().sortOrder)
    }

    @Test
    fun `remove then add reuses the freed sort order`() {
        // The two operations have to agree on what "next" means, or a
        // remove-then-add cycle produces two sections claiming one slot.
        val afterRemove = HomeSectionLayout.remove(threeSections, "b")
        val afterAdd = HomeSectionLayout.add(
            sections = afterRemove,
            id = "new",
            type = HomeSection.HomeSectionType.CUSTOM,
            title = "New",
        )
        assertEquals(listOf(0, 1, 2), afterAdd.map { it.sortOrder })
    }

    // AppPreferences.homeSections drops QUICK_WIDGETS out of a restored
    // layout, which is the one way `add` receives a list whose highest
    // sortOrder is >= its size. Deriving the new value from size alone would
    // hand the new section a number an existing one already holds.
    @Test
    fun `adding to a gapped layout does not duplicate a sort order`() {
        val gapped = listOf(section("a", 0), section("b", 1), section("d", 3))
        val out = HomeSectionLayout.add(
            sections = gapped,
            id = "new",
            type = HomeSection.HomeSectionType.CUSTOM,
            title = "New",
        )
        assertEquals(listOf(0, 1, 2, 3), out.map { it.sortOrder })
        assertEquals(listOf("a", "b", "d", "new"), out.map { it.id })
    }
}
