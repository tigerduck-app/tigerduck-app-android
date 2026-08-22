package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course

/**
 * Grid geometry used to live inside ClassTableViewModel, where it could not
 * be reached without Hilt and an Android runtime — so the trickiest layout
 * code in the app shipped with no coverage at all. These are the cases that
 * were previously only verifiable by looking at a phone.
 */
class ClassTableCellLayoutTest {

    private fun course(no: String, schedule: Map<Int, List<String>>) = Course(
        courseNo = no,
        courseName = "Course $no",
        scheduleJson = schedule.entries.joinToString(",", "{", "}") { (day, periods) ->
            "\"$day\":[" + periods.joinToString(",") { "\"$it\"" } + "]"
        },
    )

    private fun periodsFor(courses: List<Course>) = ClassTableCellLayout.activePeriods(courses)
    private fun indexOf(courses: List<Course>, id: String) =
        periodsFor(courses).indexOfFirst { it.id == id }

    private fun roleAt(courses: List<Course>, weekday: Int, periodId: String) =
        ClassTableCellLayout.roleAt(
            courses, periodsFor(courses), weekday, indexOf(courses, periodId)
        )

    // ---- activePeriods ----

    @Test
    fun `activePeriods covers the default set when nothing unusual is scheduled`() {
        val ids = ClassTableCellLayout.activePeriods(emptyList()).map { it.id }
        assertEquals(listOf("1", "2", "3", "4", "6", "7", "8", "9"), ids)
    }

    @Test
    fun `activePeriods widens for a period outside the default visible set`() {
        // "5" and "A" are not default-visible; scheduling them must reveal them,
        // in chronological order rather than appended at the end.
        val c = course("X", mapOf(1 to listOf("5", "A")))
        val ids = ClassTableCellLayout.activePeriods(listOf(c)).map { it.id }
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "A"), ids)
    }

    @Test
    fun `activeWeekdays keeps the working week and adds weekend days only when used`() {
        assertEquals(listOf(1, 2, 3, 4, 5), ClassTableCellLayout.activeWeekdays(emptyList()))
        val sat = course("S", mapOf(6 to listOf("1")))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), ClassTableCellLayout.activeWeekdays(listOf(sat)))
    }

    // ---- solo blocks ----

    @Test
    fun `a contiguous two-period course starts once and skips its second row`() {
        val courses = listOf(course("A", mapOf(1 to listOf("3", "4"))))
        val start = roleAt(courses, 1, "3")
        assertTrue("$start", start is CellRole.SoloStart)
        assertEquals(2, (start as CellRole.SoloStart).spanCount)
        assertEquals(CellRole.Skip, roleAt(courses, 1, "4"))
    }

    @Test
    fun `a gap splits one course into two independent blocks`() {
        // Periods 1 and 3 with 2 free between them: two SoloStarts, not a span of 3.
        val courses = listOf(course("A", mapOf(1 to listOf("1", "3"))))
        val first = roleAt(courses, 1, "1")
        val second = roleAt(courses, 1, "3")
        assertEquals(1, (first as CellRole.SoloStart).spanCount)
        assertEquals(1, (second as CellRole.SoloStart).spanCount)
        assertEquals(CellRole.Empty, roleAt(courses, 1, "2"))
    }

    @Test
    fun `contiguity follows visible-row adjacency, not period-number adjacency`() {
        // Period "5" is not default-visible, so with nothing scheduled there,
        // rows 4 and 6 are neighbours and this is ONE block of two.
        val courses = listOf(course("A", mapOf(1 to listOf("4", "6"))))
        val role = roleAt(courses, 1, "4")
        assertEquals(2, (role as CellRole.SoloStart).spanCount)
        assertEquals(CellRole.Skip, roleAt(courses, 1, "6"))
    }

    @Test
    fun `scheduling the in-between period breaks that adjacency apart`() {
        // Same course, but another course occupies "5", so "5" becomes a visible
        // row — 4 and 6 are no longer neighbours and the block splits in two.
        val courses = listOf(
            course("A", mapOf(1 to listOf("4", "6"))),
            course("B", mapOf(2 to listOf("5"))),
        )
        assertEquals(1, (roleAt(courses, 1, "4") as CellRole.SoloStart).spanCount)
        assertEquals(1, (roleAt(courses, 1, "6") as CellRole.SoloStart).spanCount)
    }

    // ---- 衝堂 ----

    @Test
    fun `two overlapping courses render as one conflict tile with per-course offsets`() {
        val courses = listOf(
            course("A", mapOf(1 to listOf("3", "4"))),
            course("B", mapOf(1 to listOf("4"))),
        )
        val role = roleAt(courses, 1, "3")
        assertTrue("$role", role is CellRole.ConflictStart)
        role as CellRole.ConflictStart
        assertEquals(2, role.combinedSpan)
        val a = listOf(role.courseA, role.courseB).first { it.courseNo == "A" }
        val offsetA = if (role.courseA == a) role.offsetA else role.offsetB
        val offsetB = if (role.courseA == a) role.offsetB else role.offsetA
        assertEquals("A starts at the top of the cluster", 0, offsetA)
        assertEquals("B starts one row down", 1, offsetB)
        assertEquals(CellRole.Skip, roleAt(courses, 1, "4"))
    }

    @Test
    fun `three courses chained through a bridge become one multi-conflict cluster`() {
        // A on 6-7, B on 6-8, C on 8-9. A and C share no period at all; only B
        // connects them. All three still have to be laid out as one cluster.
        val courses = listOf(
            course("A", mapOf(1 to listOf("6", "7"))),
            course("B", mapOf(1 to listOf("6", "7", "8"))),
            course("C", mapOf(1 to listOf("8", "9"))),
        )
        val role = roleAt(courses, 1, "6")
        assertTrue("$role", role is CellRole.MultiConflictStart)
        role as CellRole.MultiConflictStart
        assertEquals(3, role.members.size)
        assertEquals("cluster spans rows 6..9", 4, role.combinedSpan)

        val byNo = role.members.associateBy { it.course.courseNo }
        assertEquals(0, byNo.getValue("A").offset)
        assertEquals(0, byNo.getValue("B").offset)
        assertEquals(2, byNo.getValue("C").offset)
        assertEquals(2, byNo.getValue("A").span)
        assertEquals(3, byNo.getValue("B").span)
        assertEquals(2, byNo.getValue("C").span)

        // A and C do not overlap, so greedy lane colouring reuses A's lane for C
        // and only two lanes are needed for three courses.
        assertEquals(2, role.laneCount)
        assertEquals(byNo.getValue("A").lane, byNo.getValue("C").lane)
        assertTrue(byNo.getValue("B").lane != byNo.getValue("A").lane)

        // Every later row of the cluster is a Skip — the tile is drawn once.
        listOf("7", "8", "9").forEach { assertEquals(it, CellRole.Skip, roleAt(courses, 1, it)) }
    }

    @Test
    fun `firstPeriodId points at each member's own first row, not the cluster's`() {
        // The detail popup uses this to resolve the right per-(weekday, period)
        // classroom; taking the cluster start would show the wrong room for C.
        val courses = listOf(
            course("A", mapOf(1 to listOf("6", "7"))),
            course("B", mapOf(1 to listOf("6", "7", "8"))),
            course("C", mapOf(1 to listOf("8", "9"))),
        )
        val role = roleAt(courses, 1, "6") as CellRole.MultiConflictStart
        val byNo = role.members.associateBy { it.course.courseNo }
        assertEquals("6", byNo.getValue("A").firstPeriodId)
        assertEquals("8", byNo.getValue("C").firstPeriodId)
    }

    @Test
    fun `an empty slot and an out-of-range row are both Empty`() {
        val courses = listOf(course("A", mapOf(1 to listOf("3"))))
        assertEquals(CellRole.Empty, roleAt(courses, 2, "3"))
        val periods = periodsFor(courses)
        assertEquals(CellRole.Empty, ClassTableCellLayout.roleAt(courses, periods, 1, -1))
        assertEquals(CellRole.Empty, ClassTableCellLayout.roleAt(courses, periods, 1, periods.size))
    }

    // ---- triple-conflict guard ----

    @Test
    fun `adding a third course to an occupied slot is reported with both incumbents`() {
        val existing = listOf(
            course("A", mapOf(1 to listOf("3"))),
            course("B", mapOf(1 to listOf("3"))),
        )
        val conflict = ClassTableCellLayout.findTripleConflict(
            existing, course("C", mapOf(1 to listOf("3")))
        )
        assertNotNull(conflict)
        assertEquals(1, conflict!!.weekday)
        assertEquals("3", conflict.periodId)
        assertEquals(setOf("A", "B"), setOf(conflict.existingA.courseNo, conflict.existingB.courseNo))
    }

    @Test
    fun `a slot with only one incumbent is not a triple conflict`() {
        val existing = listOf(course("A", mapOf(1 to listOf("3"))))
        assertNull(
            ClassTableCellLayout.findTripleConflict(existing, course("C", mapOf(1 to listOf("3"))))
        )
    }
}
