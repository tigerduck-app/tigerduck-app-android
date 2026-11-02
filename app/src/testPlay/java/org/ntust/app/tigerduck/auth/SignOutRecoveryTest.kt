package org.ntust.app.tigerduck.auth

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.session.SignOutOwed
import org.ntust.app.tigerduck.data.model.UnsentMark
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * A process that dies in a sign-out, and the next start that finishes it, over the real services
 * and stores. Nothing here is told what a sign-out clears: what is on disk when the process dies
 * is set by counting the writes, and the proof is the student's data, as AccountSwitchTest fills
 * it, gone afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SignOutRecoveryTest {

    private val context = RuntimeEnvironment.getApplication()
    private val account = SchoolAccountFill(context, FIRST, SEMESTER)

    private val appJob = SupervisorJob()
    private val appScope = CoroutineScope(appJob + Dispatchers.IO)
    private val effectsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() {
        effectsScope.cancel()
        appScope.cancel()
    }

    private fun prefs(file: String): SharedPreferences = context.getSharedPreferences(file, Context.MODE_PRIVATE)

    private val credentialPrefs get() = prefs("tigerduck_credentials")

    private val owed get() = SignOutOwed(prefs("tigerduck_account_wipe"))

    /** What a [Disk] past its last write throws: the process is gone. */
    private class ProcessDied : Error("the process died here")

    /** Takes [allowed] writes and no more, naming each it took, as a process that dies after them. */
    private class Disk {
        var allowed = Int.MAX_VALUE
        val writes = mutableListOf<String>()
        var counting = false
        var onWrite: (String) -> Unit = {}

        fun write(what: String) {
            if (!counting) return
            if (writes.size >= allowed) throw ProcessDied()
            onWrite(what)
            writes += what
        }
    }

    private class CountedPrefs(
        private val real: SharedPreferences,
        private val file: String,
        private val disk: Disk,
    ) : SharedPreferences by real {
        override fun edit(): SharedPreferences.Editor = Editor(real.edit())

        private inner class Editor(private val edit: SharedPreferences.Editor) : SharedPreferences.Editor by edit {
            private val keys = mutableListOf<String>()

            private fun on(key: String?, change: () -> Unit) = apply {
                keys += key.orEmpty()
                change()
            }

            override fun putString(key: String?, value: String?) = on(key) { edit.putString(key, value) }
            override fun putStringSet(key: String?, value: MutableSet<String>?) = on(key) { edit.putStringSet(key, value) }
            override fun putInt(key: String?, value: Int) = on(key) { edit.putInt(key, value) }
            override fun putLong(key: String?, value: Long) = on(key) { edit.putLong(key, value) }
            override fun putFloat(key: String?, value: Float) = on(key) { edit.putFloat(key, value) }
            override fun putBoolean(key: String?, value: Boolean) = on(key) { edit.putBoolean(key, value) }
            override fun remove(key: String?) = on(key) { edit.remove(key) }
            override fun clear() = on("everything") { edit.clear() }

            override fun commit(): Boolean {
                disk.write("$file: $keys")
                return edit.commit()
            }

            override fun apply() {
                disk.write("$file: $keys")
                edit.apply()
            }
        }
    }

    /** The app's context, with every preferences file writing through [disk]. */
    private inner class OnDisk(private val disk: Disk) : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            touchedFiles += name
            return CountedPrefs(super.getSharedPreferences(name, mode), name, disk)
        }
    }

    private val touchedFiles = linkedSetOf("tigerduck_credentials")

    /** The student signed in, with everything the inventory gives the account, and nothing else. */
    private fun firstStudentOnTheDevice() {
        touchedFiles.forEach { prefs(it).edit().clear().commit() }
        listOf(context.filesDir, context.cacheDir, context.noBackupFilesDir).forEach { root ->
            root.listFiles()?.forEach(File::deleteRecursively)
        }
        account.fillPreferences()
        credentialPrefs.edit()
            .putString("ntust_student_id", FIRST)
            .putString("ntust_password", "first-password")
            .commit()
        account.fillDataCache()
    }

    /**
     * The process that signs out, cut short after [writes] writes. Its wipe never runs, whatever
     * the sign-out reached: a scope already cancelled, as the process's would be. Says what it wrote.
     */
    private fun signOutCutShortAfter(writes: Int): List<String> {
        val disk = Disk()
        val onDisk = OnDisk(disk)
        val w = accountWorld(onDisk, onDisk.getSharedPreferences("tigerduck_credentials", Context.MODE_PRIVATE), idleScope, idleScope)
        disk.allowed = writes
        disk.counting = true
        try {
            w.auth.logout()
        } catch (_: ProcessDied) {
            // As the table wants: what reached the disk is all that is left of it.
        }
        return disk.writes
    }

    /** The next process's start, in TigerDuckApp's order. */
    private fun nextStart(): Pair<AccountWorld, Boolean> {
        // As on the launch of the update that brought the records, when its step runs in the
        // constructor, for an account that is on its way out.
        prefs("tigerduck_prefs").edit().putBoolean("lastSyncedSettingsSeedDone", false).commit()
        val w = accountWorld(context, credentialPrefs, appScope, idleScope)
        val signOutCutShort = w.auth.finishOwedSignOut()
        if (signOutCutShort) w.auth.finishOwedWipe()
        w.effects.start(effectsScope, signOutCutShort)
        return w to signOutCutShort
    }

    private fun assertNothingOfTheFirstLeft(w: AccountWorld, firstLeft: Map<Pair<String, String>, Any?>, row: String) = runBlocking {
        withTimeout(10_000) {
            w.sessions.school.awaitOpen()
            appJob.children.toList().joinAll()
            w.browserClearings.first { it >= 1 }
        }
        assertEquals(row, emptySet<Pair<String, String>>(), firstLeft.filter { (at, value) -> prefs(at.first).all[at.second] == value }.keys)
        assertEquals(row, emptyList<String>(), listOf("cache", "data").flatMap { account.filesUnder(account.cacheRoots.getValue(it)) })
        assertEquals(row, emptyList<String>(), account.filesUnder(w.dataCache.moodleDownloadsRoot()))
        assertNull(row, w.credentials.ntustStudentId)
        assertNull(row, w.credentials.v3Tokens.accessToken)
        assertFalse(row, w.tokens.isLoggedIn)
        assertFalse(row, w.auth.authState.value)
        // What the constructor's step may have seeded for the student who left, and every other
        // record of what was last synced.
        assertNull(row, w.appPrefs.lastSyncedLiveActivitySettings)
        assertNull(row, w.appPrefs.lastSyncedAssignmentReminderSettings)
        assertNull(row, w.appPrefs.lastSyncedClassReminderSettings)
        assertTrue(row, w.appPrefs.holidayOverridesAwaitingUpload.isEmpty())
        assertTrue(row, w.appPrefs.holidayNotifyOverrides.isEmpty())
        assertFalse(row, owed.owed)
    }

    @Test
    fun `a sign-out cut short at any write is finished by the next start`() {
        firstStudentOnTheDevice()
        val everyWrite = signOutCutShortAfter(Int.MAX_VALUE)
        assertTrue("the marker first: $everyWrite", everyWrite.first().startsWith("tigerduck_account_wipe"))

        for (written in 0..everyWrite.size) {
            firstStudentOnTheDevice()
            val firstLeft = account.held()
            val row = "after ${everyWrite.take(written).lastOrNull() ?: "nothing"} ($written of ${everyWrite.size})"

            signOutCutShortAfter(written)
            val (w, signOutCutShort) = nextStart()

            if (written == 0) {
                // No marker: the sign-out never began, and the student is still signed in.
                assertFalse(row, signOutCutShort)
                assertEquals(row, FIRST, w.credentials.ntustStudentId)
                assertTrue(row, w.auth.authState.value)
                assertTrue(row, w.sessions.school.isOpen)
            } else {
                assertTrue(row, signOutCutShort)
                assertNothingOfTheFirstLeft(w, firstLeft, row)
            }
        }
    }

    @Test
    fun `what the update's step seeds for the student on their way out goes with them`() {
        firstStudentOnTheDevice()
        // A pending edit and no record: the shape in which the step records the {} that sends every field.
        val prefsFile = prefs("tigerduck_prefs")
        prefsFile.edit()
            .remove("lastSyncedLiveActivitySettings")
            .remove("lastSyncedAssignmentReminderSettings")
            .remove("lastSyncedClassReminderSettings")
            .putBoolean("lastSyncedSettingsSeedDone", false)
            .commit()
        owed.mark(FIRST)

        val w = accountWorld(context, credentialPrefs, appScope, idleScope)

        assertEquals("the step ran as Hilt built it", "{}", w.appPrefs.lastSyncedAssignmentReminderSettings)
        assertTrue(w.auth.finishOwedSignOut())
        assertNull(w.appPrefs.lastSyncedLiveActivitySettings)
        assertNull(w.appPrefs.lastSyncedAssignmentReminderSettings)
        assertNull(w.appPrefs.lastSyncedClassReminderSettings)
        assertFalse(w.appPrefs.hasUnconfirmedAssignmentSyncEdit)
        assertFalse(w.appPrefs.hasUnconfirmedClassReminderSyncEdit)
    }

    @Test
    fun `the JWT pair goes even with the student ID already gone`() {
        firstStudentOnTheDevice()
        credentialPrefs.edit().remove("ntust_student_id").remove("ntust_password").commit()
        owed.mark(FIRST)

        val w = accountWorld(context, credentialPrefs, appScope, idleScope)
        w.credentials.v3Tokens.save("access-a", "refresh-a", Long.MAX_VALUE)
        assertTrue(w.tokens.isLoggedIn)

        assertTrue(w.auth.finishOwedSignOut())

        assertFalse(w.tokens.isLoggedIn)
        assertNull(w.credentials.v3Tokens.accessToken)
    }

    @Test
    fun `a demo account's sign-out cut short leaves the demo too`() {
        credentialPrefs.edit().putString("ntust_student_id", FIRST).commit()
        val fixtures = DebugFixtureStore(context)
        fixtures.demoMode = true
        owed.mark(FIRST)

        val w = accountWorld(context, credentialPrefs, appScope, idleScope)
        assertTrue(w.auth.authState.value)

        assertTrue(w.auth.finishOwedSignOut())

        assertFalse(fixtures.demoMode)
        assertFalse(w.auth.authState.value)
    }

    @Test
    fun `a sign-in reply landing after a sign-out began stores nothing, and the next start finishes the sign-out`() = runBlocking {
        firstStudentOnTheDevice()
        val firstLeft = account.held()
        val posted = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val w = accountWorld(context, credentialPrefs, idleScope, idleScope) {
            posted.countDown()
            answer.await(10, TimeUnit.SECONDS)
        }
        val signIn = appScope.async { w.auth.login(SECOND, "second-password") }
        assertTrue(posted.await(10, TimeUnit.SECONDS))

        // What a sign-out does first, and all this one reached before the process died.
        w.sessions.school.markWipeOwed(FIRST)
        w.sessions.school.signIns.end()
        answer.countDown()

        assertFalse(withTimeout(10_000) { signIn.await() })
        assertEquals("the marker's student is still the one stored", FIRST, w.credentials.ntustStudentId)
        val (next, signOutCutShort) = nextStart()
        assertTrue(signOutCutShort)
        assertNothingOfTheFirstLeft(next, firstLeft, "the sign-in that lost")
    }

    @Test
    fun `a sign-out landing as a sign-in stores waits for it, and names that student on the marker`() = runBlocking {
        // Nobody stored: over another student, the sign-in signs that one out before it stores.
        val storing = CountDownLatch(1)
        val store = CountDownLatch(1)
        val disk = Disk().apply {
            counting = true
            // A sign-in's first write, made with the lock it stores under held.
            onWrite = { what ->
                if (what.startsWith("tigerduck_browser_cleanup")) {
                    storing.countDown()
                    store.await(10, TimeUnit.SECONDS)
                }
            }
        }
        val onDisk = OnDisk(disk)
        val w = accountWorld(onDisk, onDisk.getSharedPreferences("tigerduck_credentials", Context.MODE_PRIVATE), idleScope, idleScope)
        val signIn = appScope.async { w.auth.login(SECOND, "second-password") }
        assertTrue(storing.await(10, TimeUnit.SECONDS))

        val signOut = appScope.async { w.auth.logout() }
        try {
            delay(200)
            assertFalse("the sign-out waits for the store it would otherwise misname", owed.owed)
        } finally {
            // In a finally: the stalled write holds a lock every test of this class goes through.
            store.countDown()
        }

        withTimeout(10_000) { signOut.await() }
        withTimeout(10_000) { signIn.await() }
        assertTrue(owed.replays(SECOND))
        assertFalse(owed.replays(FIRST))
    }

    /** The app's context with one key's writes refused, as a store that went bad would. */
    private fun refusing(key: String) = OnDisk(
        Disk().apply {
            counting = true
            onWrite = { what -> if (key in what) throw IllegalStateException("made-up failure") }
        },
    )

    private fun worldOver(context: Context) =
        accountWorld(context, context.getSharedPreferences("tigerduck_credentials", Context.MODE_PRIVATE), appScope, idleScope)

    private suspend fun wipesDone() = withTimeout(10_000) { appJob.children.toList().joinAll() }

    @Test
    fun `a browser step that throws takes nothing after it down, and holds nothing back`() = runBlocking {
        firstStudentOnTheDevice()
        // What the review found: the request is read as a number, and this is not one.
        prefs("tigerduck_browser_cleanup").edit().putString("request", "not a number").commit()
        owed.mark(FIRST)
        val w = worldOver(context)

        assertTrue(w.auth.finishOwedSignOut())
        w.auth.finishOwedWipe()
        withTimeout(10_000) { w.sessions.school.awaitOpen() }

        assertNull(w.credentials.ntustStudentId)
        assertNull(w.credentials.v3Tokens.accessToken)
        assertFalse(w.auth.authState.value)
        assertFalse(owed.owed)
    }

    @Test
    fun `a credentials step that fails skips nothing after it, and the next start tries again`() = runBlocking {
        firstStudentOnTheDevice()
        owed.mark(FIRST)
        val w = worldOver(refusing("ntust_student_id"))

        assertTrue(w.auth.finishOwedSignOut())
        w.auth.finishOwedWipe()
        wipesDone()

        assertEquals("the step that failed", FIRST, w.credentials.ntustStudentId)
        assertNull("the next one went all the same", w.credentials.v3Tokens.accessToken)
        assertTrue(w.appPrefs.holidayOverridesAwaitingUpload.isEmpty())
        assertFalse(w.auth.authState.value)
        assertEquals("and the wipe", emptyList<String>(), listOf("cache", "data").flatMap { account.filesUnder(account.cacheRoots.getValue(it)) })
        assertTrue("the marker stays for the next start", owed.owed)
        assertFalse("and the next sign-in waits for it", w.sessions.school.isOpen)

        val (next, signOutCutShort) = nextStart()

        assertTrue(signOutCutShort)
        withTimeout(10_000) { next.sessions.school.awaitOpen() }
        assertNull(next.credentials.ntustStudentId)
        assertFalse(next.auth.authState.value)
        assertFalse(owed.owed)
    }

    @Test
    fun `a clearing of the preferences that fails skips none after it, and the next start tries again`() = runBlocking {
        firstStudentOnTheDevice()
        owed.mark(FIRST)
        val w = worldOver(refusing("syncedCategories"))

        assertTrue(w.auth.finishOwedSignOut())
        w.auth.finishOwedWipe()
        wipesDone()

        assertEquals("the clearing that failed", setOf("1"), prefs("tigerduck_prefs").getStringSet("syncedCategories", null))
        assertTrue("the holiday choices went all the same", w.appPrefs.holidayOverridesAwaitingUpload.isEmpty())
        assertNull("and the term picked", w.appPrefs.school.classTableSelectedSemester.get())
        assertFalse("and the stored password's refusal", prefs("tigerduck_auth").contains("stored_password_refusals"))
        assertFalse(w.auth.authState.value)
        assertTrue("the marker stays for the next start", owed.owed)
    }

    @Test
    fun `a step that keeps failing holds the next sign-in for three tries and no more`() = runBlocking {
        firstStudentOnTheDevice()
        owed.mark(FIRST)

        repeat(3) { start ->
            val w = worldOver(refusing("ntust_student_id"))
            assertTrue(w.auth.finishOwedSignOut())
            w.auth.finishOwedWipe()
            wipesDone()

            val last = start == 2
            assertEquals("try ${start + 1}", last, w.sessions.school.isOpen)
            assertEquals("try ${start + 1}", !last, owed.owed)
        }
    }

    @Test
    fun `a sign-out with a step that fails is not over until a later start finishes it`() = runBlocking {
        firstStudentOnTheDevice()
        val w = worldOver(refusing("ntust_student_id"))

        w.auth.logout()
        wipesDone()

        assertNull(w.credentials.v3Tokens.accessToken)
        assertTrue(owed.owed)
        assertFalse(w.sessions.school.isOpen)

        val (next, signOutCutShort) = nextStart()

        assertTrue(signOutCutShort)
        withTimeout(10_000) { next.sessions.school.awaitOpen() }
        assertNull(next.credentials.ntustStudentId)
        assertFalse(owed.owed)
    }

    /** A scope whose work waits until [release], so a test can stand before a wipe. */
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

    private val mark = UnsentMark("7", "ignored", madeAtMs = 1L)

    private fun userFile(name: String) = File(context.filesDir, "TigerDuckData/$name")

    @Test
    fun `a sign-out during a recovery holds the next sign-in until both have wiped`() = runBlocking {
        credentialPrefs.edit().putString("ntust_student_id", FIRST).putString("ntust_password", "first-password").commit()
        owed.mark(FIRST)
        val held = HeldDispatcher()
        val w = accountWorld(context, credentialPrefs, CoroutineScope(SupervisorJob() + held), idleScope)
        assertTrue(w.auth.finishOwedSignOut())
        w.auth.finishOwedWipe()

        // The user signs out again, or a full reset does, before the recovery's wipe has run.
        w.auth.logout()
        val nextSignIn = appScope.async { w.auth.login(SECOND, "second-password") }
        delay(200)
        assertNull("nothing stored for a wipe to take", w.credentials.ntustStudentId)
        assertFalse(nextSignIn.isCompleted)

        held.release()

        assertTrue(withTimeout(10_000) { nextSignIn.await() })
        assertEquals(SECOND, w.credentials.ntustStudentId)
        assertTrue("what the next student saves stays", w.dataCache.saveUnsentMarks(listOf(mark), w.sessions.school.signIns.current))
        delay(100)
        assertTrue(userFile("unsent_marks.json").exists())
        assertFalse(owed.owed)
    }

    @Test
    fun `two sign-outs in a row hold the next sign-in until both have wiped`() = runBlocking {
        credentialPrefs.edit().putString("ntust_student_id", FIRST).putString("ntust_password", "first-password").commit()
        val held = HeldDispatcher()
        val w = accountWorld(context, credentialPrefs, CoroutineScope(SupervisorJob() + held), idleScope)
        w.effects.start(effectsScope)

        w.auth.logout()
        w.auth.logout()
        val nextSignIn = appScope.async { w.auth.login(SECOND, "second-password") }
        delay(200)
        assertNull(w.credentials.ntustStudentId)
        assertFalse(nextSignIn.isCompleted)

        held.release()

        assertTrue(withTimeout(10_000) { nextSignIn.await() })
        assertTrue(w.dataCache.saveUnsentMarks(listOf(mark), w.sessions.school.signIns.current))
        delay(100)
        assertTrue("no wipe was left to take it", userFile("unsent_marks.json").exists())
        assertFalse(owed.owed)
    }

    private companion object {
        const val FIRST = "B00015998"
        const val SECOND = "B00015999"
        const val SEMESTER = "1141"
    }
}
