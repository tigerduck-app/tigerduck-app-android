@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.ui.screen.mail

import org.ntust.app.tigerduck.mail.index.MailIndexProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import org.ntust.app.tigerduck.mail.SentCopyNotice
import org.ntust.app.tigerduck.mail.SentCopyOrigin
import org.ntust.app.tigerduck.mail.SentCopyNotices
import org.ntust.app.tigerduck.mail.signInDuringRefusal
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.mail.RecordingNotifier
import org.ntust.app.tigerduck.mail.RecordingScheduler
import org.ntust.app.tigerduck.mail.cancelAndJoinBlocking
import org.ntust.app.tigerduck.mail.compose.OutgoingMail
import org.ntust.app.tigerduck.mail.imap.FolderSelection
import org.ntust.app.tigerduck.mail.imap.ResolvedFolders
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.mailSummary
import org.ntust.app.tigerduck.mail.model.FolderStatus
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.model.MailPage
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.sync.CheckOutcome
import org.ntust.app.tigerduck.mail.sync.CheckSource
import org.ntust.app.tigerduck.mail.sync.MailChecker
import org.ntust.app.tigerduck.mail.testApplicationScope
import java.time.Instant
import org.ntust.app.tigerduck.mail.schoolMailSite

class SchoolMailAllMailFoldersTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeMailServer()
    private val state = InMemoryMailStateStore()
    private val repo = FakeSchoolMailRepository()
    private lateinit var account: MailAccount
    private lateinit var checker: MailChecker
    private lateinit var vm: SchoolMailListViewModel
    private lateinit var notices: SentCopyNotices

    // Where the sign-out test's cache wipe runs, stopped before TemporaryFolder deletes its folder.
    private val appScope = testApplicationScope()

    @After
    fun stopAppScope() = appScope.cancelAndJoinBlocking()

    /** The checker's clock, which dates the inbox for the "Last synced" row. */
    private var now = 0L

    @Before
    fun setUp() {
        account = MailAccount(InMemoryCredentialStore(), state, server.factory(), MailCache(tmp.root),
            FakeDemoGate(), schoolMailSite(), RecordingScheduler(), RecordingNotifier(), appScope)
        runBlocking { account.signIn("b10000001", "pw") }
        notices = SentCopyNotices(account, CoroutineScope(main.dispatcher))
        checker = MailChecker(account, state, server.factory(), RecordingNotifier(), MailCache(tmp.root)) { now }
        // What a write is told under, as the real repository reads it: the list takes only the
        // signed-in account's own.
        repo.accountGeneration = { account.accountGeneration }
        vm = SchoolMailListViewModel(
            repo, account, checker, schoolMailSite(),
            { main.dispatcher.scheduler.currentTime }, notices,
        )
    }

    private fun at(millis: Long) = Instant.ofEpochMilli(millis)

    private fun seed() {
        repo.add("INBOX", mailSummary(1, subject = "期中考 inbox", sentAt = at(500)))
        repo.add(SENT, mailSummary(2, subject = "期中考 sent", sentAt = at(400)))
        repo.add(DRAFTS, mailSummary(3, subject = "期中考 draft", sentAt = at(300)))
        repo.add(USER, mailSummary(4, subject = "期中考 moodle", sentAt = at(200)))
        repo.add(JUNK, mailSummary(5, subject = "期中考 junk", sentAt = at(900)))
        repo.add(TRASH, mailSummary(6, subject = "期中考 trash", sentAt = at(900)))
    }

    private fun rows() = vm.state.value.displayed.map { it.folder to it.uid }

    @Test
    fun `All mail lists every folder but Junk and Trash, newest first`() {
        seed()
        vm.load()
        assertEquals(FolderSelection.AllMail, vm.state.value.selected)
        assertEquals(listOf("INBOX", SENT, DRAFTS, USER), vm.state.value.targets)
        assertEquals(listOf("INBOX" to 1L, SENT to 2L, DRAFTS to 3L, USER to 4L), rows())
    }

    @Test
    fun `All mail earns its chip when the only other folder is the student's own`() {
        repo.resolved = ResolvedFolders(mapOf(SpecialFolder.INBOX to "INBOX"), listOf(USER))
        repo.add("INBOX", mailSummary(1, sentAt = at(100)))
        repo.add(USER, mailSummary(2, sentAt = at(200)))
        vm.load()
        assertTrue(vm.state.value.chips.any { it.selection == FolderSelection.AllMail })
        assertEquals(listOf(USER to 2L, "INBOX" to 1L), rows())
    }

    @Test
    fun `All mail search asks every folder it covers and no other`() {
        seed()
        vm.load()
        vm.setSearchText("期中")
        vm.submitSearch()
        assertEquals(listOf("INBOX", SENT, DRAFTS, USER), repo.searchesSent)
        assertEquals(listOf("INBOX" to 1L, SENT to 2L, DRAFTS to 3L, USER to 4L), rows())
    }

    @Test
    fun `All mail pages back through a student's own folder`() {
        repo.pageSize = 1
        repo.add("INBOX", mailSummary(1, sentAt = at(500)))
        repo.add(USER, mailSummary(4, sentAt = at(100)), mailSummary(3, sentAt = at(50)))
        vm.load()
        assertEquals(listOf("INBOX" to 1L, USER to 4L), rows())
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        assertEquals(listOf("INBOX" to 1L, USER to 4L, USER to 3L), rows())
    }

    @Test
    fun `one UID in three folders is three rows with their own folders`() {
        repo.add("INBOX", mailSummary(7, sentAt = at(300)))
        repo.add(DRAFTS, mailSummary(7, sentAt = at(200)))
        repo.add(USER, mailSummary(7, sentAt = at(100)))
        vm.load()
        assertEquals(listOf("INBOX" to 7L, DRAFTS to 7L, USER to 7L), rows())
        assertEquals(3, vm.state.value.displayed.map { it.key }.toSet().size)
    }

    @Test
    fun `a Drafts row on All mail is a draft, and a student's folder row is not`() {
        seed()
        vm.load()
        assertEquals(SpecialFolder.DRAFTS, vm.state.value.kindOf(DRAFTS))
        assertNull(vm.state.value.kindOf(USER))
    }

    @Test
    fun `marking a Drafts or student's folder row addresses that folder`() {
        seed()
        vm.load()
        vm.toggleRead(vm.state.value.displayed.single { it.folder == DRAFTS })
        vm.toggleRead(vm.state.value.displayed.single { it.folder == USER })
        assertEquals(listOf(Triple(DRAFTS, 3L, true), Triple(USER, 4L, true)), repo.seenCalls)
    }

    @Test
    fun `deleting from All mail follows the row's own folder`() {
        seed()
        vm.load()
        for (folder in listOf(DRAFTS, USER)) {
            val row = vm.state.value.displayed.single { it.folder == folder }
            vm.delete(row)
            // Both move to the trash, as they do from their own chip.
            assertEquals(SchoolMailListViewModel.PendingDelete(row, permanently = false, account.accountGeneration), vm.state.value.pendingDelete)
            vm.confirmDelete(true)
        }
        assertEquals(listOf(DRAFTS to 3L, USER to 4L), repo.deleted)
        assertEquals(listOf("INBOX" to 1L, SENT to 2L), rows())
    }

    @Test
    fun `moving a student's folder row offers every folder but its own`() {
        seed()
        vm.load()
        val row = vm.state.value.displayed.single { it.folder == USER }
        vm.requestMove(row)
        val offered = vm.state.value.pendingMove!!.targets.map { it.folder }
        assertFalse(USER in offered)
        assertTrue("INBOX" in offered && DRAFTS in offered && TRASH in offered && JUNK in offered)
        vm.moveTo("INBOX")
        assertEquals(listOf(Triple(USER, 4L, "INBOX")), repo.moved)
    }

    @Test
    fun `Junk and Trash are never among All mail's folders, listed or searched`() {
        seed()
        vm.load()
        vm.setSearchText("期中")
        vm.submitSearch()
        assertFalse(vm.state.value.targets.any { it == JUNK || it == TRASH })
        assertFalse(repo.searchesSent.any { it == JUNK || it == TRASH })
    }

    private companion object {
        const val SENT = "寄件備份匣"
        const val DRAFTS = "草稿匣"
        const val JUNK = "廣告信匣"
        const val TRASH = "回收筒"
        const val USER = "Moodle 課程討論區"
    }
}
