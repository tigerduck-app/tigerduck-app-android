package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.mail.model.MailAddress
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

    private fun account() = MailAccount(credentials, state, server.factory(), cache, demo, schoolMailSite(), scheduler, notifier, appScope)

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
}
