package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.Holiday
import org.ntust.app.tigerduck.academic.SemesterTerm
import org.ntust.app.tigerduck.data.sharing.ImportedPerson
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.time.TaipeiClock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** The shown term as an ICS file. Ported from iOS `ClassTableCalendarFileTests`; course numbers and rooms are made up. */
class ClassTableCalendarFileTest {
    private typealias File = ClassTableCalendarFile

    private fun day(month: Int, day: Int, year: Int = 2026) = LocalDate.of(year, month, day)

    private fun at(month: Int, day: Int, hour: Int, minute: Int, year: Int = 2026) = LocalDateTime.of(year, month, day, hour, minute)

    /** Monday 14 September 2026 to Friday 8 January 2027, a holiday on 30 September and another the user keeps classes on. */
    private fun calendar(vararg extra: Holiday) = AcademicCalendar(
        revision = 1L,
        terms = listOf(SemesterTerm("1151", day(9, 14), day(1, 8, year = 2027))),
        holidays = listOf(
            Holiday(1, "國慶補假", "Holiday", day(9, 30), day(9, 30)),
            Holiday(2, "校慶補假", "Anniversary", day(10, 7), day(10, 9)),
        ) + extra,
    )

    private val term = File.term("1151", ownTable = true, calendar = calendar(), optedIn = setOf(2))!!

    private val structures = Course.fromSchedule(
        courseNo = "CS1001301", courseName = "資料結構", instructor = "王大明", classroom = "TR-313, IB-201",
        schedule = mapOf(3 to listOf("6", "4", "3")),
        classroomMap = mapOf("3-3" to "TR-313", "3-4" to "TR-313", "3-6" to "IB-201"),
    )

    // 12:00 in Taipei.
    private val now = Instant.parse("2026-11-06T04:00:00Z")

    private fun lines(events: List<ClassTableCalendarFile.Event>, term: ClassTableCalendarFile.Term = this.term) =
        File.text(events, term, now).split("\r\n")

    @Test
    fun `consecutive periods of a course on a day make one event, and a gap starts another`() {
        val events = File.events(listOf(structures), term, owner = null)

        assertEquals(listOf("1151-CS1001301-3-3@tigerduck.app", "1151-CS1001301-3-6@tigerduck.app"), events.map { it.uid })
        assertEquals(listOf(at(9, 16, 10, 20), at(9, 16, 13, 20)), events.map { it.start })
        assertEquals(listOf(at(9, 16, 12, 10), at(9, 16, 14, 10)), events.map { it.end })
        assertEquals(listOf("TR-313", "IB-201"), events.map { it.location })
        assertEquals(listOf("資料結構", "資料結構"), events.map { it.summary })
        assertEquals(listOf("王大明\nCS1001301", "王大明\nCS1001301"), events.map { it.description })
    }

    @Test
    fun `each meeting repeats weekly from its first day in the term to the term's last day, in Taipei time`() {
        val lines = lines(File.events(listOf(structures), term, owner = null))

        assertTrue(lines.contains("DTSTART;TZID=${TaipeiClock.zone.id}:20260916T102000"))
        assertTrue(lines.contains("DTEND;TZID=${TaipeiClock.zone.id}:20260916T121000"))
        assertEquals(2, lines.count { it == "RRULE:FREQ=WEEKLY;UNTIL=20270108T155959Z" })
        assertEquals(2, lines.count { it == "DTSTAMP:20261106T040000Z" })
        assertTrue(lines.contains("TZID:${TaipeiClock.zone.id}"))
        assertTrue(lines.contains("TZOFFSETFROM:+0800"))
        assertTrue(lines.contains("TZOFFSETTO:+0800"))
        assertEquals(listOf("BEGIN:VCALENDAR", "VERSION:2.0"), lines.take(2))
        assertEquals(listOf("END:VCALENDAR", ""), lines.takeLast(2))
        assertEquals(2, lines.count { it == "BEGIN:VEVENT" })
        assertEquals(lines.count { it.startsWith("BEGIN:") }, lines.count { it.startsWith("END:") })
    }

    @Test
    fun `a meeting starts on the term's first such weekday, and a weekday the term never reaches has none`() {
        val swimming = Course.fromSchedule(courseNo = "PE1010001", courseName = "Swimming", schedule = mapOf(7 to listOf("1"), 1 to listOf("2")))
        assertEquals(listOf(at(9, 14, 9, 10), at(9, 20, 8, 10)), File.events(listOf(swimming), term, owner = null).map { it.start })

        // Wednesday to Friday.
        val summer = AcademicCalendar(1L, listOf(SemesterTerm("114H", day(7, 1), day(7, 3))), emptyList())
        val short = File.term("114H", ownTable = true, calendar = summer, optedIn = emptySet())!!
        assertEquals(emptyList<ClassTableCalendarFile.Event>(), File.events(listOf(swimming), short, owner = null))
    }

    @Test
    fun `a holiday takes its day's meetings out, except one the user keeps classes on`() {
        val zone = TaipeiClock.zone.id
        assertEquals(
            listOf("EXDATE;TZID=$zone:20260930T102000", "EXDATE;TZID=$zone:20260930T132000"),
            lines(File.events(listOf(structures), term, owner = null)).filter { it.startsWith("EXDATE") },
        )
    }

    @Test
    fun `a series begins at its first meeting a holiday leaves, under the same uid and end`() {
        val firstWednesday = Holiday(3, "停課", "No classes", day(9, 16), day(9, 16))
        val term = File.term("1151", ownTable = true, calendar = calendar(firstWednesday), optedIn = setOf(2))!!
        val events = File.events(listOf(structures), term, owner = null)

        assertEquals(listOf("1151-CS1001301-3-3@tigerduck.app", "1151-CS1001301-3-6@tigerduck.app"), events.map { it.uid })
        assertEquals(listOf(at(9, 23, 10, 20), at(9, 23, 13, 20)), events.map { it.start })
        assertEquals(listOf(at(9, 23, 12, 10), at(9, 23, 14, 10)), events.map { it.end })
        val lines = lines(events, term)
        assertFalse(lines.any { "20260916T" in it })
        assertEquals(2, lines.count { it == "RRULE:FREQ=WEEKLY;UNTIL=20270108T155959Z" })
        assertEquals(2, lines.count { it.startsWith("EXDATE") })

        val oneWeek = AcademicCalendar(1L, listOf(SemesterTerm("1151", day(9, 14), day(9, 18))), listOf(firstWednesday))
        val closed = File.term("1151", ownTable = true, calendar = oneWeek, optedIn = emptySet())!!
        assertEquals(emptyList<ClassTableCalendarFile.Event>(), File.events(listOf(structures), closed, owner = null))
    }

    @Test
    fun `the menu's check finds a timed period on a weekday column`() {
        val untimed = listOf(
            Course.fromSchedule(courseNo = "GE1100001", courseName = "Thesis"),
            Course.fromSchedule(courseNo = "GE1100002", courseName = "Practicum", schedule = mapOf(2 to listOf("E"))),
            Course.fromSchedule(courseNo = "GE1100003", courseName = "Fieldwork", schedule = mapOf(0 to listOf("1"), 8 to listOf("2"))),
        )
        assertFalse(File.hasTimedMeeting(emptyList()))
        assertFalse(File.hasTimedMeeting(untimed))
        assertTrue(File.hasTimedMeeting(untimed + structures))
        assertEquals(listOf("資料結構", "資料結構"), File.events(untimed + structures, term, owner = null).map { it.summary })
    }

    @Test
    fun `someone else's table gets uids of its own, so a calendar holding both keeps both`() {
        val own = File.events(listOf(structures), term, owner = null)
        val ming = File.events(listOf(structures), term, owner = "5F0B6B1E")

        assertEquals(listOf("1151-CS1001301-3-3-5F0B6B1E@tigerduck.app", "1151-CS1001301-3-6-5F0B6B1E@tigerduck.app"), ming.map { it.uid })
        assertTrue(own.map { it.uid }.intersect(ming.map { it.uid }.toSet()).isEmpty())
    }

    @Test
    fun `the shown table gives its own courses, under its person's uids`() {
        val person = ImportedPerson("5F0B6B1E", "B11300001", "Ming", Instant.EPOCH, null, emptyList())
        val shown = ShownClassTable(person, "1151", listOf("1151"), listOf(structures), 0)

        assertEquals(
            listOf("1151-CS1001301-3-3-5F0B6B1E@tigerduck.app", "1151-CS1001301-3-6-5F0B6B1E@tigerduck.app"),
            File.events(shown, term).map { it.uid },
        )
        assertEquals(
            listOf("1151-CS1001301-3-3@tigerduck.app", "1151-CS1001301-3-6@tigerduck.app"),
            File.events(shown.copy(person = null), term).map { it.uid },
        )
    }

    @Test
    fun `text is escaped, control characters dropped, and lines folded at 75 octets without splitting a character`() {
        val name = "Seminar; Ethics, Law \\ Society\r\nPart\t2\u0007"
        val long = "資訊安全與密碼學".repeat(6)
        val seminar = Course.fromSchedule(courseNo = "CS4001301", courseName = name, instructor = "Lin, Mei", schedule = mapOf(1 to listOf("A")))
        val crypto = Course.fromSchedule(courseNo = "CS4002301", courseName = long, schedule = mapOf(2 to listOf("B")))
        val text = File.text(File.events(listOf(seminar, crypto), term, owner = null), term, now)

        assertTrue(text, text.contains("SUMMARY:Seminar\\; Ethics\\, Law \\\\ Society\\nPart\t2\r\n"))
        assertTrue(text, text.contains("DESCRIPTION:Lin\\, Mei\\nCS4001301\r\n"))
        val rows = text.split("\r\n")
        assertTrue(rows.all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertEquals(2, rows.count { it.startsWith(" ") })
        assertFalse(text.replace("\r\n", "").contains("\n"))
        // Unfolded, the long name comes back whole.
        val unfolded = text.replace("\r\n ", "").split("\r\n")
        assertTrue(unfolded.contains("SUMMARY:$long"))
        assertTrue(unfolded.contains("DTSTART;TZID=${TaipeiClock.zone.id}:20260914T182500"))
        assertTrue(unfolded.contains("DTSTART;TZID=${TaipeiClock.zone.id}:20260915T192000"))
    }

    @Test
    fun `a term the academic calendar does not have gives no dates, and the user's holiday choices hold only on their own table`() {
        assertNull(File.term("1142", ownTable = true, calendar = calendar(), optedIn = setOf(2)))

        val own = File.term("1151", ownTable = true, calendar = calendar(), optedIn = setOf(2))
        val ming = File.term("1151", ownTable = false, calendar = calendar(), optedIn = setOf(2))
        assertNotNull(own)
        assertEquals("1151", own!!.code)
        assertEquals(day(9, 14), own.start)
        assertEquals(day(1, 8, year = 2027), own.end)
        assertFalse(day(10, 7) in own.quietDays)
        assertTrue(day(10, 7) in ming!!.quietDays)
        assertFalse(day(10, 6) in ming.quietDays)
    }
}
