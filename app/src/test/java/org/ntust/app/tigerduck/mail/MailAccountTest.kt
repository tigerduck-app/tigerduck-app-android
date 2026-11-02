package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.auth.session.Account
import org.ntust.app.tigerduck.auth.session.AccountEvent
import org.ntust.app.tigerduck.auth.session.AccountSession
import org.ntust.app.tigerduck.auth.session.SignOutReason
import org.ntust.app.tigerduck.auth.session.StartsAccountWork
import org.ntust.app.tigerduck.auth.session.accountEventStream
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.index.InMemoryMailSearchIndex
import org.ntust.app.tigerduck.mail.index.MailSearchIndex
import org.ntust.app.tigerduck.mail.store.HeldSendStore
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.store.MailCredentialStore
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.account.SignInSeries
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.ntust.app.tigerduck.util.testSignIn

@OptIn(StartsAccountWork::class)
class MailAccountTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeMailServer()
    private val credentials = InMemoryCredentialStore()
    private val state = InMemoryMailStateStore()
    private val scheduler = RecordingScheduler()
    private val notifier = RecordingNotifier()
    private val demo = FakeDemoGate()
    private val cache by lazy { MailCache(tmp.root) }

    private val appScope = testApplicationScope()

    @After
    fun stopTheAppScope() = appScope.cancelAndJoinBlocking()

    // Apart from the cache, which a sign-out deletes whole.
    @get:Rule val heldTmp = TemporaryFolder()
    private val heldRoot by lazy { File(heldTmp.root, "held") }
    private val heldSends by lazy { HeldSendStore(heldRoot) }

    private val searchIndex = InMemoryMailSearchIndex()

    /** The school's session, which mail counts as the demo with. */
    private val school = AccountSession(SignInSeries(), appScope)

    private fun account(index: MailSearchIndex = searchIndex) = MailAccount(
        credentials, state, server.factory(), cache, demo, schoolMailSite(), scheduler, notifier, appScope, heldSends, index,
        AccountSession(SignInSeries(demo = state.demoMailbox), appScope, alsoDemoWhile = school, account = Account.MAIL),
    )

    /** An index that cannot be emptied, as a full disk fails SQLite's VACUUM. */
    private class UnclearableIndex(inner: MailSearchIndex = InMemoryMailSearchIndex()) : MailSearchIndex by inner {
        override fun clearUnless(keep: (owner: String?) -> Boolean) = throw IllegalStateException("database or disk is full")
    }

    /** Mail of [owner]'s downloaded for search, as the index worker would. */
    private fun indexMail(owner: String) = assertTrue(searchIndex.open(owner, "INBOX", 1L) { true })

    private val heldFields = HeldSendStore.Fields(
        "NEW", "", -1, false, null, null, listOf("amy@x.tw"), emptyList(), emptyList(), "Hi", "Hello",
    )

    private fun MailAccount.holdMail(owner: String) = assertTrue(heldSends.keep("m1", owner, heldFields, emptyList(), signInNow))

    @Test
    fun `successful sign-in stores the account, sets the baseline and fills the display name`() = runTest {
        server.deliver("old mail")
        server.deliver("sent", folder = "寄件備份匣", from = MailAddress("中文名", "b10000001@mail.ntust.edu.tw"))
        val account = account()
        assertNull(account.signIn(" b10000001 ", "pw"))
        assertTrue(account.signedIn.value)
        assertEquals("b10000001", credentials.mailStudentId)
        assertEquals("pw", credentials.mailPassword)
        assertEquals(server.nextUid, state.inboxSeenUidNext)
        assertEquals(server.uidValidity, state.inboxUidValidity)
        assertEquals("中文名", state.displayName)
        assertEquals(1, scheduler.scheduled)
        assertEquals(0, server.openSessions)
    }

    @Test
    fun `failures store nothing`() = runTest {
        val account = account()
        assertTrue(account.signIn("b10000001", "wrong") is MailError.AuthFailed)
        server.openError = MailError.Certificate()
        assertTrue(account.signIn("b10000001", "pw") is MailError.Certificate)
        assertFalse(account.signedIn.value)
        assertNull(credentials.mailPassword)
        assertEquals(0, scheduler.scheduled)
    }

    @Test
    fun `demo credentials never open a socket`() = runTest {
        val account = account()
        assertNull(account.signIn("B10000099", "demo"))
        assertTrue(account.isDemo)
        assertEquals("示範同學", state.displayName)
        assertEquals(0, server.opens)
    }

    @Test
    fun `while the app demo is active a real sign-in is refused offline`() = runTest {
        school.signIns.startDemo()
        assertTrue(account().signIn("b10000001", "pw") is MailError.AuthFailed)
        assertEquals(0, server.opens)
    }

    @Test
    fun `app demo mode makes isDemo true even with a real account already signed in`() = runTest {
        val account = account()
        account.signIn("b10000001", "pw")
        assertFalse(account.isDemo)
        school.signIns.startDemo()
        assertTrue(account.isDemo)
    }

    @Test
    fun `a real account can sign in over the demo mailbox, which is no app-wide demo`() = runTest {
        val account = account()
        account.signIn("B10000099", "demo")
        assertTrue(account.isDemo)

        assertNull(account.signIn("b10000001", "pw"))

        assertEquals(1, server.opens)
        assertFalse(account.isDemo)
        assertFalse(state.demoMailbox)
    }

    @Test
    fun `the same student's password typed again leaves the demo mailbox`() = runTest {
        server.passwords["B10000099"] = "real"
        val account = account()
        account.signIn("B10000099", "demo")

        assertNull(account.signIn("B10000099", "real"))

        assertFalse(account.isDemo)
    }

    @Test
    fun `the demo mailbox sign-in goes with its sign-out`() = runTest {
        val account = account()
        account.signIn("B10000099", "demo")

        account.signOut(SignOutReason.MAIL)

        assertFalse(account.isDemo)
        assertFalse(state.demoMailbox)
        assertNull(account.signIn("b10000001", "pw"))
        assertFalse(account.isDemo)
    }

    @Test
    fun `a demo mailbox signed in before this process stays the demo`() = runTest {
        account().signIn("B10000099", "demo")

        assertTrue(account().isDemo)
    }

    @Test
    fun `the school leaving the demo leaves a real account as it was`() = runTest {
        val account = account()
        account.signIn("b10000001", "pw")
        school.signIns.startDemo()
        val inTheDemo = account.currentSignIn

        school.signIns.leaveDemo()

        assertFalse(account.isDemo)
        assertTrue("the school's demo is no sign-in of the mail account's", inTheDemo.isCurrent)
    }

    @Test
    fun `signing in again as the same student keeps an edited display name`() = runTest {
        val account = account()
        account.signIn("b10000001", "pw")
        state.displayName = "我改的名字"
        account.onAuthFailure()
        assertNull(account.signIn("B10000001", "pw"))
        assertEquals("我改的名字", state.displayName)
        assertFalse(account.authFailed.value)
    }

    /**
     * A password typed again after the server rejected it is the same student's account: what
     * they chose stays as they chose it, and the notice that their sign-in failed goes.
     */
    @Test
    fun `signing in again as the same student keeps their new-mail switch and the mail still to be expunged`() = runTest {
        val account = account()
        account.signIn("b10000001", "pw")
        state.notificationsEnabled = false
        state.setOwnedDeleted("INBOX", server.uidValidity, setOf(7L))
        account.onAuthFailure()
        val noticesTakenDown = notifier.authFailuresCancelled
        assertNull(account.signIn("B10000001", "pw"))
        assertFalse(state.notificationsEnabled)
        assertEquals(setOf(7L), state.ownedDeleted("INBOX", server.uidValidity))
        assertEquals(noticesTakenDown + 1, notifier.authFailuresCancelled)
        assertEquals("the same student's new-mail notices stay", 0, notifier.cancelledAll)
    }

    @Test
    fun `signing in as another student takes the previous one's notifications down`() = runTest {
        val account = account()
        account.signIn("b10000001", "pw")
        state.notificationsEnabled = false
        server.passwords["B10000002"] = "pw"
        assertNull(account.signIn("b10000002", "pw"))
        assertEquals(1, notifier.cancelledAll)
        assertTrue("another student starts from the default", state.notificationsEnabled)
    }

    @Test
    fun `a refusal of a login sent under a sign-in that has left marks nothing and stops nothing`() = runTest {
        val account = account()
        account.signIn("b10000001", "pw")
        val before = account.signInNow
        // Signed in again, as the same student with the same password.
        account.signIn("b10000001", "pw")

        account.onAuthFailure(before)
        assertFalse(account.authFailed.value)
        assertEquals(0, scheduler.cancelled)

        account.onAuthFailure(account.signInNow)
        assertTrue(account.authFailed.value)
        assertEquals(1, scheduler.cancelled)
    }

    /**
     * A refusal marks the account and cancels its checks in one step with a sign-in's move of the
     * generation: a sign-in that comes while the refusal is cancelling waits for it, and keeps the
     * checks it then schedules.
     */
    @Test
    fun `a sign-in during a refusal's cancel keeps the checks it schedules`() = runTest {
        val account = account()
        account.signIn("b10000001", "pw")
        // The student signs in again while the refusal cancels the checks.
        val again = SignInAgain(account, "b10000001", "pw")
        scheduler.onCancel = {
            scheduler.onCancel = null
            again.during()
        }

        account.onAuthFailure(account.signInNow)
        again.join()

        assertEquals("the new sign-in's checks are the last word", listOf("schedule", "cancel", "schedule"), scheduler.events.toList())
        assertFalse(account.authFailed.value)
    }

    @Test
    fun `every sign-in and sign-out moves the generation, and a sign-in the server refuses does not`() = runTest {
        val account = account()
        val atStart = account.signInNow

        account.signIn("b10000001", "pw")
        val first = account.signInNow
        assertNotEquals("a sign-in", atStart, first)

        assertTrue(account.signIn("b10000001", "wrong") is MailError.AuthFailed)
        assertEquals("a refused sign-in", first, account.signInNow)

        account.signIn("b10000001", "pw")
        val again = account.signInNow
        assertNotEquals("the same student, the same password", first, again)

        server.passwords["B10000002"] = "pw"
        account.signIn("b10000002", "pw")
        val other = account.signInNow
        assertNotEquals("another student", again, other)

        account.signOut(SignOutReason.MAIL)
        val out = account.signInNow
        assertNotEquals("a sign-out", other, out)

        // The time signed out belongs to the next sign-in, and a second sign-out still moves it.
        account.signOut(SignOutReason.MAIL)
        assertNotEquals("a sign-out with no one signed in", out, account.signInNow)
    }

    @Test
    fun `each sign-in and sign-out is told, in order, with the sign-in that ended and who left`() = runTest {
        val account = account()
        val told = mutableListOf<AccountEvent>()
        val listening = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            account.events.collect { told += it }
        }

        account.signIn("b10000001", "pw")
        assertEquals(
            listOf(AccountEvent.SignedIn(Account.MAIL, byHand = true), AccountEvent.SignedIn(Account.MAIL, byHand = false)),
            told,
        )

        // The same student again shows no change.
        told.clear()
        account.signIn("b10000001", "pw")
        assertEquals(listOf<AccountEvent>(AccountEvent.SignedIn(Account.MAIL, byHand = true)), told)

        told.clear()
        val ended = account.signInNow
        val left = account.ownAddress
        account.signOut(SignOutReason.MAIL)
        assertEquals(listOf<AccountEvent>(AccountEvent.SignedOut(Account.MAIL, SignOutReason.MAIL, ended, left)), told)
        assertFalse(ended.isCurrent)

        // One with nobody signed in is told too, for the cleanup that must run on every one, with the
        // reason it was given.
        told.clear()
        account.signOut(SignOutReason.FULL_RESET)
        assertEquals(1, told.size)
        assertNull((told.single() as AccountEvent.SignedOut).address)
        assertEquals(SignOutReason.FULL_RESET, (told.single() as AccountEvent.SignedOut).reason)
        listening.cancel()
    }

    @Test
    fun `its events are its own, and its changes follow its state`() = runTest {
        val stream = accountEventStream()
        val session = AccountSession(SignInSeries(), appScope, account = Account.MAIL, stream = stream)
        val account = MailAccount(
            credentials, state, server.factory(), cache, demo, schoolMailSite(), scheduler, notifier, appScope, session = session,
        )
        val told = mutableListOf<AccountEvent>()
        val changes = mutableListOf<Boolean>()
        val following = CoroutineScope(Dispatchers.Unconfined)
        following.launch(start = CoroutineStart.UNDISPATCHED) { account.events.collect { told += it } }
        following.launch(start = CoroutineStart.UNDISPATCHED) { session.changes.collect { changes += it } }

        stream.tryEmit(AccountEvent.SignedOut(Account.SCHOOL, SignOutReason.SCHOOL, testSignIn(), null))
        assertEquals(emptyList<AccountEvent>(), told)

        account.signIn("b10000001", "pw")
        account.signOut(SignOutReason.MAIL)
        following.cancel()

        assertEquals(listOf(false, true, false), changes)
    }

    @Test
    fun `a sign-out is told with the account already gone, and a student change with the student`() = runTest {
        val account = account()
        account.signIn("b10000001", "pw")
        val first = account.signInNow
        server.passwords["B10000002"] = "pw"
        val atTheTelling = mutableListOf<Boolean>()
        val listening = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            account.events.collect { event ->
                if (event !is AccountEvent.SignedIn) atTheTelling += account.hasLeft(first)
            }
        }

        account.signIn("b10000002", "pw")
        assertEquals("the one who left counts as gone by the time anyone hears", listOf(true), atTheTelling)

        atTheTelling.clear()
        account.signOut(SignOutReason.MAIL)
        assertEquals(listOf(true), atTheTelling)
        assertFalse(account.signedIn.value)
        listening.cancel()
    }

    @Test
    fun `a student change is told while the departing sign-in still stands`() = runTest {
        val account = account()
        account.signIn("b10000001", "pw")
        val before = account.signInNow
        server.passwords["B10000002"] = "pw"
        var atTheTelling: SignIn? = null
        val listening = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            account.events.collect { if (it is AccountEvent.StudentChanged) atTheTelling = account.signInNow }
        }

        account.signIn("b10000002", "pw")
        listening.cancel()

        assertEquals(before, atTheTelling)
        assertNotEquals(before, account.signInNow)
    }

    @Test
    fun `a snapshot is the signed-in account with its own sign-in, and nothing while signed out`() = runTest {
        val account = account()
        assertNull(account.snapshot())

        account.signIn("b10000001", "pw")
        val first = account.snapshot()!!
        assertEquals("b10000001", first.credentials.studentId)
        assertEquals(account.signInNow, first.signIn)
        assertEquals(state.clears, first.clears)

        account.signOut(SignOutReason.MAIL)
        assertNull(account.snapshot())
    }

    @Test
    fun `a sign-out or a sign-in ends the sign-in a snapshot was read under, for good`() = runTest {
        val account = account()
        account.signIn("b10000001", "pw")
        val first = account.snapshot()!!.signIn
        assertTrue(first.isCurrent)

        account.signIn("b10000001", "pw")
        val again = account.snapshot()!!.signIn
        assertFalse("the same student, the same password", first.isCurrent)
        assertTrue(again.isCurrent)

        account.signOut(SignOutReason.MAIL)
        assertFalse("a sign-out", again.isCurrent)

        account.signIn("b10000001", "pw")
        assertFalse("never current again", again.isCurrent)
    }

    @Test
    fun `a student has left by a sign-out or another student, not by typing the password again`() = runTest {
        val account = account()
        account.signIn("b10000001", "pw")
        val first = account.signInNow
        assertFalse(account.hasLeft(first))

        account.signIn("b10000001", "pw")
        val again = account.signInNow
        assertFalse("ended, with the student still here", first.isCurrent)
        assertFalse(account.hasLeft(first))
        assertFalse(account.hasLeft(again))

        account.signOut(SignOutReason.MAIL)
        assertTrue("a sign-out", account.hasLeft(first))
        assertTrue(account.hasLeft(again))

        account.signIn("b10000001", "pw")
        assertTrue("back, under a sign-in of their own", account.hasLeft(again))
        assertFalse(account.hasLeft(account.signInNow))
    }

    @Test
    fun `a sign-in just opened has not left, nor has one this account never held`() = runTest {
        val account = account()
        assertFalse(account.hasLeft(account.signInNow))
        assertFalse(account.hasLeft(openSignIn()))
        account.signOut(SignOutReason.MAIL)
        assertFalse("the time signed out is the next sign-in's", account.hasLeft(account.signInNow))
    }

    @Test
    fun `a student told to have left has left, though their sign-in has yet to end`() = runTest {
        val account = account()
        account.signIn("b10000001", "pw")
        val leaving = account.signInNow
        server.passwords["B10000002"] = "pw"
        var leftInTheGap = false
        var currentInTheGap = false
        account.onLockWait = {
            account.onLockWait = null
            leftInTheGap = account.hasLeft(leaving)
            currentInTheGap = leaving.isCurrent
        }

        account.signIn("b10000002", "pw")

        assertTrue(leftInTheGap)
        assertTrue(currentInTheGap)
        assertFalse("the next student has not", account.hasLeft(account.signInNow))
    }

    @Test
    fun `the mail session never stays closed`() = runTest {
        val session = AccountSession(SignInSeries(), appScope)
        val account = MailAccount(
            credentials, state, server.factory(), cache, demo, schoolMailSite(), scheduler, notifier, appScope, session = session,
        )
        account.signIn("b10000001", "pw")
        assertTrue(session.signIns.isOpen.value)
        account.signOut(SignOutReason.MAIL)
        assertTrue(session.signIns.isOpen.value)
    }

    @Test
    fun `a refusal under a sign-in that has ended marks nothing, and under the current one it does`() = runTest {
        val account = account()
        account.signIn("b10000001", "pw")
        val before = account.snapshot()!!.signIn
        account.signIn("b10000001", "pw")

        assertFalse(account.onAuthFailure(before))
        assertFalse(account.authFailed.value)
        assertEquals(0, scheduler.cancelled)

        assertTrue(account.onAuthFailure(account.snapshot()!!.signIn))
        assertTrue(account.authFailed.value)
        assertEquals(1, scheduler.cancelled)
    }

    /** The credentials go in last under the lock, and a read must wait for all of it. */
    @Test
    fun `a snapshot read during a sign-in waits for it, and holds the next sign-in with the next student`() = runTest {
        var reader: Thread? = null
        var read: MailAccount.Snapshot? = null
        var waited = false
        lateinit var account: MailAccount
        val atTheLast = object : MailCredentialStore by credentials {
            override var mailPassword: String?
                get() = credentials.mailPassword
                set(value) {
                    credentials.mailPassword = value
                    val started = thread(isDaemon = true) { read = account.snapshot() }
                    reader = started
                    started.join(200)
                    waited = started.isAlive
                }
        }
        account = MailAccount(atTheLast, state, server.factory(), cache, demo, schoolMailSite(), scheduler, notifier, appScope)

        account.signIn("b10000001", "pw")
        reader!!.join(5_000)

        assertTrue("the read went through the sign-in's hold", waited)
        assertEquals("b10000001", read?.credentials?.studentId)
        assertTrue(read!!.signIn.isCurrent)
    }

    @Test
    fun `work on the mail session goes when its sign-in ends, by a sign-out or a sign-in`() = runTest {
        val session = AccountSession(SignInSeries(), appScope)
        val account = MailAccount(
            credentials, state, server.factory(), cache, demo, schoolMailSite(), scheduler, notifier, appScope, session = session,
        )
        account.signIn("b10000001", "pw")
        val untilTheSignIn = session.launch { awaitCancellation() }
        account.signIn("b10000001", "pw")
        withContext(Dispatchers.Default) { withTimeout(5_000) { untilTheSignIn.join() } }
        assertTrue(untilTheSignIn.isCancelled)

        val untilTheSignOut = session.launch { awaitCancellation() }
        account.signOut(SignOutReason.MAIL)
        withContext(Dispatchers.Default) { withTimeout(5_000) { untilTheSignOut.join() } }
        assertTrue(untilTheSignOut.isCancelled)
    }

    /** Signs out and runs [during] before the cleanup the sign-out starts, which then runs to its end. */
    private fun signOutWithItsCleanupLate(account: MailAccount, during: () -> Unit) {
        val late = CountDownLatch(1)
        account.onCleanup = { late.await(30, TimeUnit.SECONDS) }
        account.signOut(SignOutReason.MAIL)
        during()
        late.countDown()
        runBlocking {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { while (notifier.cancelledAll == 0) delay(10) }
            }
        }
        account.onCleanup = null
    }

    @Test
    fun `a late cleanup of a sign-out keeps mail held under the next sign-in, same student`() = runTest {
        val account = account()
        val me = "b10000001@mail.ntust.edu.tw"
        account.signIn("b10000001", "pw")
        assertTrue(heldSends.keep("left", me, heldFields, emptyList(), account.signInNow))

        signOutWithItsCleanupLate(account) {
            runBlocking { account.signIn("b10000001", "pw") }
            assertTrue(heldSends.keep("since", me, heldFields, emptyList(), account.signInNow))
        }

        assertFalse("the sign-in that left", File(heldRoot, "left").exists())
        assertEquals("since", HeldSendStore(heldRoot).orphan(me)?.id)
    }

    /**
     * A password typed again moves the sign-in on with the student still here: what they hold
     * under the sign-in before it stays theirs, and is no sign-out's to clear.
     */
    @Test
    fun `a late cleanup of a sign-out keeps mail held since, though a password typed again moved that sign-in on`() = runTest {
        val account = account()
        val me = "b10000001@mail.ntust.edu.tw"
        account.signIn("b10000001", "pw")

        signOutWithItsCleanupLate(account) {
            runBlocking { account.signIn("b10000001", "pw") }
            assertTrue(heldSends.keep("since", me, heldFields, emptyList(), account.signInNow))
            runBlocking { account.signIn("b10000001", "pw") }
        }

        assertEquals("since", HeldSendStore(heldRoot).orphan(me)?.id)
    }

    @Test
    fun `auth failure stops background checks`() = runTest {
        val account = account()
        account.signIn("b10000001", "pw")
        account.onAuthFailure()
        assertTrue(state.authFailed)
        assertTrue(account.authFailed.value)
        assertEquals(1, scheduler.cancelled)
    }

    @Test
    fun `sign-out wipes credentials, state, cache and notifications`() = runTest {
        val account = account()
        account.signIn("b10000001", "pw")
        cache.attachmentsDir.mkdirs()
        File(cache.attachmentsDir, "a.pdf").writeText("x")
        account.signOut(SignOutReason.MAIL)
        // Everything the repository's sign-out collector and the next sign-in depend on
        // has already happened when signOut() returns; both callers are on the main thread.
        assertFalse(account.signedIn.value)
        assertNull(credentials.mailStudentId)
        assertEquals(0L, state.inboxSeenUidNext)
        assertEquals(1, scheduler.cancelled)
        // Deleting the cache tree and cancelling notifications are the IO half, on the
        // application scope -- bridge to real time to see them land.
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (File(cache.attachmentsDir, "a.pdf").exists() || notifier.cancelledAll == 0) delay(10)
            }
        }
        assertFalse(File(cache.attachmentsDir, "a.pdf").exists())
        assertEquals(1, notifier.cancelledAll)
    }

    @Test
    fun `sign-out deletes the mail held for Undo or being sent`() = runTest {
        val account = account()
        account.signIn("b10000001", "pw")
        account.holdMail("b10000001@mail.ntust.edu.tw")

        account.signOut(SignOutReason.MAIL)

        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (notifier.cancelledAll == 0) delay(10) }
        }
        assertNull(HeldSendStore(heldRoot).orphan("b10000001@mail.ntust.edu.tw"))
        assertFalse(File(heldRoot, "m1").exists())
    }

    @Test
    fun `sign-out empties the mail search index, though no repository is there to hear it`() = runTest {
        // A sign-out from Settings, or a full reset, in a process that never opened Mail: only
        // the account is there, and the index still holds every downloaded mail's text.
        val account = account()
        account.signIn("b10000001", "pw")
        indexMail("b10000001@mail.ntust.edu.tw")

        account.signOut(SignOutReason.MAIL)

        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (notifier.cancelledAll == 0) delay(10) }
        }
        assertNull(searchIndex.uidValidity("b10000001@mail.ntust.edu.tw", "INBOX"))
    }

    @Test
    fun `an index that cannot be emptied still lets the sign-out cancel the notifications and delete the cache`() = runTest {
        val account = account(UnclearableIndex())
        account.signIn("b10000001", "pw")
        cache.attachmentsDir.mkdirs()
        File(cache.attachmentsDir, "a.pdf").writeText("x")

        account.signOut(SignOutReason.MAIL)

        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (notifier.cancelledAll == 0) delay(10) }
        }
        assertEquals(1, notifier.cancelledAll)
        assertFalse(File(cache.attachmentsDir, "a.pdf").exists())
    }

    @Test
    fun `an index that cannot be emptied does not stop another student signing in`() = runTest {
        val account = account(UnclearableIndex())
        account.signIn("b10000001", "pw")
        server.passwords["B10000002"] = "pw"

        assertNull(account.signIn("b10000002", "pw"))
        assertEquals("b10000002", credentials.mailStudentId)
    }

    @Test
    fun `another student signing in empties the previous one's search index, and the same one signing in again keeps it`() = runTest {
        val account = account()
        account.signIn("b10000001", "pw")
        indexMail("b10000001@mail.ntust.edu.tw")

        assertNull(account.signIn("b10000001", "pw"))
        assertEquals("their own, still to search", 1L, searchIndex.uidValidity("b10000001@mail.ntust.edu.tw", "INBOX"))

        server.passwords["B10000002"] = "pw"
        assertNull(account.signIn("b10000002", "pw"))
        assertNull(searchIndex.uidValidity("b10000001@mail.ntust.edu.tw", "INBOX"))
    }

    @Test
    fun `another student signing in deletes the previous one's held mail, and the same one signing in again keeps it`() = runTest {
        val account = account()
        account.signIn("b10000001", "pw")
        account.holdMail("b10000001@mail.ntust.edu.tw")

        assertNull(account.signIn("b10000001", "pw"))
        assertTrue("their own, still to review", File(heldRoot, "m1").exists())

        server.passwords["B10000002"] = "pw"
        assertNull(account.signIn("b10000002", "pw"))
        assertFalse(File(heldRoot, "m1").exists())
    }
}
