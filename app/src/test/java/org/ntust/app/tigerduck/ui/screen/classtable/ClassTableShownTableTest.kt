package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.sharing.ImportedClassTables
import org.ntust.app.tigerduck.data.sharing.ImportedPerson
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableSwitcherEntry
import java.time.Instant

/** Whose class table the page draws, the term a switch opens on, the tab's double taps and the switcher's rows. */
class ClassTableShownTableTest {

    private val own = listOf(Course(courseNo = "CS1010001", courseName = "Mine"))
    private val calculus = Course(courseNo = "MA1010001", courseName = "Calculus")
    private val circuits = Course(courseNo = "EE1010002", courseName = "Circuits")

    private val ming = ImportedPerson(
        id = "ming", studentId = "B11300001", nickname = "Ming", importedAt = Instant.EPOCH, sharedAt = null,
        terms = listOf(
            ImportedPerson.Term("1142", listOf("EE1010002"), listOf(circuits)),
            ImportedPerson.Term("1151", listOf("MA1010001"), listOf(calculus)),
        ),
    )
    private val hua = ming.copy(id = "hua", studentId = "B11300002", nickname = "Hua")

    private fun tables(shown: String? = null) = ImportedClassTables(listOf(ming, hua), shown)

    @Test
    fun `the user's own class table shows their courses and terms`() {
        val shown = shownTableOf(own, "1151", listOf("1151", "1142", "1141"), tables(), DisplayedTable(null, null, 3))

        assertNull(shown.person)
        assertEquals(own, shown.courses)
        assertEquals(listOf("1151", "1142", "1141"), shown.semesters)
        assertEquals(3, shown.switches)
    }

    @Test
    fun `someone else's class table shows only their terms, on the one picked`() {
        val shown = shownTableOf(own, "1151", listOf("1151"), tables("ming"), DisplayedTable("ming", "1142", 1))

        assertEquals("Ming", shown.person?.nickname)
        assertTrue(shown.isSomeoneElses)
        assertEquals("1142", shown.semester)
        assertEquals(listOf("1151", "1142"), shown.semesters)
        assertEquals(listOf(circuits), shown.courses)
    }

    @Test
    fun `a person imported again without the term on screen opens on the user's term, or their latest`() {
        val shown = shownTableOf(own, "1151", listOf("1151"), tables("ming"), DisplayedTable("ming", "1132", 1))
        assertEquals("1151", shown.semester)

        val gone = shownTableOf(own, "1151", listOf("1151"), ImportedClassTables(), DisplayedTable("ming", "1142", 1))
        assertNull(gone.person)
        assertEquals(own, gone.courses)
    }

    @Test
    fun `the user's own empty term says so once its first load has settled, and not during a fetch`() {
        val empty = shownTableOf(emptyList(), "1151", listOf("1151"), tables(), DisplayedTable(null, null, 0))

        assertTrue(empty.showsNoCourses(settled = true, isLoading = false))
        assertFalse("before the first load has settled", empty.showsNoCourses(settled = false, isLoading = false))
        assertFalse("while a fetch runs", empty.showsNoCourses(settled = true, isLoading = true))

        val filled = shownTableOf(own, "1151", listOf("1151"), tables(), DisplayedTable(null, null, 0))
        assertFalse(filled.showsNoCourses(settled = true, isLoading = false))
    }

    @Test
    fun `someone else's empty term says so whatever the user's own fetch is doing`() {
        val lookedUpNothing = ming.copy(terms = listOf(ImportedPerson.Term("1151", listOf("MA1010001"), emptyList())))
        val shown = shownTableOf(
            own, "1151", listOf("1151"),
            ImportedClassTables(listOf(lookedUpNothing), "ming"), DisplayedTable("ming", "1151", 1),
        )

        assertTrue(shown.showsNoCourses(settled = false, isLoading = true))
    }

    @Test
    fun `a switch keeps the term on screen when the person shared it, else opens on their latest`() {
        val start = DisplayedTable(null, null, 0)

        val keeps = nextDisplayedTable(start, tables("ming"), shownSemester = "1142")
        assertEquals(DisplayedTable("ming", "1142", 1), keeps)

        val latest = nextDisplayedTable(start, tables("ming"), shownSemester = "1132")
        assertEquals(DisplayedTable("ming", "1151", 1), latest)

        val back = nextDisplayedTable(DisplayedTable("ming", "1151", 1), tables(null), shownSemester = "1151")
        assertEquals(DisplayedTable(null, null, 2), back)
    }

    @Test
    fun `a store change that keeps the class table shown is no switch`() {
        assertNull(nextDisplayedTable(DisplayedTable("ming", "1151", 4), tables("ming"), "1151"))
        assertNull(nextDisplayedTable(DisplayedTable(null, null, 4), tables(null), "1151"))
        // Shown but no longer imported: the user's own.
        assertNull(nextDisplayedTable(DisplayedTable(null, null, 4), ImportedClassTables(emptyList(), "ming"), "1151"))
    }

    @Test
    fun `a double tap within three seconds of the last steps on, and one after a pause swaps back`() {
        var now = 10_000L
        val taps = ClassTableTabDoubleTaps { now }

        assertFalse(taps.stepsOn())
        now += 3_000
        assertTrue(taps.stepsOn())
        now += 2_999
        assertTrue(taps.stepsOn())
        now += 3_001
        assertFalse(taps.stepsOn())
    }

    @Test
    fun `the switcher lists import, the people in import order, then the user, a check on the one shown`() {
        val sections = ClassTableSwitcherEntry.sections(tables("hua"), "B11300000")

        assertEquals(
            listOf(
                listOf(ClassTableSwitcherEntry.ImportClassTable),
                listOf(
                    ClassTableSwitcherEntry.Person("ming", "Ming", isShown = false),
                    ClassTableSwitcherEntry.Person("hua", "Hua", isShown = true),
                ),
                listOf(ClassTableSwitcherEntry.User("B11300000", isShown = false)),
            ),
            sections,
        )
        assertEquals(
            listOf(
                listOf(ClassTableSwitcherEntry.ImportClassTable),
                listOf(ClassTableSwitcherEntry.User("B11300000", isShown = true)),
            ),
            ClassTableSwitcherEntry.sections(ImportedClassTables(), "B11300000"),
        )
    }

    @Test
    fun `a switch scrolls by how far the first visible row moved`() {
        val before = ClassTableRowTops(1, mapOf("1" to 400f, "2" to 500f, "3" to 600f, "4" to 700f))
        // Today's courses left the top of the page, so every row moved up 150.
        val after = ClassTableRowTops(2, mapOf("1" to 250f, "2" to 350f, "3" to 450f, "4" to 550f))

        assertEquals(-150f, ClassTableRowTops.shift(before, after, visibleTop = 450f), 0f)
    }

    @Test
    fun `only rows both class tables have count, and with all above the window the last one does`() {
        // The other class table has a row above the first one both have.
        val before = ClassTableRowTops(1, mapOf("1" to 100f, "2" to 200f))
        val after = ClassTableRowTops(2, mapOf("0" to 100f, "1" to 200f, "2" to 300f))

        assertEquals(100f, ClassTableRowTops.shift(before, after, visibleTop = 150f), 0f)
        assertEquals(100f, ClassTableRowTops.shift(before, after, visibleTop = 900f), 0f)
        assertEquals(0f, ClassTableRowTops.shift(before, ClassTableRowTops(2, mapOf("9" to 1f)), 0f), 0f)
    }
}

