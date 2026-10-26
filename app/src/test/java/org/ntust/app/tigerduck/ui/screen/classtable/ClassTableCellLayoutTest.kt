package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
        assertEquals(listOf("1", "2", "3", "4", "6", "7", "8", "9", "10"), ids)
    }

    @Test
    fun `activePeriods widens for a period outside the default visible set`() {
        // "5" and "A" are not default-visible; scheduling them must reveal them,
        // in chronological order rather than appended at the end.
        val c = course("X", mapOf(1 to listOf("5", "A")))
        val ids = ClassTableCellLayout.activePeriods(listOf(c)).map { it.id }
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "A"), ids)
    }

    @Test
    fun `activeWeekdays keeps the working week and adds weekend days only when used`() {
        assertEquals(listOf(1, 2, 3, 4, 5), ClassTableCellLayout.activeWeekdays(emptyList()))
        val sat = course("S", mapOf(6 to listOf("1")))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), ClassTableCellLayout.activeWeekdays(listOf(sat)))
    }

    // ---- a course meeting twice ----

    @Test
    fun `a course whose two blocks another course bridges keeps both`() {
        // A meets on 1-2 and 4-5, B on 2-4: one cluster down to period 5, with A's second block
        // in it (https://github.com/tigerduck-app/tigerduck-app/issues/470).
        val courses = listOf(
            course("A", mapOf(1 to listOf("1", "2", "4", "5"))),
            course("B", mapOf(1 to listOf("2", "3", "4"))),
        )
        val role = roleAt(courses, 1, "1")
        assertTrue("$role", role is CellRole.MultiConflictStart)
        role as CellRole.MultiConflictStart
        assertEquals(5, role.combinedSpan)
        assertEquals(
            listOf(Triple("A", 0, 2), Triple("B", 1, 3), Triple("A", 3, 2)),
            role.members.map { Triple(it.course.courseNo, it.offset, it.span) },
        )
        assertEquals(listOf("1", "2", "4"), role.members.map { it.firstPeriodId })
        assertEquals(CellRole.Skip, roleAt(courses, 1, "5"))
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

    // ---- lane runs (tigerduck-app-android#85, as the iPhone's tigerduck-app#153) ----

    private fun member(no: String, span: Int, offset: Int, lane: Int) =
        CellRole.MultiConflictStart.Member(course(no, emptyMap()), span, offset, lane, firstPeriodId = "1")

    private fun runsOf(role: CellRole.MultiConflictStart, no: String) =
        ClassTableCellLayout.laneRuns(role.members.first { it.course.courseNo == no }, role)

    @Test
    fun `a chain's end widens across every lane where it is alone`() {
        // A on 6-7, B on 6-8, C on 8-9: C meets B on 8 and is alone on 9.
        val courses = listOf(
            course("A", mapOf(1 to listOf("6", "7"))),
            course("B", mapOf(1 to listOf("6", "7", "8"))),
            course("C", mapOf(1 to listOf("8", "9"))),
        )
        val role = roleAt(courses, 1, "6") as CellRole.MultiConflictStart
        val laneOf = role.members.associate { it.course.courseNo to it.lane }

        assertEquals(listOf(LaneRun(0, 2, laneOf.getValue("A")..laneOf.getValue("A"))), runsOf(role, "A"))
        assertEquals(listOf(LaneRun(0, 3, laneOf.getValue("B")..laneOf.getValue("B"))), runsOf(role, "B"))
        assertEquals(
            listOf(LaneRun(2, 1, laneOf.getValue("C")..laneOf.getValue("C")), LaneRun(3, 1, 0..1)),
            runsOf(role, "C"),
        )
    }

    @Test
    fun `a long course beside two short ones widens in each row it has alone`() {
        val role = CellRole.MultiConflictStart(
            members = listOf(member("L", span = 4, offset = 0, lane = 0), member("S", 1, 0, 1), member("T", 1, 2, 1)),
            combinedSpan = 4,
            laneCount = 2,
        )
        assertEquals(
            listOf(LaneRun(0, 1, 0..0), LaneRun(1, 1, 0..1), LaneRun(2, 1, 0..0), LaneRun(3, 1, 0..1)),
            runsOf(role, "L"),
        )
        assertEquals(listOf(LaneRun(0, 1, 1..1)), runsOf(role, "S"))
        assertEquals(listOf(LaneRun(2, 1, 1..1)), runsOf(role, "T"))
    }

    @Test
    fun `courses that always meet keep their own lanes`() {
        val role = CellRole.MultiConflictStart(
            members = listOf(member("A", 2, 0, 0), member("B", 2, 0, 1), member("C", 2, 0, 2)),
            combinedSpan = 2,
            laneCount = 3,
        )
        listOf("A" to 0, "B" to 1, "C" to 2).forEach { (no, lane) ->
            assertEquals(no, listOf(LaneRun(0, 2, lane..lane)), runsOf(role, no))
        }
    }

    // ---- the outline around a course's runs ----

    @Test
    fun `a course that widens is one outline, its seam bridged across the width the runs share`() {
        // Its own lane on top, then both lanes, a 1px seam between the two.
        val strips = ConflictLaneOutline.strips(listOf(Rect(0f, 0f, 10f, 10f), Rect(0f, 11f, 21f, 21f)))
        assertEquals(listOf(Rect(0f, 0f, 10f, 10f), Rect(0f, 10f, 10f, 11f), Rect(0f, 11f, 21f, 21f)), strips)
        assertEquals(
            listOf(Offset(10f, 0f), Offset(10f, 11f), Offset(21f, 11f), Offset(21f, 21f), Offset(0f, 21f), Offset(0f, 0f)),
            ConflictLaneOutline.corners(strips),
        )
    }

    @Test
    fun `one run is a plain rectangle`() {
        val strips = ConflictLaneOutline.strips(listOf(Rect(2f, 3f, 12f, 30f)))
        assertEquals(
            listOf(Offset(12f, 3f), Offset(12f, 30f), Offset(2f, 30f), Offset(2f, 3f)),
            ConflictLaneOutline.corners(strips),
        )
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

    // ---- contentKey: which cells fade on a switch ----

    @Test
    fun `a course both class tables have at the same place keeps its key, whatever its colour`() {
        val mine = course("CS1", mapOf(1 to listOf("2", "3")))
        val theirs = mine.copy(customColorHex = "#E57373")

        val a = ClassTableCellLayout.contentKey(roleAt(listOf(mine), 1, "2"), "TR-313")
        val b = ClassTableCellLayout.contentKey(roleAt(listOf(theirs), 1, "2"), "TR-313")

        assertEquals(a, b)
    }

    @Test
    fun `another course, name, room or span makes a new key`() {
        val base = course("CS1", mapOf(1 to listOf("2", "3")))
        fun key(c: Course, room: String? = null) = ClassTableCellLayout.contentKey(roleAt(listOf(c), 1, "2"), room)

        val keys = listOf(
            key(base),
            key(course("CS2", mapOf(1 to listOf("2", "3")))),
            key(base.copy(customCourseName = "Renamed")),
            key(base, room = "TR-313"),
            key(course("CS1", mapOf(1 to listOf("2", "3", "4")))),
        )

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `slots with no course of their own have no key, and conflicts have one`() {
        val a = course("A", mapOf(1 to listOf("2", "3")))
        val b = course("B", mapOf(1 to listOf("3", "4")))

        assertNull(ClassTableCellLayout.contentKey(CellRole.Empty, null))
        assertNull(ClassTableCellLayout.contentKey(CellRole.Skip, null))
        assertNotNull(ClassTableCellLayout.contentKey(roleAt(listOf(a, b), 1, "2"), null))
    }
}

