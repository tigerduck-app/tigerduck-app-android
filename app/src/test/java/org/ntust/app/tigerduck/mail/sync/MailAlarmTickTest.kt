package org.ntust.app.tigerduck.mail.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.ntust.app.tigerduck.mail.imap.MailSession
import org.ntust.app.tigerduck.mail.imap.MailSessionFactory
import org.ntust.app.tigerduck.mail.model.FolderStatus
import org.ntust.app.tigerduck.mail.store.MailCache
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class MailAlarmTickTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeMailServer()
    private val state = InMemoryMailStateStore()
    private val notifier = RecordingNotifier()
    private val account by lazy {
        MailAccount(InMemoryCredentialStore(), state, server.factory(), MailCache(tmp.root), FakeDemoGate(), schoolMailSite(), RecordingScheduler(), notifier, scope)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Released when the stalled read finally times out; until then STATUS never answers. */
    private val readTimesOut = CountDownLatch(1)
    private val readStarted = CountDownLatch(1)

    private val rearms = AtomicInteger()
    private val handOffs = AtomicInteger()

    @After
    fun tearDown() {
        readTimesOut.countDown()
        scope.cancel()
    }

    /** Logs in fine, then blocks inside STATUS the way a stalled socket read does, and fails once it "times out". */
    private val stallingFactory = MailSessionFactory { credentials ->
        val session = server.factory().open(credentials)
        object : MailSession by session {
            override fun status(folder: String): FolderStatus {
                readStarted.countDown()
                readTimesOut.await()
                throw MailError.Network()
            }
        }
    }

    private fun checker(factory: MailSessionFactory) = MailChecker(account, state, factory, notifier, MailCache(tmp.root)) { 0L }

    private fun tick(checker: MailChecker): CheckOutcome? = runBlocking {
        runAlarmTick(
            scope = scope,
            budgetMillis = BUDGET_MS,
            rearm = { rearms.incrementAndGet() },
            check = { checker.check(CheckSource.ALARM) },
            handOff = { handOffs.incrementAndGet() },
        )
    }

    @Test
    fun `a check stuck on a stalled read is handed off within the budget`() {
        runBlocking { account.signIn("b10000001", "pw") }
        val checker = checker(stallingFactory)

        var outcome: CheckOutcome? = CheckOutcome.NoChange
        val receiver = thread { outcome = tick(checker) }
        receiver.join(BUDGET_MS + GRACE_MS)

        assertFalse("the tick is still waiting on the blocked read", receiver.isAlive)
        assertTrue("the check never reached the read", readStarted.await(GRACE_MS, TimeUnit.MILLISECONDS))
        assertNull(outcome)
        assertEquals(1, handOffs.get())
        assertEquals(1, rearms.get())

        // The abandoned check keeps the lock until its read times out: the hand-off run sees Busy (and retries).
        assertEquals(CheckOutcome.Busy, runBlocking { checker.check(CheckSource.WORKER) })

        readTimesOut.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (runBlocking { checker.check(CheckSource.WORKER) } == CheckOutcome.Busy) {
            assertTrue("the abandoned check never unwound", System.nanoTime() < deadline)
            Thread.sleep(10)
        }
        assertEquals(0, server.openSessions)
    }

    @Test
    fun `a check that answers in time is not handed off`() {
        runBlocking { account.signIn("b10000001", "pw") }

        val outcome = tick(checker(server.factory()))

        assertEquals(CheckOutcome.NoChange, outcome)
        assertEquals(0, handOffs.get())
        assertEquals(1, rearms.get())
    }

    private companion object {
        const val BUDGET_MS = 200L
        const val GRACE_MS = 1_000L
    }
}
