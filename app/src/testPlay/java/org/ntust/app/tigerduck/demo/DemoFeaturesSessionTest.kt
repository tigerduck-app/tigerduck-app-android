package org.ntust.app.tigerduck.demo

import android.app.Application
import android.content.Context
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AccountWorld
import org.ntust.app.tigerduck.auth.accountWorld
import org.ntust.app.tigerduck.auth.session.SignOutOwed
import org.ntust.app.tigerduck.auth.session.SignOutReason
import org.ntust.app.tigerduck.auth.session.StartsAccountWork
import org.ntust.app.tigerduck.clubs.FakeClubServer.Companion.club
import org.ntust.app.tigerduck.clubs.FakeClubServer.Companion.envelope
import org.ntust.app.tigerduck.shared.time.TaipeiClock
import org.ntust.app.tigerduck.shared.timeline.CourseChange
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The demo account's course changes, transcript and clubs (tigerduck-app#1049) over the real
 * stores of a device: written at its sign-in, and gone on every path that ends it, while what is
 * public and stored stays the device's. Play only, as [accountWorld] is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DemoFeaturesSessionTest {

    private val context = RuntimeEnvironment.getApplication()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    private val fixture by lazy {
        DemoFixture.parse(context.assets.open("demo.json").bufferedReader().use { it.readText() }, DemoFixture.LANG_EN)
    }

    private val demoStudent get() = fixture.studentId!!.uppercase()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        // The directory fetched before: public, the device's.
        context.getSharedPreferences("club_directory", Context.MODE_PRIVATE).edit()
            .putString("payload", envelope(club(9001, "測試棋藝社")))
            .putLong("stored_at", System.currentTimeMillis())
            .putLong("max_age", 600)
            .commit()
    }

    @After
    fun tearDown() = appScope.cancel()

    private fun world(): AccountWorld = accountWorld(
        context, context.getSharedPreferences("demo-features-credentials", Context.MODE_PRIVATE), appScope, idleScope,
    )

    private suspend fun AccountWorld.demoSignIn(): Boolean = auth.login(fixture.studentId!!, fixture.schoolPassword!!)

    private suspend fun AccountWorld.wipeDone() = withTimeout(10_000L) { sessions.school.awaitOpen() }

    private fun clubPrefs(file: String) = context.getSharedPreferences(file, Context.MODE_PRIVATE).getString("payload", null)

    @Test
    fun `the demo's sign-in fills its course changes, transcript and clubs, and its sign-out takes them away`() = runBlocking {
        val world = world()
        assertTrue(world.demoSignIn())

        val shows = world.demoShows
        val wednesday = DemoFeatures.nextDate(3, TaipeiClock.today())!!
        val exam = shows.courseChanges.current().changes("CS3005301", wednesday)
        assertTrue(exam.any { it.kind == CourseChange.Kind.ASSESSMENT })
        assertEquals(world.dataCache.currentSemesterCode(), shows.courseChanges.current().term)
        val report = world.dataCache.loadScoreReport(demoStudent)?.report
        assertEquals(58f, report?.creditSummary?.earned?.total)
        assertEquals(listOf(990001, 990002), shows.clubDirectory.state.value.clubs.map { it.id }.sorted())
        assertEquals(setOf(991001, 991002), shows.clubActivities.activities.value.map { it.id }.toSet())
        assertEquals(setOf(990001, 990002), shows.clubSubscriptions.subscribedIds.toSet())
        assertEquals("the stored directory stays on the device", envelope(club(9001, "測試棋藝社")), clubPrefs("club_directory"))
        assertNull("the activities are in memory alone", clubPrefs("club_activities"))

        world.plans.signOut(SignOutReason.SCHOOL)
        world.wipeDone()

        assertEquals(CourseChanges.EMPTY, shows.courseChanges.current())
        assertEquals(listOf(9001), shows.clubDirectory.state.value.clubs.map { it.id })
        assertEquals(emptyList<Int>(), shows.clubActivities.activities.value.map { it.id })
        assertEquals(emptyList<Int>(), shows.clubSubscriptions.subscribedIds)
        assertNull(world.dataCache.loadScoreReport(demoStudent))
        assertEquals("nothing of the demo left on disk", CourseChanges.EMPTY, world().demoShows.courseChanges.current())
    }

    @OptIn(StartsAccountWork::class)
    @Test
    fun `a demo club unsubscribed stays so when the demo's data is written again`() = runBlocking {
        val world = world()
        assertTrue(world.demoSignIn())
        world.demoShows.clubSubscriptions.unsubscribe(990001, world.sessions.school.current)

        world.demoAccount.reapply()

        assertEquals(listOf(990002), world.demoShows.clubSubscriptions.subscribedIds)
        assertEquals("still in the directory", listOf(990001, 990002), world.demoShows.clubDirectory.state.value.clubs.map { it.id }.sorted())
    }

    @Test
    fun `a demo sign-out cut short empties the course changes at the next start`() = runBlocking {
        assertTrue(world().demoSignIn())
        // The process died in the sign-out, marker written and nothing else.
        SignOutOwed(context.getSharedPreferences("tigerduck_account_wipe", Context.MODE_PRIVATE)).mark(demoStudent)

        val restarted = world()
        assertNotEquals("the demo's feed is on disk", CourseChanges.EMPTY, restarted.demoShows.courseChanges.current())
        assertTrue(restarted.plans.finishOwedSignOut())

        assertEquals(CourseChanges.EMPTY, restarted.demoShows.courseChanges.current())
    }

    @Test
    fun `a student's sign-out keeps the public course-change feed and the directory`() = runBlocking {
        val world = world()
        world.demoShows.courseChanges.replaceFeed(FEED)
        assertTrue(world.auth.login("B00015999", "made-up-password"))

        world.plans.signOut(SignOutReason.SCHOOL)
        world.wipeDone()

        assertEquals(CourseChanges.decode(FEED), world.demoShows.courseChanges.current())
        assertEquals(listOf(9001), world.demoShows.clubDirectory.state.value.clubs.map { it.id })
    }

    private companion object {
        val FEED = """{"term":"1151","changes":[{"id":2,"course_no":"CC900","kind":"room","room":"T4-502","periods":["3"],"class_days":["2026-11-12"]}]}"""
    }
}
