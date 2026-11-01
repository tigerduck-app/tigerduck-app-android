package org.ntust.app.tigerduck.liveactivity

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.changes
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.row
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import org.ntust.app.tigerduck.util.JvmContext
import java.util.Date

/**
 * Which of today's classes the Live Update works from: the one going on, the one coming up inside
 * the lead time, and the ones the boundary scheduler still has ahead. CC900 meets on Thursday
 * 2026-11-05, periods 2 to 4 (09:10-12:10) and 7 to 8 (14:20-16:20), in T4-501.
 */
class LiveActivityClassesTodayTest {

    private lateinit var preferences: LiveActivityPreferences

    @Before
    fun setUp() {
        preferences = LiveActivityPreferences(JvmContext())
        preferences.classPreparingLeadTimeSec = 600
    }

    private fun thursdayAt(time: String): Date = Date.from(CourseChangeFixture.at(time))

    /** What the Live Update shows for [courses] at [time] on Thursday, or "none". */
    private fun shown(
        time: String,
        changes: CourseChanges = CourseChanges.EMPTY,
        courses: List<Course> = listOf(CourseChangeFixture.course()),
        skippedDates: Map<String, List<String>> = emptyMap(),
    ): String {
        val snapshot = LiveActivityResolver().resolve(
            courses = courses,
            assignments = emptyList(),
            skippedDates = skippedDates,
            preferences = preferences,
            accentHex = 0,
            now = thursdayAt(time),
            changes = changes,
        ) ?: return "none"
        return listOf(
            snapshot.scenario, snapshot.sourceId, snapshot.subtitle, snapshot.locationText,
            "target=${snapshot.countdownTarget?.toInstant()}", "progress=${snapshot.progress}",
        ).joinToString(" | ")
    }

    /** The slots the boundary scheduler has ahead at [time], as `periods start-end room`. */
    private fun ahead(
        time: String,
        changes: CourseChanges = CourseChanges.EMPTY,
        courses: List<Course> = listOf(CourseChangeFixture.course()),
        skippedDates: Map<String, List<String>> = emptyMap(),
    ): List<String> =
        LiveActivityResolver().todaySlotsAfter(courses, thursdayAt(time), skippedDates, changes)
            .map { "${it.periods.joinToString("")} ${it.start.toInstant()}..${it.end.toInstant()} ${it.classroom}" }

    private val endedEarly = changes(row(1, "end", periods = listOf("2", "3", "4"), values = mapOf("end" to "11:45")))

    private val moments = listOf("11:19", "11:20", "12:09", "12:10")

    @Test
    fun `the four moments on the timetable's end`() {
        assertEquals(
            listOf(
                "IN_CLASS | class-CC900-2 | 09:10–12:10 | T4-501 | target=2026-11-05T04:10:00Z | progress=0.7166666666666667",
                "IN_CLASS | class-CC900-2 | 09:10–12:10 | T4-501 | target=2026-11-05T04:10:00Z | progress=0.7222222222222222",
                "IN_CLASS | class-CC900-2 | 09:10–12:10 | T4-501 | target=2026-11-05T04:10:00Z | progress=0.9944444444444445",
                "none",
            ),
            moments.map { shown(it) },
        )
        assertEquals(
            listOf(
                listOf("234 2026-11-05T01:10:00Z..2026-11-05T04:10:00Z T4-501", "78 2026-11-05T06:20:00Z..2026-11-05T08:20:00Z T4-501"),
                listOf("234 2026-11-05T01:10:00Z..2026-11-05T04:10:00Z T4-501", "78 2026-11-05T06:20:00Z..2026-11-05T08:20:00Z T4-501"),
                listOf("234 2026-11-05T01:10:00Z..2026-11-05T04:10:00Z T4-501", "78 2026-11-05T06:20:00Z..2026-11-05T08:20:00Z T4-501"),
                listOf("78 2026-11-05T06:20:00Z..2026-11-05T08:20:00Z T4-501"),
            ),
            moments.map { ahead(it) },
        )
    }

    @Test
    fun `the four moments on the changed end at 11_45`() {
        assertEquals(
            listOf(
                "IN_CLASS | class-CC900-2 | 09:10–11:45 | T4-501 | target=2026-11-05T03:45:00Z | progress=0.832258064516129",
                "IN_CLASS | class-CC900-2 | 09:10–11:45 | T4-501 | target=2026-11-05T03:45:00Z | progress=0.8387096774193549",
                "none",
                "none",
            ),
            moments.map { shown(it, endedEarly) },
        )
        assertEquals(
            listOf(
                listOf("234 2026-11-05T01:10:00Z..2026-11-05T03:45:00Z T4-501", "78 2026-11-05T06:20:00Z..2026-11-05T08:20:00Z T4-501"),
                listOf("234 2026-11-05T01:10:00Z..2026-11-05T03:45:00Z T4-501", "78 2026-11-05T06:20:00Z..2026-11-05T08:20:00Z T4-501"),
                listOf("78 2026-11-05T06:20:00Z..2026-11-05T08:20:00Z T4-501"),
                listOf("78 2026-11-05T06:20:00Z..2026-11-05T08:20:00Z T4-501"),
            ),
            moments.map { ahead(it, endedEarly) },
        )
    }

    @Test
    fun `the next class shows only inside the lead time, and one starting this minute is going on`() {
        assertEquals("none", shown("14:09"))
        assertEquals(
            "CLASS_PREPARING | prep-CC900-7 | 14:20–16:20 | T4-501 | target=2026-11-05T06:20:00Z | progress=null",
            shown("14:10"),
        )
        assertEquals(
            "IN_CLASS | class-CC900-7 | 14:20–16:20 | T4-501 | target=2026-11-05T08:20:00Z | progress=0.0",
            shown("14:20"),
        )
    }

    @Test
    fun `a skipped date leaves the course's classes out, and only that course's`() {
        val other = Course.fromSchedule("CC901", "Networks", schedule = mapOf(4 to listOf("3")))
        val skipped = mapOf("CC900" to listOf("2026-11-05"), "CC901" to listOf("2026-11-12"))
        val both = listOf(CourseChangeFixture.course(), other)

        assertEquals("none", shown("10:00", courses = listOf(CourseChangeFixture.course()), skippedDates = skipped))
        assertEquals(
            listOf("3 2026-11-05T02:20:00Z..2026-11-05T03:10:00Z "),
            ahead("07:00", courses = both, skippedDates = skipped),
        )
        assertEquals(
            "IN_CLASS | class-CC901-3 | 10:20–11:10 |  | target=2026-11-05T03:10:00Z | progress=0.0",
            shown("10:20", courses = both, skippedDates = skipped).replace("null", ""),
        )
    }

    @Test
    fun `a cancelled class is passed over, and a moved one runs at its changed start`() {
        val cancelledMorning = changes(
            row(1, "cancelled", periods = listOf("2", "3", "4")),
            row(2, "start", periods = listOf("7", "8"), values = mapOf("start" to "14:00")),
        )

        assertEquals("none", shown("10:00", cancelledMorning))
        assertEquals(
            "CLASS_PREPARING | prep-CC900-7 | 14:00–16:20 | T4-501 | target=2026-11-05T06:00:00Z | progress=null",
            shown("13:50", cancelledMorning),
        )
        assertEquals(
            listOf("78 2026-11-05T06:00:00Z..2026-11-05T08:20:00Z T4-501"),
            ahead("07:00", cancelledMorning),
        )
    }

    @Test
    fun `a split meeting runs as its pieces, each in its own room`() {
        val split = changes(row(1, "room", periods = listOf("4"), values = mapOf("room" to "TR-313")))

        assertEquals(
            listOf(
                "23 2026-11-05T01:10:00Z..2026-11-05T03:10:00Z T4-501",
                "4 2026-11-05T03:20:00Z..2026-11-05T04:10:00Z TR-313",
                "78 2026-11-05T06:20:00Z..2026-11-05T08:20:00Z T4-501",
            ),
            ahead("07:00", split),
        )
        assertEquals(
            "IN_CLASS | class-CC900-4 | 11:20–12:10 | TR-313 | target=2026-11-05T04:10:00Z | progress=0.4",
            shown("11:40", split),
        )
    }

    @Test
    fun `contiguous periods are one class and a gap makes two`() {
        val contiguous = listOf(CourseChangeFixture.course(listOf("3", "4")))
        val gap = listOf(CourseChangeFixture.course(listOf("3", "5")))

        assertEquals(listOf("34 2026-11-05T02:20:00Z..2026-11-05T04:10:00Z T4-501"), ahead("07:00", courses = contiguous))
        assertEquals(
            listOf(
                "3 2026-11-05T02:20:00Z..2026-11-05T03:10:00Z T4-501",
                "5 2026-11-05T04:20:00Z..2026-11-05T05:10:00Z T4-501",
            ),
            ahead("07:00", courses = gap),
        )
    }

    @Test
    fun `a period with no known time is left out, and the rest of its course still meets`() {
        val unknownOnly = listOf(CourseChangeFixture.course(listOf("E")))
        val mixed = listOf(CourseChangeFixture.course(listOf("4", "E")))

        assertEquals(emptyList<String>(), ahead("07:00", courses = unknownOnly))
        assertEquals("none", shown("11:30", courses = unknownOnly))
        assertEquals(listOf("4 2026-11-05T03:20:00Z..2026-11-05T04:10:00Z T4-501"), ahead("07:00", courses = mixed))
        assertEquals(
            "IN_CLASS | class-CC900-4 | 11:20–12:10 | T4-501 | target=2026-11-05T04:10:00Z | progress=0.0",
            shown("11:20", courses = mixed),
        )
    }
}
