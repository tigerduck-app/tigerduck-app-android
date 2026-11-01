package org.ntust.app.tigerduck.shared.timeline

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.changes
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.feed
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.row
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.slots
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.thursday
import java.time.LocalDate
import java.util.Locale

/**
 * The course-changes feed (`/v3/calendar/course-changes`) as the iPhone reads it (tigerduck-app
 * `CourseChangesTests`): rows indexed by course and class day, and only the rows this build can
 * show kept.
 */
class CourseChangesTest {

    @Test
    fun `the feed decodes and drops unknown kinds, valueless rows and other days`() {
        val changes = changes(
            row(42, "cancelled"),
            row(43, "moved_online", values = mapOf("room" to "Teams")),
            row(44, "room"),
            row(45, "note", values = mapOf("note_zh" to "期中考")),
            row(46, "room", days = listOf("2026-11-12"), values = mapOf("room" to "TR-313")),
        )

        val thatDay = changes.changes("CC900", thursday)
        assertEquals("1151", changes.term)
        assertEquals(listOf(42, 45), thatDay.map { it.id })
        assertEquals("an English note falls back to the Chinese one", "期中考", thatDay.last().noteEn)
        assertEquals(listOf(46), changes.changes("CC900", LocalDate.of(2026, 11, 12)).map { it.id })
        assertTrue(changes.changes("CC901", thursday).isEmpty())
        assertEquals(listOf("CC900_20261105_2", "CC900_20261105_7"), slots(changes).map { it.id })
    }

    @Test
    fun `a row of the wrong shape is dropped and the rest of the feed stays`() {
        val badId = row(1, "cancelled").put("id", "1")
        val badCourse = row(2, "cancelled").put("course_no", JSONObject.NULL)
        val badPeriods = row(3, "cancelled").put("periods", "2")
        val badPeriod = row(4, "cancelled").put("periods", JSONArray(listOf(2)))
        val badDays = row(5, "cancelled").put("class_days", "2026-11-05")
        val badRoom = row(6, "room").put("room", 313)
        val good = row(7, "room", values = mapOf("room" to "TR-313"))

        val changes = CourseChanges.decode(
            feed(listOf(badId, badCourse, badPeriods, badPeriod, badDays, badRoom, "not a row", good)),
        )

        assertEquals(listOf(7), changes.changes("CC900", thursday).map { it.id })
    }

    @Test
    fun `a body that is not a feed throws`() {
        for (body in listOf(
            "",
            "not a feed",
            "[]",
            """{"term":"1151"}""",
            """{"term":"1151","changes":{}}""",
            """{"changes":[]}""",
        )) {
            assertThrows(body, JSONException::class.java) { CourseChanges.decode(body) }
        }
    }

    @Test
    fun `a row is indexed under each of its class days and no other`() {
        val changes = changes(row(1, "cancelled", days = listOf("2026-11-19", "2026-11-05", "2026-11-5", "not a day")))

        assertEquals(listOf(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 19)), changes.days("CC900"))
        assertEquals(listOf(1), changes.changes("CC900", LocalDate.of(2026, 11, 19)).map { it.id })
        assertTrue(changes.changes("CC900", LocalDate.of(2026, 11, 12)).isEmpty())
        assertTrue(changes.days("CC901").isEmpty())
    }

    @Test
    fun `a change without periods covers every period and one with periods only those`() {
        val changes = changes(
            row(1, "cancelled"),
            row(2, "room", periods = listOf("3", "4"), values = mapOf("room" to "TR-313")),
        )
        val (all, some) = changes.changes("CC900", thursday)

        assertTrue(all.covers("2") && all.covers("D"))
        assertTrue(some.covers("3") && some.covers("4"))
        assertFalse(some.covers("2"))
        assertTrue(changes.isCancelled("CC900", thursday, "7"))
        assertFalse(changes.isCancelled("CC900", thursday.plusWeeks(1), "7"))
    }

    @Test
    fun `a note reads in Chinese for either script and in English otherwise`() {
        val changes = changes(
            row(1, "note", values = mapOf("note_zh" to "帶筆電", "note_en" to "Bring a laptop")),
            row(2, "note", values = mapOf("note_zh" to "期中考")),
            row(3, "note", values = mapOf("note_zh" to "小考", "note_en" to "")),
        )
        val (both, zhOnly, emptyEn) = changes.changes("CC900", thursday)

        for (locale in listOf(Locale.TRADITIONAL_CHINESE, Locale.SIMPLIFIED_CHINESE, Locale.forLanguageTag("zh-Hant-TW"), Locale.forLanguageTag("zh-Hans"))) {
            assertEquals(locale.toLanguageTag(), "帶筆電", both.note(locale))
        }
        assertEquals("Bring a laptop", both.note(Locale.ENGLISH))
        assertEquals("Bring a laptop", both.note(Locale.JAPANESE))
        assertEquals("期中考", zhOnly.note(Locale.ENGLISH))
        assertEquals("an empty English note is not a missing one", "", emptyEn.note(Locale.ENGLISH))
    }

    @Test
    fun `the empty feed has no term and no changes`() {
        assertEquals("", CourseChanges.EMPTY.term)
        assertTrue(CourseChanges.EMPTY.changes("CC900", thursday).isEmpty())
        assertTrue(CourseChanges.EMPTY.days("CC900").isEmpty())
        assertEquals(CourseChanges.EMPTY, CourseChanges.decode(feed(emptyList(), term = "")))
    }

    @Test
    fun `the same feed decodes equal and a changed row does not`() {
        val first = changes(row(1, "room", values = mapOf("room" to "TR-313")))

        assertEquals(first, changes(row(1, "room", values = mapOf("room" to "TR-313"))))
        assertEquals(first.hashCode(), changes(row(1, "room", values = mapOf("room" to "TR-313"))).hashCode())
        assertNotEquals(first, changes(row(1, "room", values = mapOf("room" to "TR-314"))))
        assertNotEquals(first, CourseChanges.decode(feed(listOf(row(1, "room", values = mapOf("room" to "TR-313"))), term = "1152")))
    }
}
