package org.ntust.app.tigerduck.auth

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.session.SignOutOwed
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.UnsentMark
import org.ntust.app.tigerduck.library.LibraryQRCache
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.testSignIn
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext

/**
 * One student has used the app, keeping what docs/account-state.md gives the school account; the
 * student signs out, and another signs in: nothing of the first is left, and the first one's
 * School Mail and library account stay, as they sign out by themselves. Runs the real
 * [AuthService.logout], [AuthService.login] against hand-made school pages, and
 * [AccountSessionEffects], which follows both, over the real stores.
 *
 * Every key the inventory gives the school account is filled, typed as its source reads it, and
 * every DataCache file it lists, in the folder DataCache keeps it in. Nothing reaches a network:
 * the school is a fake, every other request is refused, and the registration and the settings
 * pushes run on a scope that never runs them. Play only: the widgets' analytics logger differs by
 * flavor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccountSwitchTest {

    private val context = RuntimeEnvironment.getApplication()
    private val account = SchoolAccountFill(context, FIRST, SEMESTER)

    /** The sign-out's own clearing, waited for; work that would reach a network goes nowhere. */
    private val signOutJob = SupervisorJob()
    private val signOutScope = CoroutineScope(signOutJob + Dispatchers.IO)
    private val effectsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() {
        effectsScope.cancel()
        signOutScope.cancel()
    }

    private fun prefs(file: String): SharedPreferences = context.getSharedPreferences(file, Context.MODE_PRIVATE)

    /**
     * The real services over the real stores, as the test below has always built them; the
     * sign-out's own clearing runs on [appScope].
     */
    private fun world(credentialPrefs: SharedPreferences, appScope: CoroutineScope = signOutScope): AccountWorld =
        accountWorld(context, credentialPrefs, appScope, idleScope).also { it.effects.start(effectsScope) }

    @Test
    fun `signing in as a second student after a first leaves nothing of the first`() = runBlocking {
        // The first student signed in, with a School Mail and a library account of their own.
        account.fillPreferences()
        val credentialPrefs = prefs("tigerduck_credentials")
        credentialPrefs.edit()
            .putString("ntust_student_id", FIRST)
            .putString("ntust_password", "first-password")
            .putString("mail_student_id", FIRST)
            .putString("mail_password", "mail-password")
            .putString("library_username", FIRST)
            .putString("library_password", "library-password")
            .commit()
        val othersBefore = credentialPrefs.all.filterKeys { it.startsWith("mail_") || it.startsWith("library_") }
        val firstLeft = account.held()
        // The keys of each file the inventory gives the school account, the credentials among them.
        assertTrue(
            firstLeft.keys.map { it.first }.toSet()
                .containsAll(setOf("tigerduck_prefs", "tigerduck_credentials", "tigerduck_auth", "tigerduck_browser_settings")),
        )
        assertTrue(firstLeft.values.none { it == null })
        assertTrue(account.fillDataCache().all(File::exists))

        val w = world(credentialPrefs)
        val credentials = w.credentials
        val dataCache = w.dataCache
        val bulletinCache = w.bulletinCache
        val imported = w.imported
        val auth = w.auth
        val browserClearings = w.browserClearings

        // What the first student's sessions kept besides: DataCache through its own saves, a
        // bulletin snapshot and an imported class table.
        dataCache.saveCourses(listOf(Course(courseNo = "CS1001", courseName = "Calculus")), SEMESTER, testSignIn())
        dataCache.saveAssignments(listOf(Assignment("1", "CS1001", "Calculus", "Homework 1", Date())), testSignIn())
        dataCache.saveMoodleData(listOf("forum"), System.currentTimeMillis(), "forums-7", FIRST, testSignIn())
        bulletinCache.save(emptyList())
        bulletinCache.saveFetchedAt(System.currentTimeMillis())
        val classmate = ClassTableShareCode(
            ClassTableShareCode.Format.TIGER_DUCK, CLASSMATE, null, listOf(ClassTableShareCode.Term(SEMESTER, listOf("CS1001"))),
        )
        assertNotNull(imported.add(classmate, "Ming", emptyMap()))
        val bulletins = File(context.filesDir, "bulletins")
        // Writes a process death cut short.
        val importedUnfinished = File(ImportedClassTableStore.fileIn(context).path + ".tmp").apply { writeText("{}") }
        File(bulletins, "summaries.json.tmp").writeText("[]")
        File(bulletins, "fetched_at.tmp").writeText("1")
        assertTrue(account.filesUnder(bulletins).isNotEmpty())

        /** Nothing of the first student's is left, and their School Mail and library account stay. */
        suspend fun assertNothingOfTheFirstLeft(after: String) {
            // Gone, or back to a value of no one's: none still holds what the first student left.
            val left = firstLeft.filter { (at, value) -> prefs(at.first).all[at.second] == value }.keys
            assertEquals(after, emptySet<Pair<String, String>>(), left)
            assertEquals(after, emptyList<String>(), listOf("cache", "data").flatMap { account.filesUnder(account.cacheRoots.getValue(it)) })
            assertEquals(after, emptyList<String>(), account.filesUnder(dataCache.moodleDownloadsRoot()))
            assertEquals(after, emptyList<String>(), account.filesUnder(bulletins))
            assertEquals(after, emptyList<String>(), imported.load().people.map { it.nickname })
            assertFalse(after, importedUnfinished.exists())
            assertEquals(after, othersBefore, credentialPrefs.all.filterKeys { it.startsWith("mail_") || it.startsWith("library_") })
        }

        auth.logout()
        withTimeout(10_000) {
            signOutJob.children.toList().joinAll()
            browserClearings.first { it >= 1 }
        }
        // By the sign-out itself: the next sign-in clears some of the same again.
        assertNothingOfTheFirstLeft(after = "the sign-out")
        assertEquals(null, auth.storedStudentId)

        assertTrue(auth.login(SECOND, "second-password"))
        withTimeout(10_000) {
            // The browser's clearing that follows the sign-in.
            browserClearings.first { it >= 2 }
        }

        assertTrue(auth.authState.value)
        assertEquals(SECOND, auth.storedStudentId)
        assertEquals("second-password", credentials.ntustPassword)
        assertNothingOfTheFirstLeft(after = "the second sign-in")
    }

    // ---- what a sign-out does today, pinned for the refactor (phase 2, T2.2) ----

    /** A scope whose launched work waits until [release], so a test can stand between a sign-out and its wipe. */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val held = mutableListOf<Pair<CoroutineContext, Runnable>>()
        private var open = false

        @Synchronized
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (open) Dispatchers.IO.dispatch(context, block) else held += context to block
        }

        @Synchronized
        fun release() {
            open = true
            held.forEach { (context, block) -> Dispatchers.IO.dispatch(context, block) }
            held.clear()
        }
    }

    private fun signedInAsFirst(): SharedPreferences {
        val credentialPrefs = prefs("tigerduck_credentials")
        credentialPrefs.edit().clear()
            .putString("ntust_student_id", FIRST)
            .putString("ntust_password", "first-password")
            .commit()
        return credentialPrefs
    }

    private fun userFile(name: String) = File(context.filesDir, "TigerDuckData/$name")

    private val mark = UnsentMark("7", "ignored", madeAtMs = 1L)

    @Test
    fun `a write pinned before the sign-out leaves nothing after the wipe, whether it lands before or after`() = runBlocking {
        val w = world(signedInAsFirst())
        val signIn = w.sessions.school.signIns.current

        // Landing before the wipe: it is on disk, and the wipe takes it.
        assertTrue(w.dataCache.saveUnsentMarks(listOf(mark), signIn))
        assertTrue(userFile("unsent_marks.json").exists())
        w.auth.logout()
        withTimeout(10_000) { signOutJob.children.toList().joinAll() }
        assertFalse(userFile("unsent_marks.json").exists())

        // Landing after it: the sign-in has ended, so the write is dropped where it lands.
        assertFalse(w.dataCache.saveUnsentMarks(listOf(mark), signIn))
        assertFalse(w.dataCache.savePendingCourseAdds(mapOf(SEMESTER to listOf("CS1001")), signIn))
        assertFalse(w.dataCache.saveAssignments(listOf(Assignment("1", "CS1001", "Calculus", "Homework 1", Date())), signIn))
        assertEquals(emptyList<UnsentMark>(), w.dataCache.loadUnsentMarks())
        assertEquals(emptyList<String>(), account.filesUnder(account.cacheRoots.getValue("data")))
        assertEquals(emptyList<Assignment>(), w.dataCache.loadAssignments())
    }

    @Test
    fun `work that starts between the sign-in's end and the wipe, and writes after it, leaves nothing`() = runBlocking {
        val held = HeldDispatcher()
        val w = world(signedInAsFirst(), CoroutineScope(SupervisorJob() + held))

        w.auth.logout()
        // The sign-in has ended and the wipe has not run: the session is closed, so work that
        // starts in the gap reads the sign-in that ended, and writes nothing.
        assertEquals(null, w.auth.storedStudentId)
        val startedInTheGap = w.sessions.school.signIns.current
        assertFalse(startedInTheGap.isCurrent)
        assertFalse(w.dataCache.saveUnsentMarks(listOf(mark), startedInTheGap))
        held.release()
        withTimeout(10_000) { w.sessions.school.awaitOpen() }

        // The work from the gap writes after the wipe: dropped.
        assertFalse(w.dataCache.saveUnsentMarks(listOf(mark), startedInTheGap))
        assertFalse(userFile("unsent_marks.json").exists())
    }

    @Test
    fun `another student's typed sign-in leaves nothing of the first`() = runBlocking {
        val credentialPrefs = signedInAsFirst()
        account.fillPreferences()
        credentialPrefs.edit().putString("ntust_student_id", FIRST).putString("ntust_password", "first-password").commit()
        val firstLeft = account.held()
        assertTrue(firstLeft.values.none { it == null })
        val files = account.fillDataCache()
        val w = world(credentialPrefs)
        val signInBefore = w.sessions.school.signIns.current
        val signedInEmissions = CopyOnWriteArrayList<Boolean>()
        val watching = effectsScope.launch { w.auth.accountState.collect { signedInEmissions += it.signedIn } }
        val signIns = effectsScope.async { w.auth.signIns.first() }
        delay(100)

        assertTrue(w.auth.login(SECOND, "second-password"))
        withTimeout(10_000) {
            signIns.await()
            w.browserClearings.first { it >= 1 }
        }
        watching.cancel()

        assertEquals(SECOND, w.auth.storedStudentId)
        assertEquals("second-password", w.credentials.ntustPassword)
        assertTrue("the first student's sign-in ended", !signInBefore.isCurrent)
        assertTrue("the second's is open", w.sessions.school.signIns.current.isCurrent)
        assertEquals("the account went off, and came back as the second student", listOf(true, false, true), signedInEmissions.toList())
        assertEquals(emptyList<File>(), files.filter(File::exists))
        assertEquals(emptySet<Pair<String, String>>(), firstLeft.filter { (at, value) -> prefs(at.first).all[at.second] == value }.keys)
    }

    @Test
    fun `the same student typing their password again signs nobody out`() = runBlocking {
        val credentialPrefs = signedInAsFirst()
        val files = account.fillDataCache()
        val w = world(credentialPrefs)
        val signInBefore = w.sessions.school.signIns.current

        assertTrue(w.auth.login(FIRST, "first-password-again"))

        assertEquals(FIRST, w.auth.storedStudentId)
        assertSame(signInBefore, w.sessions.school.signIns.current)
        assertEquals(files, files.filter(File::exists))
    }

    @Test
    fun `a wipe a process death cut short runs at the next start`() = runBlocking {
        // The first process: the student signs out, and the process dies before the wipe runs.
        val held = HeldDispatcher()
        val credentialPrefs = signedInAsFirst()
        val first = world(credentialPrefs, CoroutineScope(SupervisorJob() + held))
        val queued = listOf("unsent_marks.json", "pending_course_adds.json")
        val signIn = first.sessions.school.signIns.current
        assertTrue(first.dataCache.saveUnsentMarks(listOf(mark), signIn))
        assertTrue(first.dataCache.savePendingCourseAdds(mapOf(SEMESTER to listOf("CS1001")), signIn))
        first.dataCache.saveAssignments(listOf(Assignment("1", "CS1001", "Calculus", "Homework 1", Date())), signIn)

        first.auth.logout()
        assertEquals(null, first.auth.storedStudentId)
        assertTrue("the wipe has not run", queued.all { userFile(it).exists() })
        // The next process starts: new objects over the same files, as after a process death.
        val second = world(credentialPrefs)
        assertTrue("a sign-out was cut short", second.auth.finishOwedSignOut())
        assertTrue("the queued edits are still there to be sent as the next student's", queued.all { userFile(it).exists() })
        assertFalse("the session starts closed while the wipe is owed", second.sessions.school.signIns.isOpen.value)

        second.auth.finishOwedWipe()
        withTimeout(10_000) { second.sessions.school.awaitOpen() }

        assertEquals(emptyList<String>(), queued.filter { userFile(it).exists() })
        assertEquals(emptyList<Assignment>(), second.dataCache.loadAssignments())
        assertEquals(emptyList<UnsentMark>(), second.dataCache.loadUnsentMarks())
        assertTrue(second.sessions.school.signIns.isOpen.value)
        // The marker went with the wipe, so a start after this one finds nothing owed.
        assertFalse(SignOutOwed(prefs("tigerduck_account_wipe")).owed)
        assertTrue(second.auth.login(SECOND, "second-password"))
        assertEquals(SECOND, second.auth.storedStudentId)
    }

    @Test
    fun `a start that finds the sign-out promised signs the account out before the wipe`() = runBlocking {
        // The process died after the marker was written and before the credentials went.
        val credentialPrefs = signedInAsFirst()
        credentialPrefs.edit().putString("moodle_token", "made-up-token").commit()
        SignOutOwed(prefs("tigerduck_account_wipe")).mark(FIRST)
        val w = world(credentialPrefs)
        w.tokens.let { w.credentials.v3Tokens.save("access-a", "refresh-a", Long.MAX_VALUE) }
        assertTrue(w.auth.authState.value)
        assertTrue(userFile("unsent_marks.json").also { it.parentFile?.mkdirs() }.apply { writeText("[]") }.exists())

        assertTrue(w.auth.finishOwedSignOut())

        // Synchronously, before the wipe: credentials, tokens and the signed-in state.
        assertEquals(null, w.credentials.ntustStudentId)
        assertEquals(null, w.credentials.v3Tokens.accessToken)
        assertFalse(w.auth.authState.value)
        assertFalse(w.sessions.school.signIns.isOpen.value)

        w.auth.finishOwedWipe()
        withTimeout(10_000) { w.sessions.school.awaitOpen() }
        assertFalse(userFile("unsent_marks.json").exists())
        assertFalse(SignOutOwed(prefs("tigerduck_account_wipe")).owed)
    }

    @Test
    fun `a stale marker wipes nothing of the student stored now, and opens the session`() = runBlocking {
        val credentialPrefs = prefs("tigerduck_credentials")
        credentialPrefs.edit().clear().putString("ntust_student_id", SECOND).putString("ntust_password", "second-password").commit()
        SignOutOwed(prefs("tigerduck_account_wipe")).mark(FIRST)
        val w = world(credentialPrefs)
        userFile("unsent_marks.json").also { it.parentFile?.mkdirs() }.writeText("[]")
        w.credentials.v3Tokens.save("second's-token", "second's-refresh", Long.MAX_VALUE)
        w.appPrefs.holidayOverridesAwaitingUpload = setOf(7)

        assertFalse("nothing to finish", w.auth.finishOwedSignOut())
        delay(300)

        assertEquals(SECOND, w.credentials.ntustStudentId)
        assertEquals("second's-token", w.credentials.v3Tokens.accessToken)
        assertEquals(setOf(7), w.appPrefs.holidayOverridesAwaitingUpload)
        assertTrue(w.auth.authState.value)
        assertTrue("the second student's file stays", userFile("unsent_marks.json").exists())
        assertTrue(w.sessions.school.signIns.isOpen.value)
        assertFalse(SignOutOwed(prefs("tigerduck_account_wipe")).owed)
    }

    @Test
    fun `a start with nothing owed leaves the files and the session alone`() = runBlocking {
        val w = world(signedInAsFirst())
        assertTrue(w.dataCache.saveUnsentMarks(listOf(mark), w.sessions.school.signIns.current))
        w.credentials.v3Tokens.save("first's-token", "first's-refresh", Long.MAX_VALUE)
        w.appPrefs.holidayOverridesAwaitingUpload = setOf(7)
        val current = w.sessions.school.signIns.current

        assertFalse("nothing to finish", w.auth.finishOwedSignOut())
        delay(200)

        assertTrue(userFile("unsent_marks.json").exists())
        assertEquals(FIRST, w.credentials.ntustStudentId)
        assertEquals("first's-token", w.credentials.v3Tokens.accessToken)
        assertEquals(setOf(7), w.appPrefs.holidayOverridesAwaitingUpload)
        assertTrue(w.auth.authState.value)
        assertTrue(w.sessions.school.signIns.isOpen.value)
        assertTrue("the same sign-in goes on", current === w.sessions.school.signIns.current && current.isCurrent)
        assertFalse(SignOutOwed(prefs("tigerduck_account_wipe")).owed)
    }

    @Test
    fun `the school sign-out leaves the school mail and the library account, stores and credentials`() = runBlocking {
        val credentialPrefs = signedInAsFirst()
        credentialPrefs.edit()
            .putString("mail_student_id", FIRST).putString("mail_password", "mail-password")
            .putString("library_username", FIRST).putString("library_password", "library-password")
            .putString("library_token", "library-token")
            .commit()
        val mailCache = MailCache(File(context.filesDir, "mail"))
        mailCache.attachmentsDir.mkdirs()
        val mailFile = File(mailCache.attachmentsDir, "note.txt").apply { writeText("kept") }
        val qr = LibraryQRCache<String>().apply { store("payload", null, account = 1, issuedAt = 0L) }
        val others = credentialPrefs.all.filterKeys { it.startsWith("mail_") || it.startsWith("library_") }
        val w = world(credentialPrefs)

        w.auth.logout()
        withTimeout(10_000) { signOutJob.children.toList().joinAll() }

        assertEquals(null, w.credentials.ntustStudentId)
        assertEquals(others, credentialPrefs.all.filterKeys { it.startsWith("mail_") || it.startsWith("library_") })
        assertTrue(mailFile.exists())
        assertNotNull(qr.reusable(account = 1, now = 1L))
    }

    private companion object {
        const val FIRST = "B00015998"
        const val SECOND = "B00015999"
        const val CLASSMATE = "B00015997"
        const val SEMESTER = "1141"
    }
}
