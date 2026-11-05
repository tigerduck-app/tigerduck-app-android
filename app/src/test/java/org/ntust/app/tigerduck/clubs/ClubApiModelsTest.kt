package org.ntust.app.tigerduck.clubs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The API's rows, read as the Apple apps read them (ClubAPIModelsTests). */
class ClubApiModelsTest {

    private fun envelope(rows: String) = """{"success": true, "data": [$rows], "error": null, "meta": null}"""

    private fun activity(id: Int, date: String = "2026-11-02", end: String? = date, from: String? = "19:00:00", to: String? = "21:00:00") =
        """{"id": $id, "name": "Opening night", "club_id": 9001, "club_name": "測試棋藝社", "date": "$date",
            "end_date": ${end?.let { "\"$it\"" }}, "start_time": ${from?.let { "\"$it\"" }}, "end_time": ${to?.let { "\"$it\"" }},
            "location": "TR-000", "type": "社課或會議", "content": "Bring a board."}"""

    @Test
    fun `a bad row is left out, a repeated id keeps its first row, and the rest read`() {
        val clubs = ClubApiList.clubs(
            envelope(
                """{"id": 9001, "name": "測試棋藝社", "en_name": "Test Chess Club", "attribute": "學藝性", "tagline": null},
                   {"id": "9002", "name": "bad id"},
                   {"id": 9003, "name": "測試吉他社", "en_name": 7},
                   {"id": 9004.5, "name": "half"},
                   {"id": 9001, "name": "second row of 9001"},
                   {"id": 9005, "name": "測試登山社"}""",
            ),
        )
        assertEquals(listOf(9001, 9005), clubs.map { it.id })
        assertEquals("測試棋藝社", clubs[0].name)
        assertEquals("Test Chess Club", clubs[0].enName)
        assertNull(clubs[1].enName)
        assertNull(clubs[1].attribute)
    }

    @Test
    fun `a list none of whose rows reads is unreadable, and an empty one is not`() {
        assertThrows(ClubListUnreadableException::class.java) { ClubApiList.clubs(envelope("""{"id": "x"}""")) }
        assertThrows(ClubListUnreadableException::class.java) { ClubApiList.clubs("<html>503</html>") }
        assertThrows(ClubListUnreadableException::class.java) { ClubApiList.clubs("""{"data": {}}""") }
        assertEquals(emptyList<ClubCard>(), ClubApiList.clubs(envelope("")))
    }

    @Test
    fun `only an activity on one day with both times, ending after it starts, has a place`() {
        val read = ClubApiList.activities(
            envelope(
                listOf(
                    activity(1),
                    activity(2, end = "2026-11-03"),
                    activity(3, from = null),
                    activity(4, from = "21:00:00", to = "19:00:00"),
                    activity(5, to = "25:00:00"),
                    activity(6, from = "19:00"),
                ).joinToString(","),
            ),
        )
        assertEquals(listOf(1, 2, 3, 4, 5, 6), read.map { it.id })
        assertEquals(OneDay("2026-11-02", 19 * 60, 21 * 60), read[0].oneDay)
        read.drop(1).forEach { assertNull("activity ${it.id}", it.oneDay) }
        assertEquals(ClubActivityKind.Meeting, read[0].kind)
    }

    @Test
    fun `an hour past any number, or past 23, leaves its row no place, and the rest of the feed reads`() {
        val read = ClubApiList.activities(
            envelope(listOf(activity(1), activity(2, from = "2147483648:00:00"), activity(3, to = "25:00:00")).joinToString(",")),
        )
        val placed = runCatching {
            ClubActivities.occurrences(read, setOf("2026-11-02"), listOf(SubscribedClub(9001, 0x123456)), emptyMap()).map { it.id }
        }

        assertNull(placed.exceptionOrNull())
        assertEquals(listOf(1), placed.getOrNull())
        assertEquals(listOf(1, 2, 3), read.map { it.id })
    }

    @Test
    fun `the kind is the feed's type, and one this build does not know keeps its name`() {
        assertEquals(ClubActivityKind.Event, ClubActivityKind.of("活動"))
        assertEquals(ClubActivityKind.Meeting, ClubActivityKind.of("社課或會議"))
        assertEquals(ClubActivityKind.Other("展演"), ClubActivityKind.of("展演"))
    }

    @Test
    fun `the name shown is the Chinese one in Chinese, and the English one elsewhere when there is one`() {
        val club = ClubCard(9001, "測試棋藝社", "Test Chess Club", null, null)
        assertEquals("測試棋藝社", club.displayName(chinese = true))
        assertEquals("Test Chess Club", club.displayName(chinese = false))
        assertEquals("測試棋藝社", club.copy(enName = " ").displayName(chinese = false))
    }
}
