package org.ntust.app.tigerduck.clubs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.clubs.FakeClubServer.Answer
import org.ntust.app.tigerduck.clubs.FakeClubServer.Companion.club
import org.ntust.app.tigerduck.clubs.FakeClubServer.Companion.envelope
import org.ntust.app.tigerduck.util.JvmContext
import java.time.Instant

/** The club list the subscribe screen shows (ClubCacheTests and ClubScreensTests on iOS). */
class ClubDirectoryTest {

    private val server = FakeClubServer()
    private val context = JvmContext()
    private var now = Instant.parse("2026-11-05T04:00:00Z")

    private fun directory(chinese: Boolean = true) =
        ClubDirectory(context.getSharedPreferences("club_directory", 0), server.api, { chinese }, { now })

    private fun card(id: Int, name: String, en: String? = null, attribute: String? = null) = ClubCard(id, name, en, attribute, null)

    @Test
    fun `clubs list by attribute in the school's order, then by name, then by id`() {
        val clubs = listOf(
            card(1, "乙測試社", attribute = "體育性"),
            card(2, "甲測試社", attribute = "體育性"),
            card(3, "測試學會", attribute = "學藝性"),
            card(4, "測試自治會", attribute = "自治性"),
            card(5, "測試無類社", attribute = "其他"),
            card(6, "測試無類社", attribute = null),
            card(7, "測試服務社", attribute = "服務性"),
            card(8, "測試聯誼社", attribute = "聯誼性"),
            card(9, "測試藝術社", attribute = "藝術性"),
        )

        val listed = ClubDirectory.listed(clubs.shuffled(java.util.Random(7)), chinese = true).map { it.id }

        assertEquals(3, listed[0])
        assertEquals(9, listed[1])
        assertEquals(setOf(1, 2), listed.subList(2, 4).toSet())
        assertEquals(listOf(8, 7, 4, 5, 6), listed.subList(4, 9))
    }

    @Test
    fun `in English the clubs sort by their English names, case aside`() {
        val clubs = listOf(card(1, "測試甲", "beta Club", "學藝性"), card(2, "測試乙", "Alpha Club", "學藝性"), card(3, "測試丙", null, "學藝性"))
        assertEquals(listOf(2, 1, 3), ClubDirectory.listed(clubs, chinese = false).map { it.id })
    }

    @Test
    fun `search matches every word in the Chinese or the English name, case and width aside`() {
        val clubs = listOf(card(1, "測試棋藝社", "Test Chess Club"), card(2, "測試吉他社", "Test Guitar Club"), card(3, "登山社", null))

        assertEquals(listOf(1), ClubDirectory.matching("棋藝", clubs).map { it.id })
        assertEquals(listOf(1), ClubDirectory.matching("chess  test", clubs).map { it.id })
        assertEquals(listOf(2), ClubDirectory.matching("ＧＵＩＴＡＲ", clubs).map { it.id })
        assertEquals(listOf(1, 2), ClubDirectory.matching("測試 club", clubs).map { it.id })
        assertEquals(emptyList<Int>(), ClubDirectory.matching("chess guitar", clubs).map { it.id })
        assertEquals(clubs, ClubDirectory.matching("  ", clubs))
    }

    @Test
    fun `the stored directory is fetched again only once it is older than its max-age`() = runBlocking {
        server.enqueue("clubs", Answer(body = envelope(club(9001, "測試棋藝社")), headers = mapOf("Cache-Control" to "max-age=600")))
        val first = directory()
        first.refresh()
        assertEquals(listOf(9001), first.state.value.clubs.map { it.id })

        now = now.plusSeconds(599)
        val reopened = directory()
        assertEquals(listOf(9001), reopened.state.value.clubs.map { it.id })
        reopened.refresh()
        assertEquals(1, server.requests("clubs").size)

        now = now.plusSeconds(1)
        server.enqueue("clubs", Answer(body = envelope(club(9002, "測試吉他社")), headers = mapOf("Cache-Control" to "max-age=600")))
        reopened.refresh()
        assertEquals(2, server.requests("clubs").size)
        assertEquals(listOf(9002), reopened.state.value.clubs.map { it.id })
    }

    @Test
    fun `a failed fetch keeps the stored clubs and says why, and a 503 at night says it is the maintenance`() = runBlocking {
        server.enqueue("clubs", Answer(body = envelope(club(9001, "測試棋藝社")), headers = mapOf("Cache-Control" to "max-age=0")))
        val clubs = directory()
        clubs.refresh()

        now = Instant.parse("2026-11-05T18:30:00Z") // 02:30 in Taipei
        server.enqueue("clubs", Answer(code = 503, body = "<html></html>"))
        clubs.refresh()
        assertEquals(listOf(9001), clubs.state.value.clubs.map { it.id })
        assertTrue(clubs.state.value.underMaintenance)
        assertTrue(clubs.state.value.failure is ClubsUnavailableException)

        now = Instant.parse("2026-11-05T04:00:00Z") // 12:00 in Taipei
        server.enqueue("clubs", Answer(code = 503, body = "<html></html>"))
        clubs.refresh()
        assertFalse(clubs.state.value.underMaintenance)

        server.enqueue("clubs", Answer(body = envelope(club(9001, "測試棋藝社"))))
        clubs.refresh()
        assertNull(clubs.state.value.failure)
    }

    @Test
    fun `a directory that cannot be read keeps the stored one`() = runBlocking {
        server.enqueue("clubs", Answer(body = envelope(club(9001, "測試棋藝社")), headers = mapOf("Cache-Control" to "max-age=0")))
        val clubs = directory()
        clubs.refresh()
        server.enqueue("clubs", Answer(body = "<html>not json</html>"))
        clubs.refresh()
        assertEquals(listOf(9001), directory().state.value.clubs.map { it.id })
    }
}
