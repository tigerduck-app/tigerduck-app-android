package org.ntust.app.tigerduck.data.moodle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.network.model.moodle.MoodleMaterialSection
import java.time.Instant
import java.time.ZonedDateTime

/** Ported from iOS `MoodleCurrentWeekTests`. */
class MoodleCurrentWeekTest {

    private fun date(year: Int, month: Int, day: Int, hour: Int = 10, minute: Int = 0): Instant =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, AppConstants.TAIPEI_ZONE).toInstant()

    private fun sections(vararg names: String): List<MoodleMaterialSection> =
        names.mapIndexed { i, name -> MoodleMaterialSection(100 + i, name, emptyList(), i) }

    private fun current(
        sections: List<MoodleMaterialSection>,
        format: String? = "topics",
        start: Instant? = null,
        now: Instant,
    ): Int? = MoodleCurrentWeek.sectionId(sections, format, start?.epochSecond, now, AppConstants.TAIPEI_ZONE)

    private val weekly = sections("General", "Week 1", "Week 2", "Week 3", "Week 4")
    private val weeklyStart = date(2026, 9, 7, 0)

    @Test
    fun `a weekly course counts weeks from its start date, section 1 being the first, each from the start's weekday`() {
        assertEquals(101, current(weekly, "weeks", weeklyStart, date(2026, 9, 7, 0)))
        assertEquals(102, current(weekly, "weeks", weeklyStart, date(2026, 9, 20, 23, 59)))
        assertEquals(103, current(weekly, "weeks", weeklyStart, date(2026, 9, 21, 0)))
        assertEquals(104, current(weekly, "weeks", weeklyStart, date(2026, 10, 4)))
    }

    @Test
    fun `a weekly course has no current section before it starts, after its last section, or in a week whose section is not listed`() {
        assertNull(current(weekly, "weeks", weeklyStart, date(2026, 9, 6, 23, 59)))
        assertNull(current(weekly, "weeks", weeklyStart, date(2026, 10, 5)))
        val withoutWeek2 = weekly.filter { it.number != 2 }
        assertNull(current(withoutWeek2, "weeks", weeklyStart, date(2026, 9, 16)))
    }

    @Test
    fun `a weekly course goes by its section numbers, not its names, and sections without numbers give none`() {
        val named = sections("General", "Intro 12/1", "Sockets 12/8")
        assertEquals(102, current(named, "weeks", weeklyStart, date(2026, 9, 15)))
        val unnumbered = weekly.map { MoodleMaterialSection(it.id, it.name, emptyList(), null) }
        assertNull(current(unnumbered, "weeks", weeklyStart, date(2026, 9, 15)))
    }

    @Test
    fun `a course of another format, or a weekly one without a start date, goes by the dates in its section names`() {
        val names = sections("課程資訊", "第1週 9/8 Introduction", "第2週 9/15 HTTP", "第3週 9/22 TCP")
        assertEquals(102, current(names, now = date(2026, 9, 16)))
        assertEquals(103, current(names, "weeks", null, date(2026, 9, 22)))
        assertEquals(101, current(names, null, now = date(2026, 9, 8, 0)))
    }

    @Test
    fun `a name may write its date as month and day in Chinese, or after a year`() {
        val chinese = sections("General", "9月8日 課程介紹", "9 月 15 日 HTTP")
        assertEquals(102, current(chinese, now = date(2026, 9, 18)))
        val withYear = sections("General", "2026/09/08 Intro", "2026/09/15 HTTP")
        assertEquals(101, current(withYear, now = date(2026, 9, 10)))
    }

    @Test
    fun `a section's dates run a week from its first date, or to the end of a longer range, and stop at the next dated section`() {
        val names = sections("General", "11/2-11/15 Midterm", "11/16 Routing", "11/18 Switching", "11/30 Review")
        assertEquals(101, current(names, now = date(2026, 11, 12)))
        assertEquals(102, current(names, now = date(2026, 11, 17)))
        assertEquals(103, current(names, now = date(2026, 11, 24)))
        assertNull(current(names, now = date(2026, 11, 25)))
        assertEquals(104, current(names, now = date(2026, 12, 6)))
        assertNull(current(names, now = date(2026, 12, 7)))
        assertNull(current(names, now = date(2026, 11, 1)))
    }

    @Test
    fun `a term across New Year puts its December sections in the year before its January ones`() {
        val names = sections("General", "Week 15 12/21", "Week 16 12/28-1/3", "Week 17 1/4", "Week 18 1/11 Final")
        assertEquals(101, current(names, now = date(2026, 12, 23)))
        assertEquals(102, current(names, now = date(2027, 1, 2)))
        assertEquals(103, current(names, now = date(2027, 1, 5)))
        assertEquals(104, current(names, now = date(2027, 1, 12)))
        val early = sections("General", "9/14 Intro", "1/11 Final")
        assertEquals(101, current(early, now = date(2026, 9, 15)))
        assertEquals(102, current(early, now = date(2027, 1, 12)))
    }

    @Test
    fun `names without dates, with dates that do not rise, or with no real date give no section`() {
        assertNull(current(weekly, now = date(2026, 9, 16)))
        val falling = sections("General", "Part 3/4", "Part 1/2", "Part 2/2")
        assertNull(current(falling, now = date(2026, 3, 5)))
        val impossible = sections("General", "2/30 Intro", "13/1 Lab")
        assertNull(current(impossible, now = date(2026, 3, 1)))
        assertNull(current(emptyList(), "weeks", weeklyStart, date(2026, 9, 8)))
    }
}
