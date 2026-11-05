package org.ntust.app.tigerduck.ui.screen.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.clubs.ClubActivities
import org.ntust.app.tigerduck.clubs.ClubDirectory
import org.ntust.app.tigerduck.clubs.ClubSubscriptions
import org.ntust.app.tigerduck.clubs.FakeClubServer
import org.ntust.app.tigerduck.clubs.FakeClubServer.Answer
import org.ntust.app.tigerduck.clubs.FakeClubServer.Companion.activity
import org.ntust.app.tigerduck.clubs.FakeClubServer.Companion.club
import org.ntust.app.tigerduck.clubs.FakeClubServer.Companion.envelope
import org.ntust.app.tigerduck.util.testSignIn
import org.ntust.app.tigerduck.util.JvmContext
import java.time.Instant

/** What Home's time machine reads of the stores the class table's clubs use, none of them a real server. */
class FeedHomeClubsTest {

    private val server = FakeClubServer()
    private val context = JvmContext()
    private val language = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var chinese = true
    private var minute = 0L

    private val activities = ClubActivities(context.getSharedPreferences("club_activities", 0), server.api)
    private val subscriptions = ClubSubscriptions(context.getSharedPreferences("club_subscriptions", 0)) {
        Instant.ofEpochSecond(1_793_620_800L + 60 * ++minute)
    }
    private val directory = ClubDirectory(
        context.getSharedPreferences("club_directory", 0), server.api, { chinese }, language, CoroutineScope(Dispatchers.Unconfined),
    ) { Instant.parse("2026-11-05T04:00:00Z") }
    private val clubs = FeedHomeClubs(activities, subscriptions, directory, language)
    private val signIn = testSignIn()

    private val day = setOf("2026-11-05")

    private fun fetch() = runBlocking {
        server.enqueue(
            "activities",
            Answer(body = envelope(listOf(activity(1, 9001, "2026-11-05", "19:00", "21:00"), activity(2, 9002, "2026-11-05", "18:00", "19:00")).joinToString(","))),
        )
        server.enqueue("clubs", Answer(body = envelope(listOf(club(9001, "測試棋藝社", "Test Chess Club"), club(9002, "測試登山社", "Test Hiking Club")).joinToString(","))))
        activities.refresh(signIn)
        directory.refresh()
    }

    @Test
    fun `only the subscribed clubs' activities come, in the user's order, in the club's color and name`() {
        fetch()
        assertEquals(emptyList<Int>(), clubs.occurrences(day).map { it.id })

        subscriptions.subscribe(9002, signIn)
        subscriptions.subscribe(9001, signIn)
        subscriptions.setColor(0x445566, 9001, signIn)

        val found = clubs.occurrences(day)
        assertEquals(listOf(2, 1), found.map { it.id })
        assertEquals(listOf(0, 1), found.map { it.priority })
        assertEquals(0x445566, found.last().color)
        assertEquals(listOf("測試登山社", "測試棋藝社"), found.map { it.clubName })
        assertEquals(emptyList<Int>(), clubs.occurrences(setOf("2026-11-06")).map { it.id })
    }

    @Test
    fun `the feed, the subscriptions, their colors and ranks, the names and the language are each a change`() = runBlocking {
        var changes = 0
        val job = launch(Dispatchers.Unconfined) { clubs.changes.collect { changes++ } }
        fun moved(what: String, action: () -> Unit) {
            val before = changes
            action()
            assertTrue("$what is a change", changes > before)
        }

        moved("the feed and the directory") { fetch() }
        moved("a subscription") { subscriptions.subscribe(9001, signIn) }
        moved("another") { subscriptions.subscribe(9002, signIn) }
        moved("a rank") { subscriptions.reorder(listOf(9002, 9001), signIn) }
        moved("a color") { subscriptions.setColor(0x445566, 9001, signIn) }
        moved("the language") { chinese = false; language.tryEmit(Unit) }
        assertEquals(listOf("Test Hiking Club", "Test Chess Club"), clubs.occurrences(day).map { it.clubName })
        job.cancel()
    }
}
