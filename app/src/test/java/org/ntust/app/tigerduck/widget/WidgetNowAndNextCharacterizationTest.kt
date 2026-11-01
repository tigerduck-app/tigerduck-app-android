package org.ntust.app.tigerduck.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.Holiday
import org.ntust.app.tigerduck.academic.QuietDays
import org.ntust.app.tigerduck.academic.SemesterTerm
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.changes
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.row
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.thursday
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import java.time.LocalDate
import java.time.LocalTime

/**
 * What the widgets name as going on, next today and first of the days after, at the minutes a
 * class starts and ends, pinned through [WidgetDataLoader.state] so the pins hold however the
 * state is computed. Thursday 2026-11-05 is the day; course numbers and rooms are made up.
 */
class WidgetNowAndNextCharacterizationTest {

    private fun holiday(id: Int, vararg days: String) =
        days.map { Holiday(id + it.takeLast(2).toInt(), "假", "Day off", LocalDate.parse(it), LocalDate.parse(it)) }

    private fun quiet(vararg offDays: String) = QuietDays(
        AcademicCalendar(
            revision = 1L,
            terms = listOf(SemesterTerm("1151", LocalDate.parse("2026-09-07"), LocalDate.parse("2026-12-25"))),
            holidays = holiday(100, *offDays),
        ),
        emptySet(),
    )

    private fun course(no: String, schedule: Map<Int, List<String>>, room: String = "") =
        Course.fromSchedule(no, no, classroom = room, schedule = schedule)

    private fun thu(no: String, vararg periods: String, room: String = "") =
        course(no, mapOf(4 to periods.toList()), room)

    private fun CourseBlock?.shown(): String =
        if (this == null) "none" else buildString {
            append("${course.courseNo} $periodRange $startTime-$endTime")
            if (isCancelled) append(" cancelled")
            if (timeChanged) append(" time")
            if (roomChanged) append(" room")
        }

    /** One line a time: the blocks going on, the next today, and the first class of the days after. */
    private fun lines(
        courses: List<Course>,
        times: List<String>,
        changes: CourseChanges = CourseChanges.EMPTY,
        quietDays: QuietDays = quiet(),
        day: LocalDate = thursday,
        seconds: Int = 0,
    ): String = times.joinToString("\n") { time ->
        val state = WidgetDataLoader.state(
            courses = courses,
            now = day.atTime(LocalTime.parse(time)).withSecond(seconds),
            isLoggedIn = true,
            quietDays = quietDays,
            changes = changes,
            courseNameScale = 1f,
        )
        val first = state.tomorrowFirstCourseNo?.let {
            "$it d${state.tomorrowFirstCourseWeekday} p${state.tomorrowFirstCoursePeriodId}"
        } ?: "none"
        "$time on=[${state.ongoingBlocks.joinToString("; ") { it.shown() }}] " +
            "next=${state.nextTodayBlock.shown()} first=$first quiet=${state.isQuietToday}"
    }

    @Test
    fun `a course with a morning and an afternoon block is next only once the morning one is over`() {
        val twice = thu("CC910", "3", "4", "7", "8", room = "T1-101")
        val other = thu("CC911", "9")
        val text = lines(listOf(twice, other), listOf("10:19", "10:20", "11:19", "11:20", "12:09", "12:10", "14:19", "14:20", "15:29", "15:30", "16:19", "16:20"))
        assertEquals(
            """
            10:19 on=[] next=CC910 3–4 10:20-12:10 first=CC910 d4 p3 quiet=false
            10:20 on=[CC910 3–4 10:20-12:10] next=CC911 9 16:30-17:20 first=CC910 d4 p3 quiet=false
            11:19 on=[CC910 3–4 10:20-12:10] next=CC911 9 16:30-17:20 first=CC910 d4 p3 quiet=false
            11:20 on=[CC910 3–4 10:20-12:10] next=CC911 9 16:30-17:20 first=CC910 d4 p3 quiet=false
            12:09 on=[CC910 3–4 10:20-12:10] next=CC911 9 16:30-17:20 first=CC910 d4 p3 quiet=false
            12:10 on=[] next=CC910 7–8 14:20-16:20 first=CC910 d4 p3 quiet=false
            14:19 on=[] next=CC910 7–8 14:20-16:20 first=CC910 d4 p3 quiet=false
            14:20 on=[CC910 7–8 14:20-16:20] next=CC911 9 16:30-17:20 first=CC910 d4 p3 quiet=false
            15:29 on=[CC910 7–8 14:20-16:20] next=CC911 9 16:30-17:20 first=CC910 d4 p3 quiet=false
            15:30 on=[CC910 7–8 14:20-16:20] next=CC911 9 16:30-17:20 first=CC910 d4 p3 quiet=false
            16:19 on=[CC910 7–8 14:20-16:20] next=CC911 9 16:30-17:20 first=CC910 d4 p3 quiet=false
            16:20 on=[] next=CC911 9 16:30-17:20 first=CC910 d4 p3 quiet=false
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `the boundary minutes of one class ending where another starts`() {
        val first = thu("CC912", "3")
        val second = thu("CC913", "4")
        val text = lines(listOf(first, second), listOf("11:09", "11:10", "11:19", "11:20", "12:09", "12:10"))
        assertEquals(
            """
            11:09 on=[CC912 3 10:20-11:10] next=CC913 4 11:20-12:10 first=CC912 d4 p3 quiet=false
            11:10 on=[] next=CC913 4 11:20-12:10 first=CC912 d4 p3 quiet=false
            11:19 on=[] next=CC913 4 11:20-12:10 first=CC912 d4 p3 quiet=false
            11:20 on=[CC913 4 11:20-12:10] next=none first=CC912 d4 p3 quiet=false
            12:09 on=[CC913 4 11:20-12:10] next=none first=CC912 d4 p3 quiet=false
            12:10 on=[] next=none first=CC912 d4 p3 quiet=false
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `a class the school ends at 11-20 hands over at that minute`() {
        val early = thu("CC914", "3", "4")
        val next = thu("CC915", "4")
        val feed = changes(row(1, "end", periods = listOf("3", "4"), values = mapOf("end" to "11:20"), courseNo = "CC914"))
        val text = lines(listOf(early, next), listOf("11:19", "11:20", "12:09", "12:10"), feed)
        assertEquals(
            """
            11:19 on=[CC914 3–4 10:20-11:20 time] next=CC915 4 11:20-12:10 first=CC914 d4 p3 quiet=false
            11:20 on=[CC915 4 11:20-12:10] next=none first=CC914 d4 p3 quiet=false
            12:09 on=[CC915 4 11:20-12:10] next=none first=CC914 d4 p3 quiet=false
            12:10 on=[] next=none first=CC914 d4 p3 quiet=false
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `a class the school ends at 11-45 is over at that minute, and with no feed it runs to 12-10`() {
        val moved = thu("CC916", "3", "4")
        val feed = changes(row(1, "end", periods = listOf("3", "4"), values = mapOf("end" to "11:45"), courseNo = "CC916"))
        val times = listOf("11:19", "11:20", "11:44", "11:45", "12:09", "12:10")
        assertEquals(
            """
            11:19 on=[CC916 3–4 10:20-11:45 time] next=none first=CC916 d4 p3 quiet=false
            11:20 on=[CC916 3–4 10:20-11:45 time] next=none first=CC916 d4 p3 quiet=false
            11:44 on=[CC916 3–4 10:20-11:45 time] next=none first=CC916 d4 p3 quiet=false
            11:45 on=[] next=none first=CC916 d4 p3 quiet=false
            12:09 on=[] next=none first=CC916 d4 p3 quiet=false
            12:10 on=[] next=none first=CC916 d4 p3 quiet=false
            """.trimIndent(),
            lines(listOf(moved), times, feed),
        )
        assertEquals(
            """
            11:19 on=[CC916 3–4 10:20-12:10] next=none first=CC916 d4 p3 quiet=false
            11:20 on=[CC916 3–4 10:20-12:10] next=none first=CC916 d4 p3 quiet=false
            11:44 on=[CC916 3–4 10:20-12:10] next=none first=CC916 d4 p3 quiet=false
            11:45 on=[CC916 3–4 10:20-12:10] next=none first=CC916 d4 p3 quiet=false
            12:09 on=[CC916 3–4 10:20-12:10] next=none first=CC916 d4 p3 quiet=false
            12:10 on=[] next=none first=CC916 d4 p3 quiet=false
            """.trimIndent(),
            lines(listOf(moved), times),
        )
    }

    @Test
    fun `a class the school starts later is next at its new start`() {
        val moved = thu("CC917", "3")
        val other = thu("CC918", "4")
        val feed = changes(row(1, "start", periods = listOf("3"), values = mapOf("start" to "10:40"), courseNo = "CC917"))
        assertEquals(
            """
            10:19 on=[] next=CC917 3 10:40-11:10 time first=CC917 d4 p3 quiet=false
            10:20 on=[] next=CC917 3 10:40-11:10 time first=CC917 d4 p3 quiet=false
            10:39 on=[] next=CC917 3 10:40-11:10 time first=CC917 d4 p3 quiet=false
            10:40 on=[CC917 3 10:40-11:10 time] next=CC918 4 11:20-12:10 first=CC917 d4 p3 quiet=false
            11:09 on=[CC917 3 10:40-11:10 time] next=CC918 4 11:20-12:10 first=CC917 d4 p3 quiet=false
            11:10 on=[] next=CC918 4 11:20-12:10 first=CC917 d4 p3 quiet=false
            11:20 on=[CC918 4 11:20-12:10] next=none first=CC917 d4 p3 quiet=false
            """.trimIndent(),
            lines(listOf(moved, other), listOf("10:19", "10:20", "10:39", "10:40", "11:09", "11:10", "11:20"), feed),
        )
    }

    @Test
    fun `contiguous periods are one block and a split meeting runs as its classes`() {
        val long = thu("CC919", "2", "3", "4", room = "T1-101")
        val split = thu("CC920", "6", "7", "8", room = "T1-102")
        val feed = changes(
            row(1, "cancelled", periods = listOf("7"), courseNo = "CC920"),
            row(2, "room", periods = listOf("8"), values = mapOf("room" to "T9-909"), courseNo = "CC920"),
        )
        val times = listOf("09:10", "11:20", "12:10", "13:20", "14:09", "14:20", "15:09", "15:10", "15:29", "15:30", "16:19", "16:20")
        assertEquals(
            """
            09:10 on=[CC919 2–4 09:10-12:10] next=none first=CC919 d4 p2 quiet=false
            11:20 on=[CC919 2–4 09:10-12:10] next=none first=CC919 d4 p2 quiet=false
            12:10 on=[] next=none first=CC919 d4 p2 quiet=false
            """.trimIndent(),
            lines(listOf(long), times.take(3)),
        )
        assertEquals(
            """
            09:10 on=[CC919 2–4 09:10-12:10] next=CC920 6 13:20-14:10 first=CC919 d4 p2 quiet=false
            11:20 on=[CC919 2–4 09:10-12:10] next=CC920 6 13:20-14:10 first=CC919 d4 p2 quiet=false
            12:10 on=[] next=CC920 6 13:20-14:10 first=CC919 d4 p2 quiet=false
            13:20 on=[CC920 6 13:20-14:10] next=none first=CC919 d4 p2 quiet=false
            14:09 on=[CC920 6 13:20-14:10] next=none first=CC919 d4 p2 quiet=false
            14:20 on=[] next=CC920 8 15:30-16:20 room first=CC919 d4 p2 quiet=false
            15:09 on=[] next=CC920 8 15:30-16:20 room first=CC919 d4 p2 quiet=false
            15:10 on=[] next=CC920 8 15:30-16:20 room first=CC919 d4 p2 quiet=false
            15:29 on=[] next=CC920 8 15:30-16:20 room first=CC919 d4 p2 quiet=false
            15:30 on=[CC920 8 15:30-16:20 room] next=none first=CC919 d4 p2 quiet=false
            16:19 on=[CC920 8 15:30-16:20 room] next=none first=CC919 d4 p2 quiet=false
            16:20 on=[] next=none first=CC919 d4 p2 quiet=false
            """.trimIndent(),
            lines(listOf(long, split), times, feed),
        )
    }

    @Test
    fun `a cancelled class is passed over for next and a later one is named`() {
        val gone = thu("CC921", "2")
        val later = thu("CC922", "3")
        val feed = changes(row(1, "cancelled", periods = listOf("2"), courseNo = "CC921"))
        assertEquals(
            """
            08:00 on=[] next=CC922 3 10:20-11:10 first=CC921 d4 p2 quiet=false
            09:10 on=[] next=CC922 3 10:20-11:10 first=CC921 d4 p2 quiet=false
            09:30 on=[] next=CC922 3 10:20-11:10 first=CC921 d4 p2 quiet=false
            10:20 on=[CC922 3 10:20-11:10] next=none first=CC921 d4 p2 quiet=false
            """.trimIndent(),
            lines(listOf(gone, later), listOf("08:00", "09:10", "09:30", "10:20"), feed),
        )
    }

    @Test
    fun `courses going on together are listed in course order, not start order, and equal starts go to the earlier course`() {
        val late = thu("CC923", "4")
        val early = thu("CC924", "3")
        val twinA = thu("CC925", "7")
        val twinB = thu("CC926", "7")
        val feed = changes(row(1, "end", periods = listOf("3"), values = mapOf("end" to "11:50"), courseNo = "CC924"))
        assertEquals(
            """
            10:30 on=[CC924 3 10:20-11:50 time] next=CC923 4 11:20-12:10 first=CC924 d4 p3 quiet=false
            11:25 on=[CC923 4 11:20-12:10; CC924 3 10:20-11:50 time] next=CC925 7 14:20-15:10 first=CC924 d4 p3 quiet=false
            11:55 on=[CC923 4 11:20-12:10] next=CC925 7 14:20-15:10 first=CC924 d4 p3 quiet=false
            13:00 on=[] next=CC925 7 14:20-15:10 first=CC924 d4 p3 quiet=false
            """.trimIndent(),
            lines(listOf(late, early, twinA, twinB), listOf("10:30", "11:25", "11:55", "13:00"), feed),
        )
        assertEquals(
            """
            13:00 on=[] next=CC926 7 14:20-15:10 first=CC926 d4 p7 quiet=false
            """.trimIndent(),
            lines(listOf(twinB, twinA), listOf("13:00")),
        )
    }

    @Test
    fun `a day off silences now and next, and the lookahead passes over days off`() {
        val monday = course("CC927", mapOf(1 to listOf("2")))
        val friday = course("CC928", mapOf(5 to listOf("1")))
        val tuesday = course("CC929", mapOf(2 to listOf("1")))
        val courses = listOf(monday, friday, tuesday, thu("CC930", "3"))
        assertEquals(
            """
            10:30 on=[] next=none first=CC928 d5 p1 quiet=true
            20:00 on=[] next=none first=CC928 d5 p1 quiet=true
            """.trimIndent(),
            lines(courses, listOf("10:30", "20:00"), quietDays = quiet("2026-11-05")),
        )
        assertEquals(
            """
            20:00 on=[] next=none first=CC929 d2 p1 quiet=false
            """.trimIndent(),
            lines(courses, listOf("20:00"), quietDays = quiet("2026-11-06", "2026-11-09")),
        )
        assertEquals(
            """
            20:00 on=[] next=none first=CC930 d4 p3 quiet=false
            """.trimIndent(),
            lines(courses, listOf("20:00"), quietDays = quiet("2026-11-06", "2026-11-09", "2026-11-10")),
        )
    }

    @Test
    fun `the lookahead reaches seven days and no further, and a Friday evening names Monday`() {
        val sevenOut = thu("CC931", "1")
        val saturday = course("CC932", mapOf(6 to listOf("1")))
        assertEquals(
            """
            20:00 on=[] next=none first=CC931 d4 p1 quiet=false
            """.trimIndent(),
            lines(listOf(sevenOut), listOf("20:00")),
        )
        assertEquals(
            """
            20:00 on=[] next=none first=CC931 d4 p1 quiet=false
            """.trimIndent(),
            lines(listOf(sevenOut), listOf("20:00"), day = thursday.plusDays(1)),
        )
        assertEquals(
            """
            08:30 on=[] next=none first=CC931 d4 p1 quiet=false
            """.trimIndent(),
            lines(listOf(sevenOut), listOf("08:30"), day = thursday.plusDays(1)),
        )
        assertEquals(
            """
            20:00 on=[] next=none first=CC932 d6 p1 quiet=false
            """.trimIndent(),
            lines(listOf(saturday), listOf("20:00"), day = thursday.plusDays(1)),
        )
        val monday = course("CC937", mapOf(1 to listOf("2")))
        assertEquals(
            """
            20:00 on=[] next=none first=CC937 d1 p2 quiet=false
            """.trimIndent(),
            lines(listOf(monday, sevenOut), listOf("20:00"), day = thursday.plusDays(1)),
        )
        val allButTheLast = quiet(*(1..6).map { "2026-11-%02d".format(5 + it) }.toTypedArray())
        assertEquals(
            """
            20:00 on=[] next=none first=CC931 d4 p1 quiet=false
            """.trimIndent(),
            lines(listOf(sevenOut, monday), listOf("20:00"), quietDays = allButTheLast),
        )
        val allOff = quiet(*(1..7).map { "2026-11-%02d".format(5 + it) }.toTypedArray())
        assertEquals(
            """
            20:00 on=[] next=none first=none quiet=false
            """.trimIndent(),
            lines(listOf(sevenOut, monday), listOf("20:00"), quietDays = allOff),
        )
    }

    @Test
    fun `the first class of a later day follows the school's changes`() {
        val one = course("CC933", mapOf(5 to listOf("1")))
        val three = course("CC934", mapOf(5 to listOf("3", "4")))
        val cancel = changes(row(1, "cancelled", periods = listOf("1"), days = listOf("2026-11-06"), courseNo = "CC933"))
        val earlier = changes(row(2, "start", periods = listOf("3", "4"), days = listOf("2026-11-06"), values = mapOf("start" to "07:00"), courseNo = "CC934"))
        val split = changes(row(3, "cancelled", periods = listOf("3"), days = listOf("2026-11-06"), courseNo = "CC934"))
        assertEquals(
            """
            20:00 on=[] next=none first=CC933 d5 p1 quiet=false
            """.trimIndent(),
            lines(listOf(one, three), listOf("20:00")),
        )
        assertEquals(
            """
            20:00 on=[] next=none first=CC934 d5 p3 quiet=false
            """.trimIndent(),
            lines(listOf(one, three), listOf("20:00"), cancel),
        )
        assertEquals(
            """
            20:00 on=[] next=none first=CC934 d5 p3 quiet=false
            """.trimIndent(),
            lines(listOf(one, three), listOf("20:00"), earlier),
        )
        assertEquals(
            """
            20:00 on=[] next=none first=CC934 d5 p4 quiet=false
            """.trimIndent(),
            lines(listOf(three), listOf("20:00"), split),
        )
    }

    @Test
    fun `a moment is read to the minute, whatever its seconds`() {
        val a = thu("CC935", "3")
        val b = thu("CC936", "4")
        assertEquals(
            """
            11:09 on=[CC935 3 10:20-11:10] next=CC936 4 11:20-12:10 first=CC935 d4 p3 quiet=false
            11:19 on=[] next=CC936 4 11:20-12:10 first=CC935 d4 p3 quiet=false
            """.trimIndent(),
            lines(listOf(a, b), listOf("11:09", "11:19"), seconds = 59),
        )
        assertEquals(
            """
            11:10 on=[] next=CC936 4 11:20-12:10 first=CC935 d4 p3 quiet=false
            11:20 on=[CC936 4 11:20-12:10] next=none first=CC935 d4 p3 quiet=false
            """.trimIndent(),
            lines(listOf(a, b), listOf("11:10", "11:20"), seconds = 1),
        )
    }
}
