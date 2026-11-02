package org.ntust.app.tigerduck.demo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
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
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.auth.AccountWorld
import org.ntust.app.tigerduck.auth.accountWorld
import org.ntust.app.tigerduck.auth.session.AccountEvent
import org.ntust.app.tigerduck.auth.session.SignOutOwed
import org.ntust.app.tigerduck.auth.session.StartsAccountWork
import org.ntust.app.tigerduck.coursechanges.CourseChangeStore
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.emptyclassroom.ClassroomDemoData
import org.ntust.app.tigerduck.emptyclassroom.ClassroomUsageService
import org.ntust.app.tigerduck.network.NetworkChecker
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.emptyclassroom.AppFinderEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

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

    /** What the school was sent, as its sign-in posts. */
    private val posts = AtomicInteger()

    private fun world(): AccountWorld = accountWorld(
        context, context.getSharedPreferences("demo-session-credentials", Context.MODE_PRIVATE), appScope, idleScope,
        schoolAnswering = { posts.incrementAndGet() },
    )

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
    fun `the demo posts nothing to the school, whatever asks`() = runBlocking {
        val world = world()
        assertTrue(world.demoSignIn())

        assertFalse(world.auth.ensureAuthenticated())
        assertTrue("nothing to try", world.auth.retryStoredPassword())
        // Past the check above, the one behind it.
        assertFalse(world.auth.attemptRelogin(world.tokens.session.signIns.current))

        assertEquals(0, posts.get())
    }

    @Test
    fun `the demo's classmates are imported at its sign-in, and nobody else's`() = runBlocking {
        val world = world()
        assertTrue(world.auth.login(STUDENT, "made-up-password"))
        assertEquals(emptyList<Any>(), world.imported.tables.value.people)
        world.auth.logout()
        world.wipeDone()

        assertTrue(world.demoSignIn())

        assertEquals(fixture.classmates!!.map { it.nickname }, world.imported.tables.value.people.map { it.nickname })
    }

    @Test
    fun `writing the demo's data again on a start writes it, in the demo alone`() = runBlocking {
        val world = world()
        world.demoAccount.reapply()
        assertEquals("not the demo", emptyList<Any>(), world.dataCache.loadCourses())
        assertTrue(world.demoSignIn())
        world.dataCache.saveCourses(emptyList(), world.tokens.session.signIns.current)

        world.demoAccount.reapply()

        assertTrue(world.dataCache.loadCourses().isNotEmpty())
    }

    @Test
    fun `a sign-out as the demo's data is written leaves none of it`() = runBlocking {
        val world = world()
        val fired = AtomicBoolean()
        // Runs inside the first write, and lets the rest go on behind the wipe.
        followers.launch {
            world.dataCache.coursesVersion.drop(1).collect {
                if (fired.compareAndSet(false, true)) {
                    world.auth.logout()
                    runBlocking { world.wipeDone() }
                }
            }
        }

        assertFalse(world.demoSignIn())

        assertTrue(fired.get())
        assertEquals(null, world.credentials.ntustStudentId)
        assertFalse(world.auth.authState.value)
        assertEquals(emptyList<Any>(), world.dataCache.loadAssignments())
        assertEquals(emptyList<Any>(), world.bulletinCache.load())
        assertEquals(emptyList<Any>(), world.dataCache.loadCalendarEvents())
    }

    @Test
    fun `a sign-out landing between the demo's store and its showing shows nothing`() = runBlocking {
        val world = world()
        val fired = AtomicBoolean()
        // Told in the step before the account shows, the account stored by then.
        followers.launch(start = CoroutineStart.UNDISPATCHED) {
            world.sessions.events.collect {
                if (it is AccountEvent.SignedIn && fired.compareAndSet(false, true)) world.auth.logout()
            }
        }

        assertFalse(world.demoSignIn())
        world.wipeDone()

        assertTrue(fired.get())
        assertFalse(world.auth.authState.value)
        assertFalse(world.sessions.school.isDemo)
        assertEquals(null, world.credentials.ntustStudentId)
    }

    @Test
    fun `a student signing in after the demo finds nothing of it, and the demo nothing of the student before`() = runBlocking {
        val world = world()
        assertTrue(world.auth.login(STUDENT, "made-up-password"))
        val semester = world.dataCache.currentSemesterCode()
        world.dataCache.saveCourses(listOf(Course(courseNo = "CS1001301", courseName = "Of the student")), semester, world.tokens.session.signIns.current)

        assertTrue("signed in over them", world.demoSignIn())
        assertEquals(fixture.studentId, world.credentials.ntustStudentId)
        assertEquals(fixture.courses!!.map { it.courseNo }.sorted(), world.dataCache.loadCourses().map { it.courseNo }.sorted())

        world.auth.logout()
        world.wipeDone()
        assertTrue(world.auth.login(STUDENT, "made-up-password"))

        assertFalse(world.sessions.school.isDemo)
        assertFalse(world.tokens.session.signIns.current.isDemo)
        assertEquals(emptyList<Any>(), world.dataCache.loadCourses())
        assertEquals(emptyList<Any>(), world.dataCache.loadAssignments())
        assertEquals(emptyList<Any>(), world.bulletinCache.load())
        assertEquals(emptyList<Any>(), world.imported.tables.value.people)
        assertEquals(null, world.fixtures.studentIdOverride)
    }

    @Test
    fun `the exit lowers the stored flag first, and the session's answer after it`() = runBlocking {
        val world = world()
        assertTrue(world.demoSignIn())
        var answerAsTheFlagWent: Boolean? = null
        val hold = onceAsWritten(FIXTURE_PREFS, DEMO_MODE_KEY) { answerAsTheFlagWent = world.sessions.school.isDemo }

        world.auth.logout()

        assertEquals("a flag that will not clear leaves the session as the flag reads", true, answerAsTheFlagWent)
        assertNotNull(hold)
    }

    @Test
    fun `the finder's calendar and credentials are the demo's while it lasts`() = runBlocking {
        val world = world()
        assertTrue(world.demoSignIn())
        world.credentials.signOutSchoolAccount()
        val environment = AppFinderEnvironment(
            world.auth, world.academicCalendar, world.dataCache, world.courseService, world.appPrefs,
            NetworkChecker(context), CourseChangeStore(context, OkHttpClient(), world.appPrefs), world.sessions,
        )

        assertEquals(AcademicCalendar.EMPTY, environment.calendar())
        assertEquals(ClassroomUsageService.Credentials("", ""), environment.credentials())
    }

    @Test
    fun `the demo's rooms answer in the demo alone`() = runBlocking {
        val world = world()
        val rooms = ClassroomDemoData(context, world.sessions.school, world.appPrefs)
        val monday = LocalDate.of(2026, 11, 2)
        assertEquals(null, rooms.usage(monday))

        assertTrue(world.demoSignIn())

        assertNotNull(rooms.usage(monday))
    }

    @Test
    fun `the school can be asked by a student signed in, and not by the demo`() = runBlocking {
        val world = world()
        assertFalse("nobody signed in", world.auth.canAskSchool)
        assertTrue(world.auth.login(STUDENT, "made-up-password"))
        assertTrue(world.auth.canAskSchool)

        world.auth.logout()
        world.wipeDone()
        assertTrue(world.demoSignIn())

        assertFalse(world.auth.canAskSchool)
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
        assertTrue("a demo library stays, as a real library account does", world.demoAccount.isLibrarySignedIn)
    }

    /**
     * Runs [action] once, as [key] of the preferences [file] is written, inside that write: the
     * listeners of a write on the main thread, which this test is, run in it. What [action] does
     * may write the same file again.
     */
    private fun onceAsWritten(file: String, key: String, action: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val fired = AtomicBoolean()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == key && fired.compareAndSet(false, true)) action()
        }
        context.getSharedPreferences(file, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    @Test
    fun `a sign-out landing just before the demo goes on leaves no demo behind`() = runBlocking {
        val world = world()
        // The step right before the demo goes on: what the account change owes the browser.
        val hold = onceAsWritten(BROWSER_PREFS, BROWSER_KEY) { world.auth.logout() }

        assertFalse(world.demoSignIn())
        world.wipeDone()

        assertFalse("every request would be refused, with nobody signed in", world.fixtures.demoMode)
        assertFalse(world.sessions.school.isDemo)
        assertFalse(world.auth.authState.value)
        assertEquals(null, world.credentials.ntustStudentId)
        assertEquals(null, world.fixtures.studentIdOverride)
        assertFalse(world.fixtures.libraryFakeSignedIn)
        assertEquals(emptyList<Any>(), world.dataCache.loadCourses())
        assertNotNull(hold)
    }

    @Test
    fun `a sign-out landing as the demo goes on leaves nothing of it`() = runBlocking {
        val world = world()
        // From a thread of its own, as the sign-in is in the step that holds a sign-out off: given
        // a moment rather than waited for, so that with no such step it runs all the way here.
        var signOut: Thread? = null
        val hold = onceAsWritten(FIXTURE_PREFS, DEMO_MODE_KEY) {
            signOut = thread { world.auth.logout() }.also { it.join(MOMENT_MS) }
        }

        world.demoSignIn()
        signOut!!.join(WAIT_MS)
        world.wipeDone()

        assertFalse(world.fixtures.demoMode)
        assertFalse(world.sessions.school.isDemo)
        assertFalse(world.auth.authState.value)
        assertEquals(null, world.credentials.ntustStudentId)
        assertEquals("the demo student", null, world.fixtures.studentIdOverride)
        assertEquals(emptyList<Any>(), world.dataCache.loadCourses())
        assertNotNull(hold)
    }

    @OptIn(StartsAccountWork::class)
    @Test
    fun `a classmate imported past the demo's sign-out stays out of the next student's tables`() = runBlocking {
        val world = world()
        assertTrue(world.demoSignIn())
        val signIn = world.sessions.school.current
        world.auth.logout()
        world.wipeDone()

        world.demoAccount.importClassmates(signIn)

        assertEquals(emptyList<Any>(), world.imported.tables.value.people)
    }

    private companion object {
        const val STUDENT = "B00015999"
        const val WAIT_MS = 10_000L
        const val MOMENT_MS = 300L
        const val FIXTURE_PREFS = "debug_fixture"
        const val DEMO_MODE_KEY = "demo_mode"
        const val BROWSER_PREFS = "tigerduck_browser_cleanup"
        const val BROWSER_KEY = "request"
    }
}
