package org.ntust.app.tigerduck.wear

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.NextClassResolver
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
            listOf(course), date.dayOfWeek.value, minuteOfDay = 0, changedClasses = { changes.on(it, date) },
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
}
