package org.ntust.app.tigerduck.mail.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.mail.FakeDemoGate
import org.ntust.app.tigerduck.mail.FakeMailServer
import org.ntust.app.tigerduck.mail.InMemoryCredentialStore
import org.ntust.app.tigerduck.mail.InMemoryMailStateStore
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.RecordingNotifier
import org.ntust.app.tigerduck.mail.RecordingScheduler
import org.ntust.app.tigerduck.mail.schoolMailSite
import org.ntust.app.tigerduck.mail.imap.MailSessionFactory
import org.ntust.app.tigerduck.mail.model.FolderStatus
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.testApplicationScope
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class MailCheckerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeMailServer()
    private val state = InMemoryMailStateStore()
    private val notifier = RecordingNotifier()
    private val scheduler = RecordingScheduler()
    private var now = 1_000_000L
    private val account by lazy {
        MailAccount(InMemoryCredentialStore(), state, server.factory(), MailCache(tmp.root), FakeDemoGate(), schoolMailSite(), scheduler, notifier, testApplicationScope())
    }

    private fun checker(
        factory: MailSessionFactory = server.factory(),
        cache: MailCache = MailCache(tmp.root),
    ) = MailChecker(account, state, factory, notifier, cache) { now }

    @Test
    fun `nothing happens before sign-in`() = runTest {
        assertEquals(CheckOutcome.NotSignedIn, checker().check(CheckSource.ALARM))
    }

    @Test
    fun `new unread inbox mail notifies once and moves the marker`() = runTest {
        server.deliver("before sign-in")
        account.signIn("b10000001", "pw")
        val first = server.deliver("new one")
        server.deliver("already read", seen = true)
        val outcome = checker().check(CheckSource.ALARM)
        assertEquals(CheckOutcome.NewMail(1), outcome)
        assertEquals(listOf("new one"), notifier.posted.single().map { it.subject })
        assertEquals(first + 2, state.inboxSeenUidNext)
        assertEquals(CheckOutcome.NoChange, checker().check(CheckSource.WORKER))
        assertEquals(1, notifier.posted.size)
        assertEquals(0, server.openSessions)
    }

    /**
     * A check that was called off is not a check that failed. The cancellation used to be caught
     * with every other Exception and classified -- classify has no case for one -- so the run was
     * recorded as Failed:Protocol before the surrounding withContext rethrew it anyway, leaving a
     * failure in the diagnostics the student is asked to read out when mail stops arriving.
     */
    @Test
    fun `a cancelled check is rethrown, not recorded as a failure`() = runTest {
        account.signIn("b10000001", "pw")
        state.diagnostics = emptyList()
        // kotlinx recovers the stack trace across withContext, so this is a copy of the
        // exception thrown below, not the same instance.
        val thrown = runCatching {
            checker(MailSessionFactory { throw CancellationException("called off") }).check(CheckSource.ALARM)
        }.exceptionOrNull()
        assertTrue("expected the cancellation to propagate, got $thrown", thrown is CancellationException)
        assertEquals("called off", thrown?.message)
        assertEquals(emptyList<String>(), state.diagnostics)
    }

    @Test
    fun `a new UIDVALIDITY resets the baseline without notifying`() = runTest {
        account.signIn("b10000001", "pw")
        server.uidValidity = 99
        server.deliver("would flood")
        assertTrue(checker().check(CheckSource.ALARM) is CheckOutcome.Baseline)
        assertTrue(notifier.posted.isEmpty())
        assertEquals(99L, state.inboxUidValidity)
    }

    @Test
    fun `a rejected password stops background checks and posts one notice`() = runTest {
        account.signIn("b10000001", "pw")
        server.passwords["B10000001"] = "changed"
        val outcome = checker().check(CheckSource.ALARM)
        assertTrue(outcome is CheckOutcome.Failed && outcome.error is MailError.AuthFailed)
        assertTrue(state.authFailed)
        assertEquals(1, notifier.authFailures)
        assertEquals(1, scheduler.cancelled)
        assertEquals(CheckOutcome.Disabled, checker().check(CheckSource.ALARM))
        assertEquals(1, notifier.authFailures)
    }

    @Test
    fun `network errors just skip the round`() = runTest {
        account.signIn("b10000001", "pw")
        server.openError = MailError.Network()
        val outcome = checker().check(CheckSource.WORKER)
        assertTrue(outcome is CheckOutcome.Failed && outcome.error is MailError.Network)
        assertEquals(false, state.authFailed)
    }

    @Test
    fun `foreground checks are throttled to one a minute and notifications can be turned off`() = runTest {
        account.signIn("b10000001", "pw")
        assertEquals(CheckOutcome.NoChange, checker().check(CheckSource.FOREGROUND))
        now += 30_000
        assertEquals(CheckOutcome.Throttled, checker().check(CheckSource.FOREGROUND))
        now += 31_000
        assertEquals(CheckOutcome.NoChange, checker().check(CheckSource.FOREGROUND))
        state.notificationsEnabled = false
        assertEquals(CheckOutcome.Disabled, checker().check(CheckSource.ALARM))
    }

    @Test
    fun `the demo mailbox never connects`() = runTest {
        account.signIn("B10000099", "demo")
        assertEquals(CheckOutcome.Demo, checker().check(CheckSource.ALARM))
        assertEquals(0, server.opens)
    }

    @Test
    fun `mail the list already showed never notifies later`() = runTest {
        account.signIn("b10000001", "pw")
        server.deliver("seen in the list")
        val c = checker()
        c.noteSeenByPage(FolderStatus(server.uidValidity, server.nextUid, 1, 1))
        assertEquals(CheckOutcome.NoChange, c.check(CheckSource.ALARM))
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun `only one check runs at a time`() {
        runBlocking { account.signIn("b10000001", "pw") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = MailSessionFactory { creds ->
            entered.countDown(); release.await(); server.factory().open(creds)
        }
        val c = checker(slow)
        var first: CheckOutcome? = null
        val t = thread { first = runBlocking { c.check(CheckSource.ALARM) } }
        entered.await()
        assertEquals(CheckOutcome.Busy, runBlocking { c.check(CheckSource.WORKER) })
        release.countDown()
        t.join()
        assertEquals(CheckOutcome.NoChange, first)
    }

    @Test
    fun `the marker moves after the notification, and never backwards past a page poll`() = runTest {
        account.signIn("b10000001", "pw")
        val uid = server.deliver("new one")
        val markerBefore = state.inboxSeenUidNext
        val c = checker()
        var markerWhenNotified = -1L
        notifier.onPostNewMail = {
            // Spec §8.5 order: notify first, advance after.
            markerWhenNotified = state.inboxSeenUidNext
            // The page poll ran in this window and saw further mail than this check fetched.
            c.noteSeenByPage(FolderStatus(server.uidValidity, uid + 10, 1, 0))
        }

        assertEquals(CheckOutcome.NewMail(1), c.check(CheckSource.ALARM))
        assertEquals(markerBefore, markerWhenNotified)
        // Overwriting the poll's marker would re-notify mail the list has already shown.
        assertEquals(uid + 10, state.inboxSeenUidNext)
    }

    @Test
    fun `every check is recorded for the diagnostics screen, newest first`() = runTest {
        account.signIn("b10000001", "pw")
        checker().check(CheckSource.ALARM)
        now += 1
        checker().check(CheckSource.WORKER)
        assertEquals(listOf("${now}|WORKER|NoChange", "${now - 1}|ALARM|NoChange"), state.diagnostics)
    }

    @Test
    fun `new mail arrives with its body already cached`() = runTest {
        val cache = MailCache(tmp.root)
        account.signIn("b10000001", "pw")
        checker(cache = cache).check(CheckSource.ALARM)   // baseline
        val uid = server.deliver("hello there")
        checker(cache = cache).check(CheckSource.ALARM)

        val validity = server.uidValidity
        assertNotNull(cache.loadBody("INBOX", uid, validity))
    }

    @Test
    fun `at most five bodies are fetched for one burst`() = runTest {
        val cache = MailCache(tmp.root)
        account.signIn("b10000001", "pw")
        checker(cache = cache).check(CheckSource.ALARM)
        val uids = (1..8).map { server.deliver("mail $it") }
        checker(cache = cache).check(CheckSource.ALARM)

        val validity = server.uidValidity
        val cached = uids.count { cache.loadBody("INBOX", it, validity) != null }
        assertEquals(5, cached)
    }

    @Test
    fun `the newest mail is the one that gets cached`() = runTest {
        val cache = MailCache(tmp.root)
        account.signIn("b10000001", "pw")
        checker(cache = cache).check(CheckSource.ALARM)
        val uids = (1..8).map { server.deliver("mail $it") }
        checker(cache = cache).check(CheckSource.ALARM)

        val validity = server.uidValidity
        assertNotNull(cache.loadBody("INBOX", uids.last(), validity))
        assertNull(cache.loadBody("INBOX", uids.first(), validity))
    }

    @Test
    fun `a body that will not fetch does not change the outcome`() = runTest {
        val cache = MailCache(tmp.root)
        account.signIn("b10000001", "pw")
        checker(cache = cache).check(CheckSource.ALARM)
        server.deliver("hello")
        server.failBodyFetch = true

        assertEquals(CheckOutcome.NewMail(1), checker(cache = cache).check(CheckSource.ALARM))
    }

    @Test
    fun `nothing is fetched when no mail arrived`() = runTest {
        val cache = MailCache(tmp.root)
        account.signIn("b10000001", "pw")
        checker(cache = cache).check(CheckSource.ALARM)
        server.bodyFetches = 0
        checker(cache = cache).check(CheckSource.ALARM)

        assertEquals(0, server.bodyFetches)
    }
}
