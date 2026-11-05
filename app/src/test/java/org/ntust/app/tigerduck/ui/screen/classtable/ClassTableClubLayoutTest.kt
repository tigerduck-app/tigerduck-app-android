package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.clubs.ClubActivityKind
import org.ntust.app.tigerduck.clubs.ClubOccurrence
import org.ntust.app.tigerduck.data.model.TimetablePeriod
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableClubLayout.Interval
import java.time.LocalDate
import kotlin.math.abs

/** Where the clubs' activities go on the class table, with the iPhone's cases (ClassTableClubLayoutTests). */
class ClassTableClubLayoutTest {

    private val cellHeight = 52f
    private val rowPitch = 55f
    private val periods = TimetablePeriod.all.map(ClassTableClubLayout::times)

    private fun row(period: String) = AppConstants.Periods.chronologicalOrder.indexOf(period) * rowPitch

    private fun minutes(hm: String) = hm.split(":").let { (h, m) -> h.toInt() * 60 + m.toInt() }

    private fun club(id: Int, priority: Int, from: String, to: String, day: String = "2026-11-09") = ClubOccurrence(
        id = id, clubId = 9000 + priority, clubName = "測試社團$priority", color = 0x123456, priority = priority, day = day,
        start = minutes(from), end = minutes(to), location = "TR-000", kind = ClubActivityKind.Event, name = "Practice", content = "",
    )

    private fun course(no: String, weekday: Int, vararg periods: String) =
        Course.fromSchedule(courseNo = no, courseName = no, schedule = mapOf(weekday to periods.toList()))

    private fun span(segment: ClassTableClubLayout.Segment) = "${segment.occurrence.id} ${segment.start}-${segment.end}"

    private fun close(expected: Float, actual: Float) = assertTrue("$actual is not $expected", abs(expected - actual) < 0.001f)

    private fun blocks(day: List<ClubOccurrence>, courses: List<Interval>) =
        ClassTableClubLayout.blocks(ClassTableClubLayout.segments(day, courses), periods, rowPitch, cellHeight)

    // Monday: a course in period A, a club from 19:00 to 21:00 and a lower one to 21:30.
    private val mondayCourses = ClassTableClubLayout.courseTimes(listOf(course("TEST0001", 1, "A")), weekday = 1)
    private val monday = listOf(club(1, 0, "19:00", "21:00"), club(2, 1, "19:00", "21:30"))

    @Test
    fun `a course wins, and a lower club shows only where the higher one has ended`() {
        assertEquals(listOf("1 1155-1260", "2 1260-1290"), ClassTableClubLayout.segments(monday, mondayCourses).map(::span))

        val (first, lower) = blocks(monday, mondayCourses)
        assertEquals(row("B"), first.top)
        close(row("C") + 52f * 45 / 50, first.top + first.height)
        assertTrue(first.showsName && first.showsLocation)
        close(first.top + first.height + 3, lower.top)
        close(row("D") + 52f * 20 / 50, lower.top + lower.height)
        assertTrue(lower.showsName)
        assertFalse(lower.showsLocation)
    }

    @Test
    fun `two clubs are cut short of a course, a piece left with no room is not drawn, and a short one goes without its name`() {
        val courses = ClassTableClubLayout.courseTimes(listOf(course("TEST0002", 3, "B", "C")), weekday = 3)
        val day = listOf(club(3, 5, "18:30", "21:00"), club(4, 1, "19:00", "21:00"))

        assertEquals(listOf("4 1140-1160", "4 1210-1215", "3 1110-1140"), ClassTableClubLayout.segments(day, courses).map(::span))

        val drawn = blocks(day, courses)
        assertEquals(listOf(4, 3), drawn.map { it.segment.occurrence.id })
        val (higher, lower) = drawn
        close(row("A") + 52f * 35 / 50, higher.top)
        close(row("B") - 3, higher.top + higher.height)
        assertFalse(higher.showsName)
        close(row("A") + 52f * 5 / 50, lower.top)
        close(higher.top - 3, lower.top + lower.height)
        assertTrue(lower.showsName && !lower.showsLocation)
    }

    @Test
    fun `an activity starts at its share of the period it starts in`() {
        close(row("A") + 52f * 0.7f, blocks(listOf(club(5, 0, "19:00", "20:30")), emptyList()).single().top)
        close(row("10") + 52f * 0.6f, blocks(listOf(club(7, 0, "18:00", "20:00")), emptyList()).single().top)
    }

    @Test
    fun `an activity wholly under a course or a higher club is not drawn`() {
        val under = monday.map { it.copy(start = minutes("19:30"), end = minutes("20:00")) }
        assertEquals(listOf(1), ClassTableClubLayout.segments(under, mondayCourses).map { it.occurrence.id })
        assertEquals(emptyList<String>(), ClassTableClubLayout.segments(under, listOf(Interval(minutes("19:00"), minutes("21:00")))).map(::span))
    }

    @Test
    fun `a time outside the periods is held to the first row's top and the last row's bottom`() {
        fun position(time: String) = ClassTableClubLayout.position(minutes(time), periods, rowPitch, cellHeight)
        assertEquals(0f, position("07:00"))
        assertEquals(row("D") + 52, position("23:30"))
        close(row("B") - 3 + 3f * 2 / 5, position("19:17"))
        // On the phone's grid the rows touch, and a time between two periods is the next one's top.
        assertEquals(row("B") / rowPitch * 52, ClassTableClubLayout.position(minutes("19:17"), periods, 52f, 52f))
    }

    @Test
    fun `a tap lists the drawn activities that meet the tapped one, highest ranked first`() {
        val drawn = blocks(monday, mondayCourses).map { it.segment.occurrence }
        assertEquals(listOf(1, 2), ClassTableClubLayout.overlapping(drawn.last(), drawn).map { it.id })
        val alone = listOf(club(5, 0, "19:00", "20:30"), club(6, 1, "12:20", "13:00"))
        assertEquals(listOf(5), ClassTableClubLayout.overlapping(alone.first(), alone).map { it.id })
    }

    @Test
    fun `a club's activity opens the rows it meets and its weekend column`() {
        val clubs = mapOf(5 to listOf(club(6, 0, "12:20", "13:00")), 6 to listOf(club(8, 1, "14:00", "17:00")))
        val base = ClassTableCellLayout.activePeriods(emptyList())

        assertEquals(listOf(1, 2, 3, 4, 5, 6), ClassTableClubLayout.activeWeekdays(listOf(1, 2, 3, 4, 5), clubs))
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"),
            ClassTableClubLayout.activePeriods(base, clubs).map { it.id },
        )
        assertEquals(base, ClassTableClubLayout.activePeriods(base, emptyMap()))
        assertEquals(listOf(1, 2, 3, 4, 5), ClassTableClubLayout.activeWeekdays(listOf(1, 2, 3, 4, 5), mapOf(7 to emptyList())))
    }

    @Test
    fun `each activity goes to the column whose date it is on, in the week from today`() {
        val wednesday = LocalDate.of(2026, 11, 4)
        val shown = ClassTableClubLayout.byWeekday(
            listOf(
                club(1, 0, "19:00", "20:00", day = "2026-11-02"),
                club(2, 0, "19:00", "20:00", day = "2026-11-04"),
                club(3, 0, "19:00", "20:00", day = "2026-11-09"),
                club(4, 0, "19:00", "20:00", day = "2026-11-10"),
                club(5, 0, "19:00", "20:00", day = "2026-11-11"),
            ),
            today = wednesday,
        )
        assertEquals(mapOf(3 to listOf(2), 1 to listOf(3), 2 to listOf(4)), shown.mapValues { (_, day) -> day.map { it.id } })
        assertEquals((4..10).map { "2026-11-%02d".format(it) }.toSet(), ClassTableClubLayout.weekDays(wednesday))
    }
}
