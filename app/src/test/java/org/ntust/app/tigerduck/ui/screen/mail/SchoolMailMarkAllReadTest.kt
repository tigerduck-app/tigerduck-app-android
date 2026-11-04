@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.ui.screen.mail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.mail.FakeDemoGate
import org.ntust.app.tigerduck.mail.FakeMailServer
import org.ntust.app.tigerduck.mail.FakeSchoolMailRepository
import org.ntust.app.tigerduck.mail.InMemoryCredentialStore
import org.ntust.app.tigerduck.mail.InMemoryMailStateStore
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.mail.RecordingNotifier
import org.ntust.app.tigerduck.mail.RecordingScheduler
import org.ntust.app.tigerduck.mail.SentCopyNotices
import org.ntust.app.tigerduck.mail.cancelAndJoinBlocking
import org.ntust.app.tigerduck.mail.imap.FolderSelection
import org.ntust.app.tigerduck.mail.mailSummary
import org.ntust.app.tigerduck.mail.schoolMailSite
import org.ntust.app.tigerduck.mail.signInNow
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.sync.MailChecker
import org.ntust.app.tigerduck.mail.testApplicationScope

/**
 * The unread filter's Mark all as read: every unread mail shown, through the write a swipe makes,
 * and none of the next student's once the sign-in it started under has ended.
 */
class SchoolMailMarkAllReadTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeMailServer()
    private val state = InMemoryMailStateStore()
    private val repo = FakeSchoolMailRepository()
    private lateinit var account: MailAccount
    private lateinit var vm: SchoolMailListViewModel
    private val appScope = testApplicationScope()

    @After
    fun stopAppScope() = appScope.cancelAndJoinBlocking()

    @Before
    fun setUp() {
        account = MailAccount(InMemoryCredentialStore(), state, server.factory(), MailCache(tmp.root),
            FakeDemoGate(), schoolMailSite(), RecordingScheduler(), RecordingNotifier(), appScope)
        runBlocking { account.signIn("b10000001", "pw") }
        val checker = MailChecker(account, state, server.factory(), RecordingNotifier(), MailCache(tmp.root)) { 0L }
        repo.currentSignIn = { account.signInNow }
        vm = SchoolMailListViewModel(
            repo, account, checker, schoolMailSite(),
            { main.dispatcher.scheduler.currentTime }, SentCopyNotices(account, CoroutineScope(main.dispatcher)),
        )
        repo.add("INBOX", mailSummary(1), mailSummary(2, seen = true), mailSummary(3), mailSummary(4))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
    }

    @Test
    fun `every unread mail shown is marked read in one batch, on the server and in the list, and no other`() {
        vm.markAllRead()

        assertEquals(listOf(4L, 3L, 1L), repo.seenCalls.map { it.second })
        assertTrue(repo.seenCalls.all { it.first == "INBOX" && it.third })
        assertEquals(1, repo.seenBatches.size)
        assertTrue(vm.state.value.displayed.all { it.summary.flags.seen })
        assertEquals(emptySet<String>(), vm.state.value.writing)
    }

    @Test
    fun `under unread only, the list empties and stays filtered`() {
        vm.setUnreadOnly(true)
        vm.markAllRead()

        assertEquals(3, repo.seenCalls.size)
        assertEquals(emptyList<Long>(), vm.state.value.displayed.map { it.uid })
        assertTrue(vm.state.value.unreadOnly)
    }

    @Test
    fun `once the sign-in it started under ends, nothing is sent`() {
        val gate = CompletableDeferred<Unit>()
        repo.seenGate = gate
        vm.markAllRead()
        server.passwords["B10000002"] = "pw"
        runBlocking { account.signIn("b10000002", "pw") }
        repo.seenGate = null
        gate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()

        assertEquals(emptyList<Long>(), repo.seenCalls.map { it.second })
        assertEquals(null, vm.state.value.actionError)
    }

    @Test
    fun `a second press while one runs sends no second STORE`() {
        val gate = CompletableDeferred<Unit>()
        repo.seenGate = gate
        vm.markAllRead()
        vm.markAllRead()
        repo.seenGate = null
        gate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()

        assertEquals(listOf(listOf(4L, 3L, 1L)), repo.seenBatches.map { it.uids })
    }

    @Test
    fun `a mail its batch holds is not swiped meanwhile, so no STORE crosses it`() {
        val gate = CompletableDeferred<Unit>()
        repo.seenGate = gate
        vm.markAllRead()
        val held = vm.state.value.displayed.first { it.uid == 3L }
        assertTrue(held.key in vm.state.value.writing)
        vm.toggleRead(held)
        repo.seenGate = null
        gate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()

        assertEquals(1, repo.seenBatches.size)
        assertTrue(vm.state.value.displayed.first { it.uid == 3L }.summary.flags.seen)
    }

    @Test
    fun `each batch carries the UIDVALIDITY its mail was read under, and a folder recreated since marks nothing`() {
        repo.pageValidity = 2L
        vm.markAllRead()

        assertEquals(emptyList<FakeSchoolMailRepository.SeenBatch>(), repo.seenBatches)
        assertTrue(vm.state.value.actionError is MailError.FolderChanged)
        assertEquals(3, vm.state.value.displayed.count { !it.summary.flags.seen })
        assertEquals(emptySet<String>(), vm.state.value.writing)
    }

    @Test
    fun `a refusal says so, and marks nothing`() {
        repo.seenError = MailError.Network()
        vm.markAllRead()

        assertEquals(emptyList<Long>(), repo.seenCalls.map { it.second })
        assertNotNull(vm.state.value.actionError)
        assertTrue(vm.state.value.displayed.filter { it.uid != 2L }.none { it.summary.flags.seen })
    }
}
