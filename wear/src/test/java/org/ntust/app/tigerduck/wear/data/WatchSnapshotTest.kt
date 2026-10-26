package org.ntust.app.tigerduck.wear.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.NextClassResult
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
}

