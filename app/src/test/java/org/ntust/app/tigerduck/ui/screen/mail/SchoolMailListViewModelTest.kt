@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.ui.screen.mail

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import org.ntust.app.tigerduck.mail.imap.FolderSelection
import org.ntust.app.tigerduck.mail.imap.ResolvedFolders
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.mailSummary
import org.ntust.app.tigerduck.mail.model.FolderStatus
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.sync.MailChecker
import org.ntust.app.tigerduck.mail.testApplicationScope
import java.time.Instant

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
            FakeDemoGate(), RecordingScheduler(), RecordingNotifier(), testApplicationScope())
        runBlocking { account.signIn("b10000001", "pw") }
        vm = SchoolMailListViewModel(repo, account, MailChecker(account, state, server.factory(), RecordingNotifier()) { 0 })
    }

    @Test
    fun `load shows the folders, the inbox page, and tells the checker what the page saw`() {
        repo.add("INBOX", mailSummary(1), mailSummary(2), mailSummary(3))
        repo.status = FolderStatus(server.uidValidity, 4, 3, 3)
        vm.load()
        val s = vm.state.value
        assertEquals(
            listOf(
                FolderSelection.AllMail, FolderSelection.Real("INBOX"), FolderSelection.Real("寄件備份匣"),
                FolderSelection.Real("草稿匣"), FolderSelection.Real("廣告信匣"), FolderSelection.Real("回收筒"),
            ),
            s.chips.map { it.selection },
        )
        // 所有信件 is the one chip with no SpecialFolder behind it -- there is no such server folder.
        assertNull(s.chips.single { it.selection == FolderSelection.AllMail }.kind)
        // 所有信件 leads the row, and it is also what the screen opens on.
        assertEquals(FolderSelection.AllMail, s.selected)
        assertEquals(listOf("Moodle 課程討論區"), s.others)
        assertEquals(listOf(3L, 2L, 1L), s.displayed.map { it.uid })
        assertTrue(s.loadState is SchoolMailListViewModel.LoadState.Loaded)
        // The inbox is part of 所有信件's merge, so opening on it still moves the seen marker --
        // defaulting to 所有信件 must not cost the user the new-mail check.
        assertEquals(4L, state.inboxSeenUidNext)
    }

    @Test
    fun `when only one of 收件匣 or 寄件備份 resolves, there is nothing to merge and the default stays 收件匣`() {
        repo.resolved = ResolvedFolders(mapOf(SpecialFolder.INBOX to "INBOX"), emptyList())
        repo.add("INBOX", mailSummary(1))
        vm.load()
        val s = vm.state.value
        assertTrue(s.chips.none { it.selection == FolderSelection.AllMail })
        assertEquals(FolderSelection.Real("INBOX"), s.selected)
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
        assertEquals(listOf(Triple("INBOX", 1L, true)), repo.seenCalls)
        assertTrue(vm.state.value.displayed.single().summary.flags.seen)
    }

    @Test
    fun `selecting a folder loads it and pagination appends`() {
        repo.pageSize = 2
        repo.add("回收筒", mailSummary(7), mailSummary(8), mailSummary(9))
        vm.load()
        vm.selectFolder(FolderSelection.Real("回收筒"))
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
    fun `load shows the fresh server page after the repository dropped the folder's cache`() {
        // A move/delete on the message screen that hit MailError.FolderChanged drops the
        // folder's cache in the (shared, singleton) repository; load() -- which the screen
        // always reruns on returning to the list, since LaunchedEffect restarts whenever the
        // composable re-enters composition -- must not need that cache to show the current page.
        repo.add("INBOX", mailSummary(1))
        vm.load()
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })

        repo.dropCache("INBOX")
        repo.add("INBOX", mailSummary(2))
        vm.load()
        assertEquals(listOf(2L, 1L), vm.state.value.displayed.map { it.uid })
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

    @Test
    fun `a password the poll finds rejected marks the account, surfaces, and stops the poll`() {
        // Spec §7.4: the page poll must not keep re-sending a rejected LOGIN every minute --
        // repeated failures can lock the school account and the campus Wi-Fi that share the password.
        repo.add("INBOX", mailSummary(1))
        vm.load()
        vm.startPolling()
        repo.statusError = MailError.AuthFailed()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        assertTrue(account.authFailed.value)
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
        val callsAfterRejection = repo.statusCalls

        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS * 5)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(callsAfterRejection, repo.statusCalls)
    }

    @Test
    fun `polling does not start at all while the password is rejected, and starts again after signing in`() {
        repo.add("INBOX", mailSummary(1))
        vm.load()
        val statusCallsAfterLoad = repo.statusCalls

        account.onAuthFailure()
        vm.startPolling()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS * 3)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(0, repo.acquired)
        assertEquals(statusCallsAfterLoad, repo.statusCalls)

        // Signing in again clears the flag, and polling works from then on.
        runBlocking { account.signIn("b10000001", "pw") }
        assertFalse(account.authFailed.value)
        vm.startPolling()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(1, repo.acquired)
        assertEquals(statusCallsAfterLoad + 1, repo.statusCalls)
    }

    @Test
    fun `a certificate failure during a poll reaches the UI instead of being swallowed`() {
        repo.add("INBOX", mailSummary(1))
        vm.load()
        vm.startPolling()
        repo.statusError = MailError.Certificate()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        val failed = vm.state.value.loadState as SchoolMailListViewModel.LoadState.Failed
        assertTrue(failed.error is MailError.Certificate)
        assertFalse(account.authFailed.value)
    }

    @Test
    fun `signing out clears the previous account's mail from the screen`() {
        repo.add("INBOX", mailSummary(1), mailSummary(2))
        vm.load()
        assertEquals(2, vm.state.value.displayed.size)
        account.signOut()
        main.dispatcher.scheduler.runCurrent()
        assertEquals(SchoolMailListViewModel.UiState(), vm.state.value)
    }

    // --- 所有信件 ------------------------------------------------------------------------

    private fun at(millis: Long) = Instant.ofEpochMilli(millis)

    private fun selectAll() = vm.selectFolder(FolderSelection.AllMail)

    @Test
    fun `所有信件 merges only the inbox and sent folders, newest first`() {
        repo.add("INBOX", mailSummary(1, sentAt = at(300)), mailSummary(2, sentAt = at(100)))
        repo.add(SENT, mailSummary(5, sentAt = at(200)))
        // The folders the merge deliberately leaves out: drafts, junk, trash, user folders.
        repo.add("草稿匣", mailSummary(60, sentAt = at(999)))
        repo.add("廣告信匣", mailSummary(61, sentAt = at(999)))
        repo.add("回收筒", mailSummary(62, sentAt = at(999)))
        repo.add("Moodle 課程討論區", mailSummary(63, sentAt = at(999)))
        vm.load()
        selectAll()

        val rows = vm.state.value.displayed
        assertEquals(listOf("INBOX" to 1L, SENT to 5L, "INBOX" to 2L), rows.map { it.folder to it.uid })
    }

    @Test
    fun `the same UID in two folders is two separate rows, and acting on one leaves the other alone`() {
        // UIDs are unique only within a folder: 收件匣 and 寄件備份 both holding a UID 42 is
        // ordinary, and a list keyed by UID alone would collapse them into one row.
        repo.add("INBOX", mailSummary(42, subject = "收到的", sentAt = at(200)))
        repo.add(SENT, mailSummary(42, subject = "寄出的", sentAt = at(100)))
        vm.load()
        selectAll()

        val rows = vm.state.value.displayed
        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it.key }.toSet().size)
        assertEquals(listOf("收到的", "寄出的"), rows.map { it.summary.subject })

        vm.toggleRead(rows.last())
        val after = vm.state.value.displayed
        assertFalse(after.first { it.folder == "INBOX" }.summary.flags.seen)
        assertTrue(after.first { it.folder == SENT }.summary.flags.seen)
    }

    @Test
    fun `a 寄件備份 row acted on through 所有信件 addresses 寄件備份, not the selected chip`() {
        repo.add("INBOX", mailSummary(1, sentAt = at(100)))
        repo.add(SENT, mailSummary(7, sentAt = at(200)))
        vm.load()
        selectAll()

        val sentRow = vm.state.value.displayed.single { it.folder == SENT }
        vm.toggleRead(sentRow)
        assertEquals(listOf(Triple(SENT, 7L, true)), repo.seenCalls)
        // And the screen sends the tap to that row's own folder, drafts check included.
        assertEquals(SpecialFolder.SENT, vm.state.value.kindOf(sentRow.folder))
        assertEquals(SpecialFolder.INBOX, vm.state.value.kindOf("INBOX"))
    }

    @Test
    fun `no repository call in the merged view is ever given a folder the server does not have`() {
        // 所有信件 has no server-side existence at all -- a synthetic name reaching an IMAP
        // SELECT is the failure this whole design exists to make unrepresentable.
        repo.pageSize = 1
        repo.add("INBOX", mailSummary(1, subject = "期中考", sentAt = at(100)), mailSummary(2, sentAt = at(400)))
        repo.add(SENT, mailSummary(9, subject = "期中考", sentAt = at(200)), mailSummary(10, sentAt = at(300)))
        vm.load()
        selectAll()
        vm.loadMoreIfNeeded(vm.state.value.messages.last())
        vm.setSearchText("期中考")
        vm.submitSearch()
        vm.toggleRead(vm.state.value.displayed.first())
        vm.refresh()

        val realNames = (SpecialFolder.entries.map { it.decodedName } + repo.resolved.others).toSet()
        assertEquals(emptyList<String>(), repo.foldersTouched.filterNot { it in realNames })
        // …and it really did read both merged folders, so the check above isn't vacuous.
        assertTrue(repo.foldersTouched.containsAll(listOf("INBOX", SENT)))
    }

    @Test
    fun `the merged list keeps its own cursor per folder and ends only once both have run out`() {
        repo.pageSize = 1
        repo.add("INBOX", mailSummary(1, sentAt = at(100)), mailSummary(2, sentAt = at(400)))
        repo.add(SENT, mailSummary(9, sentAt = at(200)), mailSummary(10, sentAt = at(300)))
        vm.load()
        selectAll()

        // One page per folder, merged by date -- two round trips, not one per folder per scroll.
        assertEquals(listOf("INBOX" to 2L, SENT to 10L), vm.state.value.messages.map { it.folder to it.uid })
        assertEquals(mapOf("INBOX" to 1, SENT to 1), vm.state.value.cursors)

        vm.loadMoreIfNeeded(vm.state.value.messages.last())
        assertEquals(
            listOf("INBOX" to 2L, SENT to 10L, SENT to 9L, "INBOX" to 1L),
            vm.state.value.messages.map { it.folder to it.uid },
        )
        // Both folders are exhausted, so the list is genuinely at its end.
        assertTrue(vm.state.value.cursors.isEmpty())
    }

    @Test
    fun `a folder that runs out first does not end the merged list while the other still has mail`() {
        repo.pageSize = 1
        repo.add("INBOX", mailSummary(1, sentAt = at(100)), mailSummary(2, sentAt = at(300)))
        repo.add(SENT, mailSummary(9, sentAt = at(200)))
        vm.load()
        selectAll()

        assertEquals(mapOf("INBOX" to 1), vm.state.value.cursors)
        vm.loadMoreIfNeeded(vm.state.value.messages.last())
        assertEquals(listOf(2L, 9L, 1L), vm.state.value.messages.map { it.uid })
        assertTrue(vm.state.value.cursors.isEmpty())
    }

    @Test
    fun `searching 所有信件 covers both folders and still reports a local-only fallback`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考通知", sentAt = at(200)), mailSummary(2, subject = "other", sentAt = at(100)))
        repo.add(SENT, mailSummary(9, subject = "期中考回覆", sentAt = at(100)))
        vm.load()
        selectAll()
        vm.setSearchText("期中考")
        vm.submitSearch()

        assertEquals(listOf("INBOX" to 1L, SENT to 9L), vm.state.value.displayed.map { it.folder to it.uid })
        assertFalse(vm.state.value.searchLocalOnly)

        repo.searchUnsupported = true
        vm.submitSearch()
        assertTrue(vm.state.value.searchLocalOnly)
    }

    @Test
    fun `所有信件 keeps checking for new mail and moving the seen marker, same as 收件匣 always did`() {
        // 收件匣 is one of the folders 所有信件 merges, so the inbox is genuinely on screen while
        // viewing 所有信件 -- defaulting to it must not cost the user the new-mail check or
        // silently let a notification re-fire for mail the merged list already showed.
        repo.add("INBOX", mailSummary(1, sentAt = at(100)))
        repo.add(SENT, mailSummary(9, sentAt = at(200)))
        repo.status = FolderStatus(server.uidValidity, 2, 1, 1)
        vm.load()
        assertEquals(FolderSelection.AllMail, vm.state.value.selected)
        val statusCallsAfterLoad = repo.statusCalls

        vm.startPolling()
        repo.add("INBOX", mailSummary(2, sentAt = at(300)))
        repo.status = FolderStatus(server.uidValidity, 3, 2, 2)
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS + 1)
        main.dispatcher.scheduler.runCurrent()

        assertTrue(repo.statusCalls > statusCallsAfterLoad)
        assertEquals(3L, state.inboxSeenUidNext)
        // The merged list itself picked up the new inbox mail, same as 收件匣 always refreshed.
        assertEquals(listOf("INBOX" to 2L, SENT to 9L, "INBOX" to 1L), vm.state.value.messages.map { it.folder to it.uid })
    }

    @Test
    fun `所有信件 paints from each folder's cache before the server answers`() {
        repo.add("INBOX", mailSummary(1, sentAt = at(200)))
        repo.add(SENT, mailSummary(9, sentAt = at(100)))
        vm.load()
        selectAll()
        assertEquals(2, vm.state.value.messages.size)

        // Back to the inbox and out again: both folders are cached now, so the merge is on
        // screen before the refresh lands -- and the refresh is still one page per folder.
        vm.selectFolder(FolderSelection.Real("INBOX"))
        repo.loadError = MailError.Network()
        selectAll()
        assertEquals(listOf("INBOX" to 1L, SENT to 9L), vm.state.value.messages.map { it.folder to it.uid })
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
    }

    private companion object {
        const val SENT = "寄件備份匣"
    }
}
