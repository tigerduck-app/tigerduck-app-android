package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.index.InMemoryMailSearchIndex
import org.ntust.app.tigerduck.mail.index.MailSearchIndex
import org.ntust.app.tigerduck.mail.store.HeldSendStore
import org.ntust.app.tigerduck.mail.store.MailCache
import java.io.File

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

    private fun account(index: MailSearchIndex = searchIndex) =
        MailAccount(credentials, state, server.factory(), cache, demo, schoolMailSite(), scheduler, notifier, appScope, heldSends, index)

    /** An index that cannot be emptied, as a full disk fails SQLite's VACUUM. */
    private class UnclearableIndex(inner: MailSearchIndex = InMemoryMailSearchIndex()) : MailSearchIndex by inner {
        override fun clearUnless(keep: (owner: String?) -> Boolean) = throw IllegalStateException("database or disk is full")
    }

    /** Mail of [owner]'s downloaded for search, as the index worker would. */
    private fun indexMail(owner: String) = assertTrue(searchIndex.open(owner, "INBOX", 1L) { true })

    private val heldFields = HeldSendStore.Fields(
        "NEW", "", -1, false, null, null, listOf("amy@x.tw"), emptyList(), emptyList(), "Hi", "Hello",
    )

    private fun holdMail(owner: String) = assertTrue(heldSends.save("m1", owner, heldFields, emptyList()) { true })

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
        demo.appDemoActive = true
        assertTrue(account().signIn("b10000001", "pw") is MailError.AuthFailed)
        assertEquals(0, server.opens)
    }

    @Test
    fun `app demo mode makes isDemo true even with a real account already signed in`() = runTest {
        val account = account()
        account.signIn("b10000001", "pw")
        assertFalse(account.isDemo)
        demo.appDemoActive = true
        assertTrue(account.isDemo)
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
        val before = account.accountGeneration
        // Signed in again, as the same student with the same password.
        account.signIn("b10000001", "pw")

        account.onAuthFailure(before)
        assertFalse(account.authFailed.value)
        assertEquals(0, scheduler.cancelled)

        account.onAuthFailure(account.accountGeneration)
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

        account.onAuthFailure(account.accountGeneration)
        again.join()

        assertEquals("the new sign-in's checks are the last word", listOf("schedule", "cancel", "schedule"), scheduler.events.toList())
        assertFalse(account.authFailed.value)
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
        account.signOut()
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
        holdMail("b10000001@mail.ntust.edu.tw")

        account.signOut()

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

        account.signOut()

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

        account.signOut()

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
        holdMail("b10000001@mail.ntust.edu.tw")

        assertNull(account.signIn("b10000001", "pw"))
        assertTrue("their own, still to review", File(heldRoot, "m1").exists())

        server.passwords["B10000002"] = "pw"
        assertNull(account.signIn("b10000002", "pw"))
        assertFalse(File(heldRoot, "m1").exists())
    }
}
