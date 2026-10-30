package org.ntust.app.tigerduck.wear.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.NextClassResult
import org.ntust.app.tigerduck.shared.WatchClassChange
import org.ntust.app.tigerduck.shared.WatchCourseChanges
import java.time.LocalDate

/** What the Now and Next, Today, tile and complication read from a snapshot. */
class WatchSnapshotTest {

    private val monday = LocalDate.of(2026, 10, 12)
    private val tuesday = monday.plusDays(1)

    private val mondayCourse = Course.fromSchedule("CN", "Networks", schedule = mapOf(1 to listOf("1", "2")))
    private val tuesdayCourse = Course.fromSchedule("DB", "Databases", schedule = mapOf(2 to listOf("3", "4")))
    private val wednesdayCourse = Course.fromSchedule("OS", "Systems", schedule = mapOf(3 to listOf("1")))

    private fun snapshot(quietDays: Set<LocalDate>) = WatchSnapshot(
        courses = listOf(mondayCourse, tuesdayCourse, wednesdayCourse),
        accentHex = SchedulePersistence.DEFAULT_ACCENT,
        syncedAtMs = 1_000L,
        loggedIn = true,
        languageTag = null,
        quietDays = quietDays,
    )

    @Test
    fun `without quiet days the day's classes show as before`() {
        val snap = snapshot(emptySet())
        assertEquals(listOf("CN"), snap.todaysClasses(monday, 8 * 60 + 30).map { it.course.courseNo })
        val now = snap.nextClass(monday, 8 * 60 + 30)
        assertTrue(now is NextClassResult.Ongoing)
        assertEquals("CN", (now as NextClassResult.Ongoing).course.courseNo)
    }

    @Test
    fun `on a quiet day today lists nothing and the next class is the next class day's`() {
        val snap = snapshot(setOf(monday))
        assertTrue(snap.todaysClasses(monday, 8 * 60 + 30).isEmpty())
        val next = snap.nextClass(monday, 8 * 60 + 30)
        assertTrue(next is NextClassResult.NextFuture)
        next as NextClassResult.NextFuture
        assertEquals("DB", next.course.courseNo)
        assertEquals(1, next.daysAhead)
    }

    @Test
    fun `a quiet day ahead is passed over for the next class`() {
        val next = snapshot(setOf(tuesday)).nextClass(monday, 22 * 60)
        assertTrue(next is NextClassResult.NextFuture)
        next as NextClassResult.NextFuture
        assertEquals("OS", next.course.courseNo)
        assertEquals(2, next.daysAhead)
    }

    // ---- a course meeting twice a day ----

    // Thursday 2026-10-15: periods 1-2 in TR-513, 6-7 in TR-215.
    private val thursday = monday.plusDays(3)
    private val twice = Course.fromSchedule(
        courseNo = "CS3005301",
        courseName = "Data Structures",
        classroom = "TR-513, TR-215",
        schedule = mapOf(4 to listOf("1", "2", "6", "7"), 5 to listOf("1", "2", "6", "7")),
        classroomMap = mapOf(
            "4-1" to "TR-513", "4-2" to "TR-513", "4-6" to "TR-215", "4-7" to "TR-215",
            "5-1" to "TR-513", "5-2" to "TR-513", "5-6" to "TR-215", "5-7" to "TR-215",
        ),
    )

    private fun twiceSnapshot() = WatchSnapshot(
        courses = listOf(twice),
        accentHex = SchedulePersistence.DEFAULT_ACCENT,
        syncedAtMs = 1_000L,
        loggedIn = true,
        languageTag = null,
        quietDays = emptySet(),
    )

    @Test
    fun `the class going on shows the room of its block`() {
        val now = twiceSnapshot().nextClass(thursday, 14 * 60) as NextClassResult.Ongoing
        assertEquals("TR-215", now.classroom)
    }

    @Test
    fun `the next class today shows the room of the block that starts next`() {
        val next = twiceSnapshot().nextClass(thursday, 12 * 60) as NextClassResult.NextToday
        assertEquals(13 * 60 + 20, next.startMinute)
        assertEquals("TR-215", next.classroom)
    }

    @Test
    fun `the next class day shows the room of its first block`() {
        val next = twiceSnapshot().nextClass(thursday, 18 * 60) as NextClassResult.NextFuture
        assertEquals("TR-513", next.classroom)
    }

    @Test
    fun `today lists each block with its own room`() {
        assertEquals(
            listOf("TR-513", "TR-215"),
            twiceSnapshot().todaysClasses(thursday, 7 * 60).map { it.classroom },
        )
    }

    // ---- the school's changes ----

    // Thursday 2026-11-05: course A meets 2-4 (09:10-12:10) in TR-313. That day period 2 is
    // cancelled and 3-4 starts at 10:40 in TR-411.
    private val changeDay = LocalDate.of(2026, 11, 5)
    private val meetingA = Course.fromSchedule("A", "A", classroom = "TR-313", schedule = mapOf(4 to listOf("2", "3", "4")))
    private val movedA = WatchClassChange(listOf("3", "4"), room = "TR-411", start = "10:40")

    private fun changedSnapshot() = WatchSnapshot(
        courses = listOf(meetingA),
        accentHex = SchedulePersistence.DEFAULT_ACCENT,
        syncedAtMs = 1_000L,
        loggedIn = true,
        languageTag = null,
        quietDays = emptySet(),
        courseChanges = WatchCourseChanges(
            mapOf("A" to mapOf(changeDay to listOf(WatchClassChange(listOf("2"), cancelled = true), movedA))),
        ),
    )

    /** Ported from iOS `NextClassResolverTests.test_aDaysClassesReplaceTheMeetingAndACancelledClassDrops`. */
    @Test
    fun `a day's classes replace the meeting and a cancelled class drops`() {
        val today = changedSnapshot().todaysClasses(changeDay, 8 * 60)
        assertEquals(listOf(listOf("3", "4")), today.map { it.periods })
        assertEquals("TR-411", today.single().classroom)
        assertEquals("10:40", today.single().change?.start)
        assertEquals(10 * 60 + 40, today.single().startMinute)

        val duringPeriod2 = changedSnapshot().nextClass(changeDay, 9 * 60 + 20)
        duringPeriod2 as NextClassResult.NextToday
        assertEquals(listOf("3", "4"), duringPeriod2.periods)
        assertEquals(10 * 60 + 40, duringPeriod2.startMinute)
        assertEquals("TR-411", duringPeriod2.classroom)

        val later = changedSnapshot().todaysClasses(changeDay.plusDays(7), 8 * 60)
        assertEquals(listOf(listOf("2", "3", "4")), later.map { it.periods })
        assertTrue(later.all { it.change == null })
        assertEquals("TR-313", later.single().classroom)
    }

    @Test
    fun `the class going on is the changed one, in its changed room`() {
        val now = changedSnapshot().nextClass(changeDay, 11 * 60) as NextClassResult.Ongoing
        assertEquals(listOf("3", "4"), now.periods)
        assertEquals(10 * 60 + 40, now.startMinute)
        assertEquals(12 * 60 + 10, now.endMinute)
        assertEquals("TR-411", now.classroom)
        assertEquals(movedA, now.change)
    }

    @Test
    fun `the next class day's changed class is the one shown`() {
        val next = changedSnapshot().nextClass(changeDay.minusDays(1), 20 * 60) as NextClassResult.NextFuture
        assertEquals(1, next.daysAhead)
        assertEquals(10 * 60 + 40, next.startMinute)
        assertEquals("TR-411", next.classroom)
    }
}
