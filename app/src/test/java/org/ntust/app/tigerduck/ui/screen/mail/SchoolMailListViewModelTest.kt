@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.ui.screen.mail

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.ntust.app.tigerduck.mail.mailSummary
import org.ntust.app.tigerduck.mail.model.FolderStatus
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.sync.MailChecker

class SchoolMailListViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeMailServer()
    private val state = InMemoryMailStateStore()
    private val repo = FakeSchoolMailRepository()
    private lateinit var account: MailAccount
    private lateinit var vm: SchoolMailListViewModel

    @Before
    fun setUp() {
        account = MailAccount(InMemoryCredentialStore(), state, server.factory(), MailCache(tmp.root),
            FakeDemoGate(), RecordingScheduler(), RecordingNotifier())
        runBlocking { account.signIn("b10000001", "pw") }
        vm = SchoolMailListViewModel(repo, account, MailChecker(account, state, server.factory(), RecordingNotifier()) { 0 })
    }

    @Test
    fun `load shows the folders, the inbox page, and tells the checker what the page saw`() {
        repo.add("INBOX", mailSummary(1), mailSummary(2), mailSummary(3))
        repo.status = FolderStatus(server.uidValidity, 4, 3, 3)
        vm.load()
        val s = vm.state.value
        assertEquals(listOf("INBOX", "寄件備份匣", "草稿匣", "廣告信匣", "回收筒"), s.chips.map { it.name })
        assertEquals(listOf("Moodle 課程討論區"), s.others)
        assertEquals(listOf(3L, 2L, 1L), s.displayed.map { it.uid })
        assertTrue(s.loadState is SchoolMailListViewModel.LoadState.Loaded)
        assertEquals(4L, state.inboxSeenUidNext)
    }

    @Test
    fun `unread only filters what is displayed`() {
        repo.add("INBOX", mailSummary(1, seen = true), mailSummary(2))
        vm.load()
        vm.setUnreadOnly(true)
        assertEquals(listOf(2L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `search shows results and says when the server could not search`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考"), mailSummary(2, subject = "other"))
        vm.load()
        vm.setSearchText("期中")
        vm.submitSearch()
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })
        assertFalse(vm.state.value.searchLocalOnly)
        repo.searchUnsupported = true
        vm.submitSearch()
        assertTrue(vm.state.value.searchLocalOnly)
        vm.setSearchText("")
        assertEquals(2, vm.state.value.displayed.size)
    }

    @Test
    fun `swiping toggles read on the server and in the list`() {
        repo.add("INBOX", mailSummary(1))
        vm.load()
        vm.toggleRead(vm.state.value.displayed.single())
        assertEquals(listOf(1L to true), repo.seenCalls)
        assertTrue(vm.state.value.displayed.single().flags.seen)
    }

    @Test
    fun `selecting a folder loads it and pagination appends`() {
        repo.pageSize = 2
        repo.add("回收筒", mailSummary(7), mailSummary(8), mailSummary(9))
        vm.load()
        vm.selectFolder("回收筒")
        assertEquals(listOf(9L, 8L), vm.state.value.displayed.map { it.uid })
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        assertEquals(listOf(9L, 8L, 7L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `a rejected password marks the account and shows the failure`() {
        repo.loadError = MailError.AuthFailed()
        vm.load()
        assertTrue(account.authFailed.value)
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
    }

    @Test
    fun `resuming reloads when the selected folder's cache disappeared while away`() {
        repo.add("INBOX", mailSummary(1))
        vm.load()
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })

        // Simulates a move/delete on the message screen throwing MailError.FolderChanged,
        // which drops the folder's cache in the (shared, singleton) repository.
        repo.dropCache("INBOX")
        repo.add("INBOX", mailSummary(2))
        vm.onResume()
        assertEquals(listOf(2L, 1L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `resuming does not reload when the selected folder's cache is still there`() {
        repo.add("INBOX", mailSummary(1))
        vm.load()
        repo.add("INBOX", mailSummary(2))
        vm.onResume()
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `polling reloads when the inbox has new mail`() {
        repo.add("INBOX", mailSummary(1))
        repo.status = FolderStatus(server.uidValidity, 2, 1, 1)
        vm.load()
        vm.startPolling()
        assertEquals(1, repo.acquired)
        repo.add("INBOX", mailSummary(2))
        repo.status = FolderStatus(server.uidValidity, 3, 2, 2)
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(listOf(2L, 1L), vm.state.value.displayed.map { it.uid })
        vm.stopPolling()
        assertEquals(1, repo.released)
    }
}
