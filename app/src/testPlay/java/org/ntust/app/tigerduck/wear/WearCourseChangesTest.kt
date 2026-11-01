package org.ntust.app.tigerduck.wear

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.NextClassResolver
import org.ntust.app.tigerduck.shared.NextClassResult
import org.ntust.app.tigerduck.shared.TodayClassEntry
import org.ntust.app.tigerduck.shared.WatchClassChange
import org.ntust.app.tigerduck.shared.WatchCourseChanges
import org.ntust.app.tigerduck.shared.WearProtocol
import java.time.LocalDate

/** The classes the school changed, as the phone sends them to the watch. */
class WearCourseChangesTest {

    // Thursdays: 2-3 (09:10-11:10) and 7 (14:20-15:10) in TR-313.
    private val course = Course.fromSchedule(
        courseNo = "CC900",
        courseName = "Fake Course",
        classroom = "TR-313",
        schedule = mapOf(4 to listOf("2", "3", "7")),
    )

    private fun feed(vararg rows: String) = CourseChanges.decode("""{"term":"1151","changes":[${rows.joinToString(",")}]}""")

    /** The day's classes as the watch lists them, read back from what the phone sends. */
    private fun shownOn(sent: String, day: String): List<TodayClassEntry> {
        val changes = WearProtocol.Schedule.decodeCourseChanges(sent)
        val date = LocalDate.parse(day)
        return NextClassResolver.todaysClasses(
            listOf(course), date, minuteOfDay = 0, changes = changes,
        )
    }

    private fun shown(sent: String, day: String): List<String> = shownOn(sent, day).map {
        val label = if (it.periods.size > 1) "${it.periods.first()}-${it.periods.last()}" else it.periods.single()
        "$label ${hm(it.startMinute)}-${hm(it.endMinute)} ${it.classroom}"
    }

    private fun hm(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

    /** Ported from iOS `WatchPayloadEncoderTests.test_carriesEachChangedDaysClassesOnTheirMeeting`. */
    @Test
    fun `each changed day's classes go to the watch on their meeting`() {
        val sent = wearCourseChanges(
            listOf(course),
            feed(
                """{"id":1,"course_no":"CC900","kind":"cancelled","periods":["7"],"class_days":["2026-11-05"]}""",
                """{"id":2,"course_no":"CC900","kind":"room","room":"TR-411","class_days":["2026-11-12"]}""",
                """{"id":3,"course_no":"CC900","kind":"cancelled","periods":["2"],"class_days":["2026-11-19"]}""",
                """{"id":4,"course_no":"CC900","kind":"room","room":"TR-411","periods":["3"],"class_days":["2026-11-26"]}""",
            ),
        )

        assertEquals(listOf("2-3 09:10-11:10 TR-313"), shown(sent, "2026-11-05"))
        assertEquals(listOf("2-3 09:10-11:10 TR-411", "7 14:20-15:10 TR-411"), shown(sent, "2026-11-12"))
        assertEquals(WatchClassChange(listOf("2", "3"), room = "TR-411"), shownOn(sent, "2026-11-12").first().change)
        assertEquals(listOf("3 10:20-11:10 TR-313", "7 14:20-15:10 TR-313"), shown(sent, "2026-11-19"))
        assertEquals(
            listOf("2 09:10-10:00 TR-313", "3 10:20-11:10 TR-411", "7 14:20-15:10 TR-313"),
            shown(sent, "2026-11-26"),
        )
        assertEquals(listOf(null, "TR-411", null), shownOn(sent, "2026-11-26").map { it.change?.room })
        // The cancelled classes go too, for the watch to leave out.
        val changes = WearProtocol.Schedule.decodeCourseChanges(sent)
        assertEquals(listOf(WatchClassChange(listOf("7"), cancelled = true)), changes.on("CC900", LocalDate.parse("2026-11-05")))
        assertEquals(listOf("2", "3"), changes.on("CC900", LocalDate.parse("2026-11-19")).map { it.periods.single() })
        // A week the school left alone is the timetable's.
        assertEquals(listOf("2-3 09:10-11:10 TR-313", "7 14:20-15:10 TR-313"), shown(sent, "2026-12-03"))
    }

    @Test
    fun `a moved start and end are sent as changed and the room left to the timetable`() {
        val sent = wearCourseChanges(
            listOf(course),
            feed(
                """{"id":5,"course_no":"CC900","kind":"start","start":"09:30","periods":["2"],"class_days":["2026-12-03"]}""",
                """{"id":6,"course_no":"CC900","kind":"end","end":"11:00","periods":["3"],"class_days":["2026-12-03"]}""",
            ),
        )
        assertEquals(
            listOf(WatchClassChange(listOf("2", "3"), start = "09:30", end = "11:00")),
            WearProtocol.Schedule.decodeCourseChanges(sent).on("CC900", LocalDate.parse("2026-12-03")),
        )
        assertEquals(listOf("2-3 09:30-11:00 TR-313", "7 14:20-15:10 TR-313"), shown(sent, "2026-12-03"))
    }

    @Test
    fun `days already past are sent as well as the ones ahead`() {
        val sent = wearCourseChanges(
            listOf(course),
            feed("""{"id":7,"course_no":"CC900","kind":"cancelled","periods":["7"],"class_days":["2020-01-02","2036-01-03"]}"""),
        )
        val changes = WearProtocol.Schedule.decodeCourseChanges(sent)
        assertEquals(setOf(LocalDate.parse("2020-01-02"), LocalDate.parse("2036-01-03")), changes.byCourse.getValue("CC900").keys)
    }

    @Test
    fun `no feed, a note alone, or a course not taken sends no changes`() {
        assertEquals(WatchCourseChanges.EMPTY, WearProtocol.Schedule.decodeCourseChanges(wearCourseChanges(listOf(course), CourseChanges.EMPTY)))
        val others = feed(
            """{"id":8,"course_no":"CC900","kind":"note","note_zh":"帶課本","class_days":["2026-11-05"]}""",
            """{"id":9,"course_no":"ZZ999","kind":"cancelled","class_days":["2026-11-05"]}""",
        )
        assertEquals(WatchCourseChanges.EMPTY, WearProtocol.Schedule.decodeCourseChanges(wearCourseChanges(listOf(course), others)))
        assertEquals("{}", wearCourseChanges(emptyList(), others))
    }

    // ---- pieces the change left empty are not sent, and stay gone on the watch ----

    // Tuesday 2026-11-03: A meets 1-2-3 (08:10-11:10), B meets 7 (14:20-15:10).
    private val a = Course.fromSchedule("A", "Course A", classroom = "T-1", schedule = mapOf(2 to listOf("1", "2", "3")))
    private val b = Course.fromSchedule("B", "Course B", classroom = "T-2", schedule = mapOf(2 to listOf("7")))
    private val tuesday = LocalDate.parse("2026-11-03")

    private fun row(id: Int, kind: String, periods: List<String>?, vararg values: Pair<String, String>, courseNo: String = "A") =
        """{"id":$id,"course_no":"$courseNo","kind":"$kind","periods":${periods?.joinToString(",", "[", "]") { "\"$it\"" } ?: "null"},""" +
            values.joinToString("") { "\"${it.first}\":\"${it.second}\"," } + """"class_days":["2026-11-03"]}"""

    /** What the watch says at [minute] on Tuesday, after the feed went through the phone's encoder and the watch's decoder. */
    private fun watch(courses: List<Course>, minute: Int, vararg rows: String): String {
        val changes = WearProtocol.Schedule.decodeCourseChanges(wearCourseChanges(courses, feed(*rows)))
        fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
        return when (val r = NextClassResolver.resolve(courses, tuesday, minute, changes)) {
            is NextClassResult.Ongoing -> "ongoing ${r.course.courseNo} ${r.periods.joinToString("")} ${hm(r.startMinute)}-${hm(r.endMinute)}"
            is NextClassResult.NextToday -> "today ${r.course.courseNo} ${r.periods.joinToString("")} ${hm(r.startMinute)}"
            is NextClassResult.NextFuture -> "later ${r.course.courseNo}"
            NextClassResult.Empty -> "empty"
        }
    }

    @Test
    fun `a cancelled first period with a later start leaves the rest at that start`() {
        val rows = arrayOf(row(1, "cancelled", listOf("1")), row(2, "start", null, "start" to "09:10"))
        assertEquals("today A 23 09:10", watch(listOf(a, b), 8 * 60 + 30, *rows))
        assertEquals("ongoing A 23 09:10-11:10", watch(listOf(a, b), 9 * 60 + 15, *rows))
    }

    @Test
    fun `a cancelled last period, or a moved one, with an earlier end leaves the rest ending there`() {
        for (first in listOf(row(1, "cancelled", listOf("3")), row(1, "room", listOf("3"), "room" to "NEW"))) {
            val rows = arrayOf(first, row(2, "end", null, "end" to "10:00"))
            assertEquals("ongoing A 12 08:10-10:00", watch(listOf(a, b), 9 * 60 + 30, *rows))
            assertEquals("today B 7 14:20", watch(listOf(a, b), 10 * 60 + 30, *rows))
        }
    }

    @Test
    fun `a moved room on the first period with an end after it leaves only that period`() {
        val rows = arrayOf(row(1, "room", listOf("1"), "room" to "NEW"), row(2, "end", null, "end" to "09:00"))
        assertEquals("ongoing A 1 08:10-09:00", watch(listOf(a, b), 8 * 60 + 30, *rows))
        assertEquals("today B 7 14:20", watch(listOf(a, b), 9 * 60 + 30, *rows))
    }

    @Test
    fun `a cancelled first period with a start after the second begins`() {
        val rows = arrayOf(row(1, "cancelled", listOf("1")), row(2, "start", null, "start" to "09:30"))
        assertEquals("today A 23 09:30", watch(listOf(a, b), 8 * 60, *rows))
        assertEquals("today A 23 09:30", watch(listOf(a, b), 9 * 60 + 15, *rows))
    }

    @Test
    fun `a cancelled last period with an end after the second finishes`() {
        val rows = arrayOf(row(1, "cancelled", listOf("3")), row(2, "end", null, "end" to "10:10"))
        assertEquals("ongoing A 12 08:10-10:10", watch(listOf(a, b), 10 * 60 + 5, *rows))
        assertEquals("today B 7 14:20", watch(listOf(a, b), 10 * 60 + 15, *rows))
    }

    @Test
    fun `a course's two blocks on at once show the one that began first`() {
        val d = Course.fromSchedule("D", "Course D", classroom = "T-D", schedule = mapOf(2 to listOf("1", "3")))
        val rows = arrayOf(
            row(1, "start", listOf("1"), "start" to "10:25", courseNo = "D"),
            row(2, "end", listOf("1"), "end" to "11:00", courseNo = "D"),
        )
        assertEquals("ongoing D 3 10:20-11:10", watch(listOf(d), 10 * 60 + 30, *rows))
    }
}
