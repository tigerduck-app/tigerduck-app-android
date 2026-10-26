package org.ntust.app.tigerduck.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.HomeSection
import org.ntust.app.tigerduck.data.model.WidgetItem

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

    // Builds before 2.5 dropped QUICK_WIDGETS out of a restored layout, and a
    // layout they re-saved can carry the gap, which is the one way `add`
    // receives a list whose highest sortOrder is >= its size. Deriving the new
    // value from size alone would hand the new section a number an existing
    // one already holds.
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

    // --- Shortcuts (quick features) ---

    private fun shortcutSection(
        id: String,
        type: HomeSection.HomeSectionType,
        vararg features: AppFeature,
    ) = HomeSection(
        id = id,
        type = type,
        title = id,
        sortOrder = 0,
        isVisible = true,
        widgets = features.map { WidgetItem(id = "w-${it.id}", feature = it) },
    )

    private val builtinOnly = listOf(
        section("today", 0).copy(type = HomeSection.HomeSectionType.TODAY_COURSES),
        section("assignments", 1).copy(type = HomeSection.HomeSectionType.UPCOMING_ASSIGNMENTS),
    )

    @Test
    fun `adding a quick feature with no section to hold it creates a Quick widgets section`() {
        // iOS parity (HomeViewModel.addQuickFeature): the Add sheet's list must
        // always do something, so a layout without a shortcut grid grows one.
        val out = HomeSectionLayout.addQuickFeature(
            sections = builtinOnly,
            feature = AppFeature.ANNOUNCEMENTS,
            sectionId = "qw",
            widgetId = "w1",
            quickWidgetsTitle = "Quick widgets",
        )
        assertEquals(listOf("today", "assignments", "qw"), out.map { it.id })
        val added = out.last()
        assertEquals(HomeSection.HomeSectionType.QUICK_WIDGETS, added.type)
        assertEquals("Quick widgets", added.title)
        assertEquals(2, added.sortOrder)
        assertEquals(listOf(WidgetItem("w1", AppFeature.ANNOUNCEMENTS)), added.widgets)
    }

    @Test
    fun `adding a quick feature appends to the first section that holds shortcuts`() {
        // A custom section counts: on iOS both types render the same grid, and
        // the first one in layout order is the target.
        val sections = builtinOnly +
            shortcutSection("custom", HomeSection.HomeSectionType.CUSTOM, AppFeature.SCORE) +
            shortcutSection("qw", HomeSection.HomeSectionType.QUICK_WIDGETS)
        val out = HomeSectionLayout.addQuickFeature(
            sections = sections,
            feature = AppFeature.ANNOUNCEMENTS,
            sectionId = "unused",
            widgetId = "w1",
            quickWidgetsTitle = "Quick widgets",
        )
        assertEquals(sections.map { it.id }, out.map { it.id })
        assertEquals(
            listOf(AppFeature.SCORE, AppFeature.ANNOUNCEMENTS),
            out.first { it.id == "custom" }.widgets.map { it.feature },
        )
        assertEquals(emptyList<WidgetItem>(), out.first { it.id == "qw" }.widgets)
    }

    @Test
    fun `adding a quick feature already on Home returns the very same list`() {
        // The Add dialog hides features that are already placed; the model
        // still refuses a second copy, so a double tap cannot slip one in.
        val sections = builtinOnly +
            shortcutSection("qw", HomeSection.HomeSectionType.QUICK_WIDGETS, AppFeature.SCORE)
        assertSame(
            sections,
            HomeSectionLayout.addQuickFeature(sections, AppFeature.SCORE, "x", "y", "Quick widgets"),
        )
    }

    @Test
    fun `removing a shortcut leaves the section and the others in place`() {
        val sections = builtinOnly + shortcutSection(
            "qw", HomeSection.HomeSectionType.QUICK_WIDGETS,
            AppFeature.ANNOUNCEMENTS, AppFeature.SCORE, AppFeature.EMPTY_CLASSROOM,
        )
        val out = HomeSectionLayout.removeWidget(sections, sectionId = "qw", widgetId = "w-score")
        assertEquals(sections.map { it.id }, out.map { it.id })
        assertEquals(
            listOf(AppFeature.ANNOUNCEMENTS, AppFeature.EMPTY_CLASSROOM),
            out.last().widgets.map { it.feature },
        )
    }

    @Test
    fun `removing an unknown shortcut returns the very same list`() {
        val sections = builtinOnly +
            shortcutSection("qw", HomeSection.HomeSectionType.QUICK_WIDGETS, AppFeature.SCORE)
        assertSame(sections, HomeSectionLayout.removeWidget(sections, "qw", "nope"))
        assertSame(sections, HomeSectionLayout.removeWidget(sections, "nope", "w-score"))
    }

    @Test
    fun `moving a shortcut puts it at the target index`() {
        val sections = builtinOnly + shortcutSection(
            "qw", HomeSection.HomeSectionType.QUICK_WIDGETS,
            AppFeature.ANNOUNCEMENTS, AppFeature.SCORE, AppFeature.EMPTY_CLASSROOM,
        )
        val down = HomeSectionLayout.moveWidget(sections, "qw", widgetId = "w-announcements", toIndex = 2)
        assertEquals(
            listOf(AppFeature.SCORE, AppFeature.EMPTY_CLASSROOM, AppFeature.ANNOUNCEMENTS),
            down.last().widgets.map { it.feature },
        )
        val up = HomeSectionLayout.moveWidget(sections, "qw", widgetId = "w-emptyClassroom", toIndex = 0)
        assertEquals(
            listOf(AppFeature.EMPTY_CLASSROOM, AppFeature.ANNOUNCEMENTS, AppFeature.SCORE),
            up.last().widgets.map { it.feature },
        )
    }

    @Test
    fun `the Add dialog offers the shipped shortcut features not yet on Home, in order`() {
        // Whatever section holds the shortcut, custom included, it is taken.
        val sections = builtinOnly +
            shortcutSection("custom", HomeSection.HomeSectionType.CUSTOM, AppFeature.SCORE)
        assertEquals(
            AppFeature.widgetFeatures - AppFeature.SCORE,
            HomeSectionLayout.addableQuickFeatures(sections),
        )
        assertEquals(AppFeature.widgetFeatures, HomeSectionLayout.addableQuickFeatures(builtinOnly))
        val everything = builtinOnly + shortcutSection(
            "qw", HomeSection.HomeSectionType.QUICK_WIDGETS, *AppFeature.widgetFeatures.toTypedArray(),
        )
        assertEquals(emptyList<AppFeature>(), HomeSectionLayout.addableQuickFeatures(everything))
    }

    @Test
    fun `a shortcut move that changes nothing returns the very same list`() {
        // Same identity contract as `move`: HomeViewModel persists on `!==`,
        // and a drag is a stream of these calls, most of them no-ops.
        val sections = builtinOnly + shortcutSection(
            "qw", HomeSection.HomeSectionType.QUICK_WIDGETS, AppFeature.ANNOUNCEMENTS, AppFeature.SCORE,
        )
        assertSame(sections, HomeSectionLayout.moveWidget(sections, "qw", "w-score", toIndex = 1))
        assertSame(sections, HomeSectionLayout.moveWidget(sections, "qw", "nope", toIndex = 0))
        assertSame(sections, HomeSectionLayout.moveWidget(sections, "nope", "w-score", toIndex = 0))
        assertSame(sections, HomeSectionLayout.moveWidget(sections, "qw", "w-score", toIndex = 5))
        assertSame(sections, HomeSectionLayout.moveWidget(sections, "qw", "w-score", toIndex = -1))
    }
}
