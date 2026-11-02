package org.ntust.app.tigerduck.demo

import android.app.Application
import android.content.Context
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AccountWorld
import org.ntust.app.tigerduck.auth.accountWorld
import org.ntust.app.tigerduck.auth.session.AccountEvent
import org.ntust.app.tigerduck.auth.session.SignOutOwed
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Who sees the demo, and from when to when: the real [org.ntust.app.tigerduck.auth.AuthService]
 * and [DemoAccount] over the real stores of a device, with `assets/demo.json`'s account. Nothing
 * reaches a network: the school is a fake, every other request is refused, and the demo signs in
 * on the device. Play only, as [accountWorld] is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DemoSessionTest {

    private val context = RuntimeEnvironment.getApplication()

    /** The sign-out's wipe, on threads of its own, as it runs on the application scope. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Followers that run inside the step that tells them. */
    private val followers = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** What would reach a network: never run. */
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    private val fixture by lazy {
        DemoFixture.parse(context.assets.open("demo.json").bufferedReader().use { it.readText() }, DemoFixture.LANG_EN)
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        followers.cancel()
    }

    private fun world(): AccountWorld =
        accountWorld(context, context.getSharedPreferences("demo-session-credentials", Context.MODE_PRIVATE), appScope, idleScope)

    private suspend fun AccountWorld.demoSignIn(): Boolean = auth.login(fixture.studentId!!, fixture.schoolPassword!!)

    /** The session's wipe is done, and the next sign-in may start. */
    private suspend fun AccountWorld.wipeDone() = withTimeout(WAIT_MS) { sessions.school.awaitOpen() }

    @Test
    fun `the demo sign-in shows as the demo before its data is written`() = runBlocking {
        val world = world()
        val seen = CopyOnWriteArrayList<Boolean>()
        // Runs inside the write that moves it.
        followers.launch { world.dataCache.coursesVersion.drop(1).collect { seen += world.sessions.school.isDemo } }
        followers.launch { world.dataCache.assignmentsVersion.drop(1).collect { seen += world.sessions.school.isDemo } }

        assertTrue(world.demoSignIn())

        assertTrue("the demo's data was written", seen.isNotEmpty())
        assertEquals("every write saw the demo", emptyList<Boolean>(), seen.filterNot { it })
        assertTrue(world.dataCache.loadCourses().isNotEmpty())
    }

    @Test
    fun `everything in the sign-out before the exit still sees the demo`() = runBlocking {
        val world = world()
        assertTrue(world.demoSignIn())
        val seen = CopyOnWriteArrayList<Pair<String, Boolean>>()
        val demo = { world.sessions.school.isDemo }
        // The sign-out's last steps before the exit: it shows as signed out, and says so. The
        // unregister after them is SignOutClearingTest's, which pins the order of the source.
        followers.launch { world.auth.authState.drop(1).collect { seen += "shown" to demo() } }
        followers.launch(start = CoroutineStart.UNDISPATCHED) {
            world.sessions.events.collect { if (it is AccountEvent.SignedOut) seen += "told" to demo() }
        }

        world.auth.logout()

        assertEquals(listOf("shown" to true, "told" to true), seen.toList())
        assertFalse("the exit, last, ended it", demo())
        assertFalse("and with it what the interceptor reads", world.fixtures.demoMode)
    }

    @Test
    fun `a demo session started before this process keeps being one`() = runBlocking {
        assertTrue(world().demoSignIn())

        val restarted = world()

        assertTrue(restarted.sessions.school.isDemo)
        assertTrue(restarted.auth.authState.value)
        assertFalse("no session to build, and nothing goes out for one", restarted.auth.ensureAuthenticated())
        assertTrue("the data stays", restarted.dataCache.loadCourses().isNotEmpty())
    }

    @Test
    fun `a demo session with no account stored shows as signed in, as a clean install photographed in it does`() {
        DebugFixtureStore(context).demoMode = true

        val world = world()

        assertTrue(world.sessions.school.isDemo)
        assertEquals(null, world.credentials.ntustStudentId)
        assertTrue(world.auth.authState.value)
    }

    @Test
    fun `a start that finds a demo sign-out cut short reads the demo until its exit runs`() = runBlocking {
        val demoStudent = fixture.studentId!!
        assertTrue(world().demoSignIn())
        // The process died in the sign-out, marker written and nothing else.
        SignOutOwed(context.getSharedPreferences("tigerduck_account_wipe", Context.MODE_PRIVATE)).mark(demoStudent)

        val restarted = world()

        assertTrue("what a start reads", restarted.sessions.school.isDemo)
        assertTrue(restarted.auth.authState.value)
        assertTrue(restarted.auth.finishOwedSignOut())
        assertFalse(restarted.sessions.school.isDemo)
        assertFalse(restarted.fixtures.demoMode)
        restarted.auth.finishOwedWipe()
        restarted.wipeDone()
        assertFalse(restarted.sessions.school.isDemo)
    }

    @Test
    fun `leaving the demo leaves the next sign-in plain`() = runBlocking {
        val world = world()
        assertTrue(world.demoSignIn())

        world.auth.logout()
        world.wipeDone()

        assertFalse(world.sessions.school.isDemo)
        assertEquals(emptyList<Any>(), world.dataCache.loadCourses())
        assertFalse(world.fixtures.demoMode)
        assertEquals(null, world.fixtures.studentIdOverride)
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
