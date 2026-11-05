package org.ntust.app.tigerduck.clubs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.clubs.FakeClubServer.Answer
import org.ntust.app.tigerduck.clubs.FakeClubServer.Companion.activity
import org.ntust.app.tigerduck.clubs.FakeClubServer.Companion.envelope
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.util.JvmContext
import org.ntust.app.tigerduck.util.testSignIn
import java.io.IOException

/** The activity feed's cache and what the class table takes from it (ClubCacheTests, ClubOccurrencesTests on iOS). */
class ClubActivitiesTest {

    private val server = FakeClubServer()
    private val context = JvmContext()

    private fun feed() = ClubActivities(context.getSharedPreferences("club_activities", 0), server.api)

    private val monday = envelope(activity(1, 9001, "2026-11-02", "19:00", "21:00"))

    @Test
    fun `the feed keeps its ETag, sends it, and a 304 keeps the stored copy`() = runBlocking {
        server.enqueue("activities", Answer(body = monday, headers = mapOf("ETag" to "\"v1\"")))
        server.enqueue("activities", Answer(code = 304))
        val signIn = testSignIn()

        feed().refresh(signIn)
        val reopened = feed()
        assertEquals(listOf(1), reopened.activities.value.map { it.id })
        reopened.refresh(signIn)

        assertEquals(listOf(1), reopened.activities.value.map { it.id })
        val (first, second) = server.requests("activities")
        assertNull(first.header("If-None-Match"))
        assertEquals("\"v1\"", second.header("If-None-Match"))
    }

    @Test
    fun `a failed or unreadable fetch keeps the stored copy and its ETag`() = runBlocking {
        server.enqueue("activities", Answer(body = monday, headers = mapOf("ETag" to "\"v1\"")))
        server.enqueue("activities", Answer(failure = IOException("offline")))
        server.enqueue("activities", Answer(body = "<html>oops</html>", headers = mapOf("ETag" to "\"v2\"")))
        server.enqueue("activities", Answer(code = 304))
        val activities = feed()
        val signIn = testSignIn()

        repeat(3) { activities.refresh(signIn) }

        assertEquals(listOf(1), activities.activities.value.map { it.id })
        assertEquals("\"v1\"", server.requests("activities").last().header("If-None-Match"))
    }

    @Test
    fun `a sign-out drops the feed and its ETag, and an answer for an ended sign-in is not kept`() = runBlocking {
        server.enqueue("activities", Answer(body = monday, headers = mapOf("ETag" to "\"v1\"")))
        val activities = feed()
        val series = SignInSeries()
        activities.refresh(series.current)

        activities.forgetAccount()

        assertEquals(emptyList<ClubActivity>(), activities.activities.value)
        assertEquals(emptyList<ClubActivity>(), feed().activities.value)
        val ended = series.current.also { series.end() }
        activities.refresh(ended)
        assertEquals(1, server.requests("activities").size)
        assertEquals(emptyList<ClubActivity>(), feed().activities.value)
    }

    @Test
    fun `occurrences are the subscribed clubs' one-day activities on the days asked, by day, start and priority`() {
        val feed = ClubApiList.activities(
            envelope(
                listOf(
                    activity(1, 9001, "2026-11-02", "19:00", "21:00"),
                    activity(2, 9002, "2026-11-02", "19:00", "21:30"),
                    activity(3, 9002, "2026-11-02", "18:00", "19:00"),
                    activity(4, 9003, "2026-11-02", "12:00", "13:00"),
                    activity(5, 9001, "2026-11-20", "19:00", "21:00"),
                    """{"id": 6, "name": "Camp", "club_id": 9001, "club_name": "測試社團9001", "date": "2026-11-02",
                        "end_date": "2026-11-04", "start_time": "09:00:00", "end_time": "17:00:00", "location": "",
                        "type": "活動", "content": ""}""",
                ).joinToString(","),
            ),
        )
        val clubs = listOf(SubscribedClub(9002, 0x112233), SubscribedClub(9001, 0x445566))

        val shown = ClubActivities.occurrences(feed, setOf("2026-11-02"), clubs, mapOf(9001 to "Test Chess Club"))

        assertEquals(listOf(3, 2, 1), shown.map { it.id })
        assertEquals(listOf(0, 0, 1), shown.map { it.priority })
        assertEquals(0x445566, shown.last().color)
        assertEquals("Test Chess Club", shown.last().clubName)
        assertEquals("測試社團9002", shown.first().clubName)
        assertEquals(18 * 60, shown.first().start)
    }
}
