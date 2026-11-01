package org.ntust.app.tigerduck.wear.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.NextClassResult
import org.ntust.app.tigerduck.shared.TodayClassEntry
import org.ntust.app.tigerduck.shared.WatchClassChange
import org.ntust.app.tigerduck.shared.WatchCourseChanges
import org.ntust.app.tigerduck.shared.WearProtocol
import org.ntust.app.tigerduck.wear.tile.tileTransitionMinutes
import java.time.LocalDate

/**
 * What the watch shows now and next, pinned before the watch reads the shared class timeline, and
 * unchanged by it: the Now and Next screen and complication (`nextClass`), the Today list and the
 * tile (`todaysClasses`, `tileTransitionMinutes`), each from a snapshot as the phone's data item
 * and the watch's stored copy give it. Every row here was written against the code before the move.
 */
class WatchTimelineCharacterizationTest {

    private val tuesday = LocalDate.of(2026, 11, 3)
    private val wednesday = tuesday.plusDays(1)

    private fun course(no: String, schedule: Map<Int, List<String>>, room: String = "", map: Map<String, String> = emptyMap()) =
        Course.fromSchedule(courseNo = no, courseName = "Course-$no", classroom = room, schedule = schedule, classroomMap = map)

    private val p4 = course("P4", mapOf(2 to listOf("3", "4")), "TR-1")      // 10:20-12:10
    private val p7 = course("P7", mapOf(2 to listOf("7", "8")), "TR-2")      // 14:20-16:20

    private fun snapshot(
        courses: List<Course>,
        changes: Map<String, Map<LocalDate, List<WatchClassChange>>> = emptyMap(),
        quiet: Set<LocalDate> = emptySet(),
    ) = WatchSnapshot(
        courses = courses,
        accentHex = SchedulePersistence.DEFAULT_ACCENT,
        syncedAtMs = 1_000L,
        loggedIn = true,
        languageTag = null,
        quietDays = quiet,
        courseChanges = WatchCourseChanges(changes),
    )

    private fun at(h: Int, m: Int) = h * 60 + m

    private fun hm(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

    private fun show(r: NextClassResult): String = when (r) {
        is NextClassResult.Ongoing ->
            "ongoing ${r.course.courseNo} ${r.periods.joinToString("")} ${hm(r.startMinute)}-${hm(r.endMinute)} ${r.classroom} " +
                "next=" + (r.nextToday?.let { "${it.course.courseNo} ${it.periods.joinToString("")} ${hm(it.startMinute)} ${it.classroom}" } ?: "-") +
                " chg=${r.change != null}"
        is NextClassResult.NextToday ->
            "today ${r.course.courseNo} ${r.periods.joinToString("")} ${hm(r.startMinute)} ${r.classroom} chg=${r.change != null}"
        is NextClassResult.NextFuture ->
            "+${r.daysAhead} ${r.course.courseNo} ${r.periods.joinToString("")} ${hm(r.startMinute)} wd=${r.weekday} ${r.classroom} chg=${r.change != null}"
        NextClassResult.Empty -> "empty"
    }

    private fun showEntry(e: TodayClassEntry) =
        "${e.course.courseNo} ${e.periods.joinToString("")} ${hm(e.startMinute)}-${hm(e.endMinute)} ${e.classroom} ${e.status} chg=${e.change != null}"

    private fun now(s: WatchSnapshot, day: LocalDate, minute: Int) = show(s.nextClass(day, minute))

    private fun today(s: WatchSnapshot, day: LocalDate, minute: Int) = s.todaysClasses(day, minute).map(::showEntry)

    private fun tile(s: WatchSnapshot, day: LocalDate, minute: Int): String {
        val blocks = s.todaysClasses(day, minute).filter { it.endMinute > minute }
        return tileTransitionMinutes(blocks, minute).joinToString(",") { hm(it) }
    }

    // ---- the brief's four moments, with the timetable's end and with the end changed to 11:45 ----

    @Test
    fun `now and next at the four moments with the timetable's end`() {
        val s = snapshot(listOf(p4, p7))
        val ongoing = "ongoing P4 34 10:20-12:10 TR-1 next=P7 78 14:20 TR-2 chg=false"
        assertEquals(ongoing, now(s, tuesday, at(11, 19)))
        assertEquals(ongoing, now(s, tuesday, at(11, 20)))
        assertEquals(ongoing, now(s, tuesday, at(12, 9)))
        assertEquals("today P7 78 14:20 TR-2 chg=false", now(s, tuesday, at(12, 10)))
    }

    @Test
    fun `now and next at the four moments with the end changed to 11-45`() {
        val s = snapshot(listOf(p4, p7), mapOf("P4" to mapOf(tuesday to listOf(WatchClassChange(listOf("3", "4"), end = "11:45")))))
        val ongoing = "ongoing P4 34 10:20-11:45 TR-1 next=P7 78 14:20 TR-2 chg=true"
        assertEquals(ongoing, now(s, tuesday, at(11, 19)))
        assertEquals(ongoing, now(s, tuesday, at(11, 20)))
        assertEquals(ongoing, now(s, tuesday, at(11, 44)))
        assertEquals("today P7 78 14:20 TR-2 chg=false", now(s, tuesday, at(11, 45)))
        assertEquals("today P7 78 14:20 TR-2 chg=false", now(s, tuesday, at(12, 9)))
        assertEquals("today P7 78 14:20 TR-2 chg=false", now(s, tuesday, at(12, 10)))
    }

    @Test
    fun `today's list and the tile at the four moments, with and without the changed end`() {
        val plain = snapshot(listOf(p4, p7))
        assertEquals(
            listOf("P4 34 10:20-12:10 TR-1 Ongoing chg=false", "P7 78 14:20-16:20 TR-2 Upcoming chg=false"),
            today(plain, tuesday, at(11, 19)),
        )
        assertEquals(
            listOf("P4 34 10:20-12:10 TR-1 Ongoing chg=false", "P7 78 14:20-16:20 TR-2 Upcoming chg=false"),
            today(plain, tuesday, at(12, 9)),
        )
        assertEquals(
            listOf("P4 34 10:20-12:10 TR-1 Ended chg=false", "P7 78 14:20-16:20 TR-2 Upcoming chg=false"),
            today(plain, tuesday, at(12, 10)),
        )
        assertEquals("12:10,14:20,16:20", tile(plain, tuesday, at(11, 19)))
        assertEquals("12:10,14:20,16:20", tile(plain, tuesday, at(11, 20)))
        assertEquals("12:10,14:20,16:20", tile(plain, tuesday, at(12, 9)))
        assertEquals("14:20,16:20", tile(plain, tuesday, at(12, 10)))

        val early = snapshot(listOf(p4, p7), mapOf("P4" to mapOf(tuesday to listOf(WatchClassChange(listOf("3", "4"), end = "11:45")))))
        assertEquals(
            listOf("P4 34 10:20-11:45 TR-1 Ongoing chg=true", "P7 78 14:20-16:20 TR-2 Upcoming chg=false"),
            today(early, tuesday, at(11, 44)),
        )
        assertEquals(
            listOf("P4 34 10:20-11:45 TR-1 Ended chg=true", "P7 78 14:20-16:20 TR-2 Upcoming chg=false"),
            today(early, tuesday, at(11, 45)),
        )
        assertEquals("11:45,14:20,16:20", tile(early, tuesday, at(11, 19)))
        assertEquals("11:45,14:20,16:20", tile(early, tuesday, at(11, 20)))
        assertEquals("11:45,14:20,16:20", tile(early, tuesday, at(11, 44)))
        assertEquals("14:20,16:20", tile(early, tuesday, at(11, 45)))
        assertEquals("14:20,16:20", tile(early, tuesday, at(12, 9)))
    }

    // ---- no class today: the next one in N days ----

    @Test
    fun `nothing today and a class in six days, a week, and tomorrow`() {
        val db = course("DB", mapOf(2 to listOf("3", "4")), "T-1")
        val s = snapshot(listOf(db))
        assertEquals("+6 DB 34 10:20 wd=2 T-1 chg=false", now(s, wednesday, at(9, 0)))
        assertEquals("+7 DB 34 10:20 wd=2 T-1 chg=false", now(s, tuesday, at(13, 0)))
        assertEquals("+1 DB 34 10:20 wd=2 T-1 chg=false", now(s, tuesday.minusDays(1), at(22, 0)))
        assertEquals(emptyList<String>(), today(s, wednesday, at(9, 0)))
    }

    @Test
    fun `a quiet day is passed over, and a quiet week ahead leaves nothing`() {
        val db = course("DB", mapOf(2 to listOf("3", "4")), "T-1")
        assertEquals("empty", now(snapshot(listOf(db), quiet = setOf(tuesday.plusDays(7))), wednesday, at(9, 0)))
        // Today quiet: its class is not on, and the next Tuesday is seven days on.
        val quietToday = snapshot(listOf(db), quiet = setOf(tuesday))
        assertEquals("+7 DB 34 10:20 wd=2 T-1 chg=false", now(quietToday, tuesday, at(10, 30)))
        assertEquals(emptyList<String>(), today(quietToday, tuesday, at(10, 30)))
        assertEquals("", tile(quietToday, tuesday, at(10, 30)))
    }

    @Test
    fun `the last class of the day leaves the next class day's first`() {
        val wed = course("OS", mapOf(3 to listOf("1")), "T-3")
        val s = snapshot(listOf(p4, p7, wed))
        assertEquals("+1 OS 1 08:10 wd=3 T-3 chg=false", now(s, tuesday, at(17, 0)))
        assertEquals("today P7 78 14:20 TR-2 chg=false", now(s, tuesday, at(13, 0)))
        assertEquals("ongoing P7 78 14:20-16:20 TR-2 next=- chg=false", now(s, tuesday, at(16, 19)))
        assertEquals("+1 OS 1 08:10 wd=3 T-3 chg=false", now(s, tuesday, at(16, 20)))
    }

    // ---- the school's changes ----

    @Test
    fun `a cancelled class is neither on nor next, today or on a later day`() {
        val wed = course("OS", mapOf(3 to listOf("1")), "T-3")
        val thu = course("NW", mapOf(4 to listOf("1")), "T-4")
        val cancelled = WatchClassChange(listOf("3", "4"), cancelled = true)
        val s = snapshot(
            listOf(p4, p7, wed, thu),
            mapOf(
                "P4" to mapOf(tuesday to listOf(cancelled)),
                "OS" to mapOf(wednesday to listOf(WatchClassChange(listOf("1"), cancelled = true))),
            ),
        )
        assertEquals("today P7 78 14:20 TR-2 chg=false", now(s, tuesday, at(11, 0)))
        assertEquals(listOf("P7 78 14:20-16:20 TR-2 Upcoming chg=false"), today(s, tuesday, at(11, 0)))
        assertEquals("14:20,16:20", tile(s, tuesday, at(11, 0)))
        assertEquals("+2 NW 1 08:10 wd=4 T-4 chg=false", now(s, tuesday, at(17, 0)))
    }

    @Test
    fun `a moved class runs from its changed start`() {
        val s = snapshot(listOf(p4, p7), mapOf("P7" to mapOf(tuesday to listOf(WatchClassChange(listOf("7", "8"), start = "14:40", room = "TR-9")))))
        assertEquals("today P7 78 14:40 TR-9 chg=true", now(s, tuesday, at(14, 30)))
        assertEquals("today P7 78 14:40 TR-9 chg=true", now(s, tuesday, at(14, 39)))
        assertEquals("ongoing P7 78 14:40-16:20 TR-9 next=- chg=true", now(s, tuesday, at(14, 40)))
        assertEquals(
            listOf("P4 34 10:20-12:10 TR-1 Ended chg=false", "P7 78 14:40-16:20 TR-9 Upcoming chg=true"),
            today(s, tuesday, at(14, 30)),
        )
        assertEquals("14:40,16:20", tile(s, tuesday, at(14, 30)))
    }

    @Test
    fun `a class the school ran late is on until its changed end`() {
        val s = snapshot(listOf(p4, p7), mapOf("P4" to mapOf(tuesday to listOf(WatchClassChange(listOf("3", "4"), end = "12:30")))))
        assertEquals("ongoing P4 34 10:20-12:30 TR-1 next=P7 78 14:20 TR-2 chg=true", now(s, tuesday, at(12, 29)))
        assertEquals("today P7 78 14:20 TR-2 chg=false", now(s, tuesday, at(12, 30)))
    }

    @Test
    fun `a meeting split by a room change is its pieces, with the gap between them`() {
        val s = snapshot(
            listOf(p4, p7),
            mapOf("P4" to mapOf(tuesday to listOf(WatchClassChange(listOf("3")), WatchClassChange(listOf("4"), room = "TR-9")))),
        )
        assertEquals("ongoing P4 3 10:20-11:10 TR-1 next=P4 4 11:20 TR-9 chg=true", now(s, tuesday, at(10, 30)))
        assertEquals("today P4 4 11:20 TR-9 chg=true", now(s, tuesday, at(11, 15)))
        assertEquals("ongoing P4 4 11:20-12:10 TR-9 next=P7 78 14:20 TR-2 chg=true", now(s, tuesday, at(11, 30)))
        assertEquals(
            listOf("P4 3 10:20-11:10 TR-1 Ongoing chg=true", "P4 4 11:20-12:10 TR-9 Upcoming chg=true", "P7 78 14:20-16:20 TR-2 Upcoming chg=false"),
            today(s, tuesday, at(10, 30)),
        )
        assertEquals("11:10,11:20,12:10,14:20,16:20", tile(s, tuesday, at(10, 30)))
    }

    @Test
    fun `a cancelled piece of a meeting leaves the other piece`() {
        val s = snapshot(
            listOf(p4, p7),
            mapOf("P4" to mapOf(tuesday to listOf(WatchClassChange(listOf("3"), cancelled = true), WatchClassChange(listOf("4"))))),
        )
        assertEquals("today P4 4 11:20 TR-1 chg=true", now(s, tuesday, at(10, 30)))
        assertEquals("ongoing P4 4 11:20-12:10 TR-1 next=P7 78 14:20 TR-2 chg=true", now(s, tuesday, at(11, 20)))
    }

    @Test
    fun `a change on another day leaves today as the timetable has it`() {
        val s = snapshot(
            listOf(p4, p7),
            mapOf("P4" to mapOf(tuesday.minusDays(7) to listOf(WatchClassChange(listOf("3", "4"), cancelled = true)))),
        )
        assertEquals("ongoing P4 34 10:20-12:10 TR-1 next=P7 78 14:20 TR-2 chg=false", now(s, tuesday, at(11, 0)))
    }

    // ---- blocks ----

    @Test
    fun `a course with a morning and an afternoon block leaves its other block out of what is next`() {
        val m = course(
            "M", mapOf(2 to listOf("1", "2", "6", "7")), "R",
            mapOf("2-1" to "R-AM", "2-2" to "R-AM", "2-6" to "R-PM", "2-7" to "R-PM"),
        )
        val z = course("Z", mapOf(2 to listOf("9")), "T-9")   // 16:30-17:20
        val alone = snapshot(listOf(m))
        assertEquals("ongoing M 12 08:10-10:00 R-AM next=- chg=false", now(alone, tuesday, at(8, 30)))
        assertEquals("today M 67 13:20 R-PM chg=false", now(alone, tuesday, at(10, 30)))
        assertEquals("ongoing M 67 13:20-15:10 R-PM next=- chg=false", now(alone, tuesday, at(14, 0)))
        assertEquals("+7 M 12 08:10 wd=2 R-AM chg=false", now(alone, tuesday, at(16, 0)))
        assertEquals(
            listOf("M 12 08:10-10:00 R-AM Upcoming chg=false", "M 67 13:20-15:10 R-PM Upcoming chg=false"),
            today(alone, tuesday, at(7, 0)),
        )
        assertEquals("10:00,13:20,15:10", tile(alone, tuesday, at(9, 0)))
        val withZ = snapshot(listOf(m, z))
        assertEquals("ongoing M 12 08:10-10:00 R-AM next=Z 9 16:30 T-9 chg=false", now(withZ, tuesday, at(8, 30)))
    }

    @Test
    fun `contiguous periods are one block and a missing period splits two`() {
        val three = course("T3", mapOf(2 to listOf("1", "2", "3")), "R-3")    // 08:10-11:10
        assertEquals("ongoing T3 123 08:10-11:10 R-3 next=- chg=false", now(snapshot(listOf(three)), tuesday, at(10, 5)))
        val gap = course("GP", mapOf(2 to listOf("3", "4", "6")), "R-G")       // 10:20-12:10 and 13:20-14:10
        val g = snapshot(listOf(gap))
        assertEquals("ongoing GP 34 10:20-12:10 R-G next=- chg=false", now(g, tuesday, at(10, 30)))
        assertEquals("today GP 6 13:20 R-G chg=false", now(g, tuesday, at(12, 30)))
    }

    @Test
    fun `a period with no known time drops its block`() {
        val unknown = course("U", mapOf(2 to listOf("X")), "R-U")
        val mixed = course("V", mapOf(2 to listOf("3", "X")), "R-V")
        assertEquals("empty", now(snapshot(listOf(unknown)), tuesday, at(9, 0)))
        assertEquals(emptyList<String>(), today(snapshot(listOf(unknown)), tuesday, at(9, 0)))
        val s = snapshot(listOf(unknown, mixed, p7))
        assertEquals("today V 3 10:20 R-V chg=false", now(s, tuesday, at(9, 0)))
        assertEquals("today P7 78 14:20 TR-2 chg=false", now(s, tuesday, at(11, 30)))
        assertEquals(listOf("V 3 10:20-11:10 R-V Upcoming chg=false", "P7 78 14:20-16:20 TR-2 Upcoming chg=false"), today(s, tuesday, at(9, 0)))
        assertEquals("+7 V 3 10:20 wd=2 R-V chg=false", now(snapshot(listOf(unknown, mixed)), tuesday, at(12, 0)))
    }

    // ---- two classes at once, and ties ----

    @Test
    fun `when two courses overlap the first in the list is on and the other's later period start is next`() {
        val c = course("C", mapOf(2 to listOf("4", "5")), "R-C")             // 11:20-13:10
        assertEquals("ongoing P4 34 10:20-12:10 TR-1 next=C 45 11:20 R-C chg=false", now(snapshot(listOf(p4, c, p7)), tuesday, at(10, 30)))
        assertEquals("ongoing P4 34 10:20-12:10 TR-1 next=C 45 12:20 R-C chg=false", now(snapshot(listOf(p4, c, p7)), tuesday, at(11, 30)))
        assertEquals("ongoing C 45 11:20-13:10 R-C next=P7 78 14:20 TR-2 chg=false", now(snapshot(listOf(c, p4, p7)), tuesday, at(11, 30)))
        assertEquals("ongoing C 45 11:20-13:10 R-C next=P7 78 14:20 TR-2 chg=false", now(snapshot(listOf(c, p4, p7)), tuesday, at(12, 15)))
    }

    @Test
    fun `two courses starting together, the first in the list is next`() {
        val d1 = course("D1", mapOf(2 to listOf("3")), "R-1")
        val d2 = course("D2", mapOf(2 to listOf("3", "4")), "R-2")
        assertEquals("today D1 3 10:20 R-1 chg=false", now(snapshot(listOf(d1, d2)), tuesday, at(9, 0)))
        assertEquals("today D2 34 10:20 R-2 chg=false", now(snapshot(listOf(d2, d1)), tuesday, at(9, 0)))
        assertEquals("ongoing D2 34 10:20-12:10 R-2 next=- chg=false", now(snapshot(listOf(d2, d1)), tuesday, at(10, 20)))
        assertEquals("ongoing D1 3 10:20-11:10 R-1 next=D2 34 11:20 R-2 chg=false", now(snapshot(listOf(d1, d2)), tuesday, at(10, 20)))
    }

    // ---- what the phone sends and the watch stores ----

    private val fixtureCourses = """
        [{"courseNo":"CC900","courseName":"Fake Course","instructor":"Ada","credits":3,
          "creditsExact":3.0,"classroom":"TR-313","scheduleJson":"{\"4\":[\"2\",\"3\",\"7\"]}",
          "classroomMapJson":"{}","moodleIdNumber":null,"customColorHex":null,"isManual":false}]
    """.trimIndent()

    private val thursday = LocalDate.of(2026, 11, 5)

    /** The stored copy as a watch writes it; no `course_changes` key when [courseChanges] is null. */
    private fun stored(courseChanges: String?): Preferences {
        val pairs = mutableListOf<Preferences.Pair<*>>(
            stringPreferencesKey("courses_json") to fixtureCourses,
            stringPreferencesKey("accent_hex") to "#112233",
            longPreferencesKey("synced_at_ms") to 1_000L,
            booleanPreferencesKey("logged_in") to true,
        )
        if (courseChanges != null) pairs += stringPreferencesKey("course_changes") to courseChanges
        return preferencesOf(*pairs.toTypedArray())
    }

    @Test
    fun `the watch reads a data item from a phone on today's build`() {
        // Saved from the build before the move: the data item's key and the DataStore key as they are.
        assertEquals("courseChanges", WearProtocol.Schedule.KEY_COURSE_CHANGES)
        val raw = """{"CC900":{"2026-11-05":[{"periods":["2"],"cancelled":true},""" +
            """{"periods":["3"],"cancelled":false,"room":"TR-411","end":"11:00"}]}}"""
        val s = SchedulePersistence.readSnapshot(stored(raw))
        assertEquals(
            listOf("CC900 3 10:20-11:00 TR-411 Upcoming chg=true", "CC900 7 14:20-15:10 TR-313 Upcoming chg=false"),
            today(s, thursday, at(8, 0)),
        )
        assertEquals("today CC900 3 10:20 TR-411 chg=true", now(s, thursday, at(8, 0)))
        assertEquals("ongoing CC900 3 10:20-11:00 TR-411 next=CC900 7 14:20 TR-313 chg=true", now(s, thursday, at(10, 30)))
        assertEquals("today CC900 7 14:20 TR-313 chg=false", now(s, thursday, at(11, 0)))
        assertEquals("14:20,15:10", tile(s, thursday, at(11, 0)))
    }

    @Test
    fun `a stale, missing or malformed course changes item leaves the timetable`() {
        val plainToday = "ongoing CC900 23 09:10-11:10 TR-313 next=- chg=false"
        // A phone that predates the item sends none; an item for another week or course changes nothing today.
        val stale = """{"CC900":{"2026-10-01":[{"periods":["2"],"cancelled":true}]},"OTHER":{"2026-11-05":[{"periods":["2"],"cancelled":true}]}}"""
        for (raw in listOf(null, "", "{}", stale, "[1,2]", """{"CC900":{"2026-11-05":"moved"}}""", "{\"CC900\":")) {
            val s = SchedulePersistence.readSnapshot(stored(raw))
            assertEquals(raw, plainToday, now(s, thursday, at(9, 30)))
        }
    }
}
