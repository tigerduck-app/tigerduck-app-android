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

class SchoolMailListViewModelTest {
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
        // All mail is the one chip with no SpecialFolder behind it -- there is no such server folder.
        assertNull(s.chips.single { it.selection == FolderSelection.AllMail }.kind)
        // All mail leads the row, and it is also what the screen opens on.
        assertEquals(FolderSelection.AllMail, s.selected)
        assertEquals(listOf("Moodle 課程討論區"), s.others)
        assertEquals(listOf(3L, 2L, 1L), s.displayed.map { it.uid })
        assertTrue(s.loadState is SchoolMailListViewModel.LoadState.Loaded)
        // The inbox is part of All mail's merge, so opening on it still moves the seen marker --
        // defaulting to All mail must not cost the user the new-mail check.
        assertEquals(4L, state.inboxSeenUidNext)
    }

    @Test
    fun `when only one of Inbox or Sent resolves, there is nothing to merge and the default stays Inbox`() {
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

    /** A pull is the user asking for the mail as it is now: mail for search downloads at once (#586). */
    @Test
    fun `a pull to refresh asks for mail to be downloaded for search at once`() {
        repo.add("INBOX", mailSummary(1))
        vm.load()

        vm.refresh()

        assertEquals(1, repo.downloadsAsked)
    }

    /** A pull after the folder list failed starts over from it, and still asks for mail for search. */
    @Test
    fun `a pull with no folders yet still asks for mail to be downloaded for search`() {
        vm.refresh()

        assertEquals(1, repo.downloadsAsked)
    }

    /** The download's end runs the search once more, over the last batch it brought. */
    @Test
    fun `a search on screen runs once more when the download ends`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考"))
        vm.load()
        vm.setSearchText("期中")
        vm.submitSearch()
        repo.searchIndexProgress.value = MailIndexProgress(1, 2)
        main.dispatcher.scheduler.runCurrent()
        val searched = repo.searchCalls

        repo.add("INBOX", mailSummary(2, subject = "期中考 補考"))
        repo.searchIndexProgress.value = null
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.SEARCH_REGROW_MS + 1)
        main.dispatcher.scheduler.runCurrent()

        assertTrue(repo.searchCalls > searched)
        assertEquals(listOf(2L, 1L), vm.state.value.displayed.map { it.uid })
    }

    /** A query being edited is not run before it is submitted: the search on screen is the one submitted. */
    @Test
    fun `a query being edited is not run as mail downloads`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考"))
        vm.load()
        vm.setSearchText("期中")
        vm.submitSearch()
        val searched = repo.searchCalls

        vm.setSearchText("期末")
        repo.searchIndexProgress.value = MailIndexProgress(1, 10)
        main.dispatcher.scheduler.runCurrent()

        assertEquals(searched, repo.searchCalls)
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })
    }

    /** Pages of the search the user scrolled to stay: run again, it would show only the first. */
    @Test
    fun `a search scrolled past its first page is not run again as mail downloads`() {
        repo.pageSize = 2
        repo.add("INBOX", *(1L..5L).map { mailSummary(it, subject = "期中考 $it") }.toTypedArray())
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.setSearchText("期中")
        vm.submitSearch()
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        val shown = vm.state.value.displayed.map { it.uid }
        val searched = repo.searchCalls

        repo.searchIndexProgress.value = MailIndexProgress(1, 10)
        main.dispatcher.scheduler.runCurrent()

        assertEquals(searched, repo.searchCalls)
        assertEquals(shown, vm.state.value.displayed.map { it.uid })
    }

    /**
     * While mail downloads for search, a search on screen runs again as it comes in, at most every
     * [SchoolMailListViewModel.SEARCH_REGROW_MS], its results staying on screen meanwhile, so they
     * grow rather than wait for a new search (#586).
     */
    @Test
    fun `a search on screen grows as mail for it downloads, at most every few seconds`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考"))
        vm.load()
        vm.setSearchText("期中")
        vm.submitSearch()
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })
        val searched = repo.searchCalls

        // Downloaded meanwhile, and now found by the search.
        repo.add("INBOX", mailSummary(2, subject = "期中考 補考"))
        val held = CompletableDeferred<Unit>().also { repo.searchGate = it }
        repo.searchIndexProgress.value = MailIndexProgress(1, 10)
        main.dispatcher.scheduler.runCurrent()
        assertTrue("it ran again", repo.searchCalls > searched)
        assertEquals("the results stay while it runs", listOf(1L), vm.state.value.displayed.map { it.uid })
        assertFalse(vm.state.value.isSearching)
        repo.searchGate = null
        held.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(listOf(2L, 1L), vm.state.value.displayed.map { it.uid })

        // More mail within the few seconds: not yet.
        val again = repo.searchCalls
        repo.searchIndexProgress.value = MailIndexProgress(2, 10)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(again, repo.searchCalls)
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.SEARCH_REGROW_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        assertTrue(repo.searchCalls > again)
    }

    @Test
    fun `a search the server fails keeps the list and shows the loaded matches`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考"), mailSummary(2, subject = "other"))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        repo.searchError = MailError.Network()
        vm.setSearchText("期中")
        vm.submitSearch()
        val s = vm.state.value
        assertTrue(s.loadState is SchoolMailListViewModel.LoadState.Loaded)
        assertEquals(listOf(1L), s.displayed.map { it.uid })
        assertTrue(s.searchLocalOnly)
        assertTrue(s.actionError is MailError.Network)
    }

    @Test
    fun `a password the server rejects during a search is not sent again for the next folder`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考"))
        repo.add("寄件備份匣", mailSummary(2, subject = "期中考 reply"))
        vm.load()
        repo.foldersTouched.clear()
        repo.searchError = MailError.AuthFailed()
        vm.setSearchText("期中")
        vm.submitSearch()
        // Spec §7.4: the rejection reaches the account before Sent is searched, so no second LOGIN.
        assertEquals(listOf("INBOX"), repo.foldersTouched)
        assertTrue(account.authFailed.value)
        // Both folders still show their matches among the loaded mail.
        assertEquals(setOf(1L, 2L), vm.state.value.displayed.map { it.uid }.toSet())
        assertTrue(vm.state.value.searchLocalOnly)
    }

    @Test
    fun `a search that cannot reach the server stops trying folder after folder`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考"))
        repo.add("寄件備份匣", mailSummary(2, subject = "期中考 reply"))
        vm.load()
        repo.foldersTouched.clear()
        repo.searchError = MailError.Network()
        vm.setSearchText("期中")
        vm.submitSearch()
        assertEquals(listOf("INBOX"), repo.foldersTouched)
        assertEquals(setOf(1L, 2L), vm.state.value.displayed.map { it.uid }.toSet())
        assertTrue(vm.state.value.actionError is MailError.Network)
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loaded)
    }

    /**
     * All mail, with every match read: under unread only the first page shows nothing, which is the
     * one case where a search fetches another page by itself. The inbox answers, and then [error]
     * ends the server's part of the search at Sent.
     */
    private fun searchWhoseServerIsLostAfterTheInbox(error: MailError) {
        repo.pageSize = 2
        repo.add("INBOX", *(1L..5L).map { mailSummary(it, subject = "期中考 $it", seen = true) }.toTypedArray())
        repo.add(SENT, mailSummary(9, subject = "期中考 reply", seen = true))
        vm.load()
        vm.setUnreadOnly(true)
        repo.searchError = error
        repo.searchErrorFolder = SENT
        vm.setSearchText("期中")
        vm.submitSearch()
        assertTrue(vm.state.value.displayed.isEmpty())
        assertTrue(vm.state.value.searchLocalOnly)
        assertFalse(vm.state.value.isPaginating)
    }

    @Test
    fun `a search that lost the connection fetches no more results on its own, even with nothing on screen`() {
        searchWhoseServerIsLostAfterTheInbox(MailError.Network())
        assertTrue(vm.state.value.actionError is MailError.Network)
        // The inbox's remaining matches are left alone: fetching a page of them would open the
        // connection again right after the search decided to stop trying it.
        assertTrue(repo.searchPages.isEmpty())
    }

    @Test
    fun `a search the password was rejected during fetches no more results on its own`() {
        searchWhoseServerIsLostAfterTheInbox(MailError.AuthFailed())
        assertTrue(account.authFailed.value)
        // Spec §7.4: a page of results is one more LOGIN with the rejected password.
        assertTrue(repo.searchPages.isEmpty())
    }

    @Test
    fun `a certificate failure during a search stays on screen, as it does for the poll`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考"))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        repo.searchError = MailError.Certificate()
        vm.setSearchText("期中")
        vm.submitSearch()
        val s = vm.state.value
        assertTrue((s.loadState as? SchoolMailListViewModel.LoadState.Failed)?.error is MailError.Certificate)
        // The list it searched is still there, with the loaded matches.
        assertEquals(listOf(1L), s.displayed.map { it.uid })
    }

    @Test
    fun `a certificate failure while paging search results stays on screen`() {
        repo.pageSize = 2
        repo.add("INBOX", *(1L..5L).map { mailSummary(it, subject = "期中考 $it") }.toTypedArray())
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.setSearchText("期中")
        vm.submitSearch()
        repo.searchPageError = MailError.Certificate()
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        assertTrue((vm.state.value.loadState as? SchoolMailListViewModel.LoadState.Failed)?.error is MailError.Certificate)
    }

    @Test
    fun `a page of search results that fails is asked for once, not again and again`() {
        repo.pageSize = 2
        repo.add("INBOX", *(1L..9L).map { mailSummary(it, subject = "期中考 $it") }.toTypedArray())
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.setSearchText("期中")
        vm.submitSearch()
        repo.searchPageError = MailError.ServerBusy()
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        assertEquals(1, repo.searchPages.size)
        assertTrue(vm.state.value.actionError is MailError.ServerBusy)
        assertFalse(vm.state.value.isPaginating)
        // The page stays unfetched: the next scroll to the end asks for the same page again.
        repo.searchPageError = null
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        assertEquals(2, repo.searchPages.size)
        assertEquals(repo.searchPages[0], repo.searchPages[1])
    }

    @Test
    fun `search results keep paging past the first page as the list scrolls`() {
        repo.pageSize = 2
        repo.add("INBOX", *(1L..5L).map { mailSummary(it, subject = "期中考 $it") }.toTypedArray(), mailSummary(6, subject = "other"))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.setSearchText("期中")
        vm.submitSearch()
        assertEquals(listOf(5L, 4L), vm.state.value.displayed.map { it.uid })
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), vm.state.value.displayed.map { it.uid })
        assertEquals(listOf("INBOX" to listOf(3L, 2L, 1L)), repo.searchPages)
        // The matches have run out; scrolling to the end again asks for nothing.
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        assertEquals(1, repo.searchPages.size)
    }

    @Test
    fun `a search whose first page shows nothing under unread only fetches on until something shows`() {
        repo.pageSize = 2
        repo.add("INBOX", mailSummary(1, subject = "期中考 1"), mailSummary(2, subject = "期中考 2", seen = true), mailSummary(3, subject = "期中考 3", seen = true))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.setUnreadOnly(true)
        vm.setSearchText("期中")
        vm.submitSearch()
        // No row on screen means no last row to scroll to, so the search cannot wait for one.
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `search results from a recreated folder are dropped, the list reloads and the search runs again`() {
        repo.pageSize = 2
        repo.add("INBOX", *(1L..5L).map { mailSummary(it, subject = "期中考 $it") }.toTypedArray())
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.setSearchText("期中")
        vm.submitSearch()
        val firstPages = repo.pageLimits.size
        val searches = repo.searchCalls
        repo.searchPageValidity = 2
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        // The matches were found under another UIDVALIDITY: none of them can be trusted now, so
        // the page is not added, the list reloads, and the query runs again over the recreated
        // folder. What is on screen is the new search's answer -- not an empty result left over
        // a list that reloaded perfectly well, which is all a search of this one folder showed.
        assertEquals(firstPages + 1, repo.pageLimits.size)
        assertEquals(1, repo.searchPages.size)
        assertEquals(searches + 1, repo.searchCalls)
        assertEquals(listOf(5L, 4L), vm.state.value.displayed.map { it.uid })
        assertEquals("期中", vm.state.value.searchText)
        assertFalse(vm.state.value.isSearching)
        assertFalse(vm.state.value.isPaginating)
    }

    @Test
    fun `a recreated folder whose reload fails shows none of its old rows, not even as loaded matches`() {
        repo.pageSize = 2
        repo.add("INBOX", *(1L..5L).map { mailSummary(it, subject = "期中考 $it") }.toTypedArray())
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.setSearchText("期中")
        vm.submitSearch()
        val searches = repo.searchCalls
        // The folder was recreated, and the connection goes before the list can reload from it.
        repo.searchPageValidity = 2
        repo.loadError = MailError.Network()
        repo.searchError = MailError.Network()
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        // The rows the list held were read under the old UIDVALIDITY. A search run again now would
        // fail too and fall back to the loaded mail -- which must not be those rows, so it is not
        // run, and the list keeps nothing of the old mailbox for a later one to fall back to.
        assertEquals(emptyList<Long>(), vm.state.value.displayed.map { it.uid })
        assertEquals(searches, repo.searchCalls)
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
        vm.setSearchText("")
        assertEquals(emptyList<Long>(), vm.state.value.displayed.map { it.uid })
        vm.setSearchText("期中")
        vm.submitSearch()
        assertEquals(emptyList<Long>(), vm.state.value.displayed.map { it.uid })
        assertFalse(vm.state.value.isSearching)
        assertFalse(vm.state.value.isPaginating)
    }

    @Test
    fun `a recreated folder whose reload fails is not painted from its cache again`() {
        repo.pageSize = 2
        repo.add("INBOX", *(1L..5L).map { mailSummary(it, subject = "期中考 $it") }.toTypedArray())
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.setSearchText("期中")
        vm.submitSearch()
        repo.searchPageValidity = 2
        repo.loadError = MailError.Network()
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        assertTrue(vm.state.value.messages.isEmpty())
        // Away and back while the server stays unreachable. The folder's cache still held the old
        // mailbox's page, and painting it again would put the rows -- and a cursor into them --
        // back for the next scroll and the next failing search to read.
        vm.setSearchText("")
        vm.selectFolder(FolderSelection.Real("回收筒"))
        vm.selectFolder(FolderSelection.Real("INBOX"))
        assertEquals(emptyList<Long>(), vm.state.value.messages.map { it.uid })
        assertTrue(vm.state.value.cursors.isEmpty())
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
        repo.searchError = MailError.Network()
        vm.setSearchText("期中")
        vm.submitSearch()
        assertEquals(emptyList<Long>(), vm.state.value.displayed.map { it.uid })
        // Once the server answers again, the folder loads afresh.
        repo.loadError = null
        vm.setSearchText("")
        vm.refresh()
        assertEquals(listOf(5L, 4L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `a recreated folder's failed reload leaves a cache that already holds the new mailbox alone`() {
        repo.pageSize = 2
        repo.add("INBOX", mailSummary(1, sentAt = at(100)), mailSummary(2, sentAt = at(200)), mailSummary(3, sentAt = at(300)), mailSummary(4, sentAt = at(400)))
        repo.add(SENT, mailSummary(9, sentAt = at(50)))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        // The inbox was recreated. A refresh of All mail caches its page under the new UIDVALIDITY
        // but fails at Sent, so the rows on screen -- and the cursor into them -- stay the old
        // mailbox's.
        repo.pageValidity = 2
        repo.loadError = MailError.Network()
        repo.loadErrorFolder = SENT
        selectAll()
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
        assertEquals(2L, runBlocking { repo.cachedPage("INBOX") }!!.uidValidity)
        assertEquals(mapOf("INBOX" to 2), vm.state.value.cursors)
        // The older page comes down under the new validity, and the connection goes right after.
        repo.olderPage = { _, _ ->
            repo.loadErrorFolder = null
            MailPage(2, 4, listOf(mailSummary(2, sentAt = at(200)), mailSummary(1, sentAt = at(100))), null)
        }
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        // The old rows go, as they must. The page cached under the new validity is the only
        // offline copy of the new mailbox there is, and it is not what was found obsolete: it stays.
        assertTrue(vm.state.value.messages.none { it.folder == "INBOX" })
        assertEquals(2L, runBlocking { repo.cachedPage("INBOX") }!!.uidValidity)
        // So the inbox still paints from it while the server is away.
        vm.selectFolder(FolderSelection.Real("INBOX"))
        assertEquals(listOf(4L, 3L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `a recreated folder's reload starts no request for a folder the user moved to meanwhile`() {
        repo.pageSize = 2
        repo.add("INBOX", mailSummary(1), mailSummary(2), mailSummary(3), mailSummary(4))
        repo.add("回收筒", mailSummary(7))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        repo.olderPage = { _, _ -> MailPage(2, 4, listOf(mailSummary(9), mailSummary(8)), null) }
        // The user taps another chip while the recreated inbox's cached page is being dropped -- a
        // disk write the request suspends on. That chip's own load is the only one to run.
        repo.onDropCachedPage = {
            repo.onDropCachedPage = null
            vm.selectFolder(FolderSelection.Real("回收筒"))
        }
        val sent = repo.loadsSent.size
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        // The older page that found the inbox recreated, then the chip's load, and nothing more.
        assertEquals(listOf("INBOX", "回收筒"), repo.loadsSent.drop(sent))
        assertEquals(listOf(7L), vm.state.value.displayed.map { it.uid })
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loaded)
    }

    @Test
    fun `a folder list that returns after another student signed in over this one installs nothing and loads nothing`() {
        repo.add("INBOX", mailSummary(1))
        // Sent, and still out when the other student signs in: no sign-out, so nothing resets the screen.
        repo.onFolders = {
            repo.onFolders = null
            signInAnotherOverThisOne()
        }
        vm.load()
        assertEquals(emptyList<SchoolMailListViewModel.FolderChip>(), vm.state.value.chips)
        assertEquals(emptyList<String>(), repo.loadsSent)
        // The loading it announced is over; whoever is signed in now starts their own.
        assertFalse(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loading)
    }

    @Test
    fun `a student who signs in over another while that one's folder list is out gets their own load`() {
        repo.add("INBOX", mailSummary(1))
        val answer = CompletableDeferred<Unit>()
        repo.foldersAnswerGate = answer
        vm.load()
        assertEquals(1, repo.foldersSent)
        // B signs in over A through the sign-in sheet, and B's screen asks for its load at once --
        // while A's folder list is still out, so the single flight refuses it.
        signInAnotherOverThisOne()
        vm.load()
        repo.foldersAnswerGate = null
        answer.complete(Unit)
        // A's list came back for nobody, and B's own load ran once it had: B's folders and inbox
        // are on screen, not a blank page waiting for a pull.
        assertEquals(2, repo.foldersSent)
        assertTrue(vm.state.value.chips.isNotEmpty())
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loaded)
    }

    @Test
    fun `a search run before every mail was downloaded says so until it runs again`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考"), mailSummary(2, subject = "other"))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        repo.searchComplete = false
        vm.setSearchText("期中")

        vm.submitSearch()
        assertTrue(vm.state.value.searchIndexPartial)
        assertFalse(vm.state.value.searchLocalOnly)

        // The download gets through meanwhile: the results on screen are still the partial search's.
        repo.searchIndexProgress.value = null
        assertTrue(vm.state.value.searchIndexPartial)

        repo.searchComplete = true
        vm.submitSearch()
        assertFalse(vm.state.value.searchIndexPartial)
    }

    @Test
    fun `clearing the search clears its note that it may have missed mail`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考"))
        vm.load()
        repo.searchComplete = false
        vm.setSearchText("期中")
        vm.submitSearch()
        assertTrue(vm.state.value.searchIndexPartial)

        vm.setSearchText("")

        assertFalse(vm.state.value.searchIndexPartial)
    }

    // A rejection is marked in one step with a sign-in's move of the generation: the student
    // signing in again while the list marks it keeps the sign-in the server just accepted.

    @Test
    fun `a page refused as the student signs in again leaves the new sign-in on`() {
        repo.add("INBOX", mailSummary(1))
        repo.loadError = MailError.AuthFailed()
        val signIn = signInDuringRefusal(account, state, "b10000001", "pw")

        vm.load()
        signIn.join()

        assertFalse(account.authFailed.value)
    }

    @Test
    fun `a mark-read refused as the student signs in again leaves the new sign-in on`() {
        repo.add("INBOX", mailSummary(1))
        vm.load()
        repo.seenError = MailError.AuthFailed()
        val signIn = signInDuringRefusal(account, state, "b10000001", "pw")

        vm.toggleRead(vm.state.value.displayed.single())
        signIn.join()

        assertFalse(account.authFailed.value)
    }

    @Test
    fun `a search refused as the student signs in again leaves the new sign-in on`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考"))
        vm.load()
        repo.searchError = MailError.AuthFailed()
        val signIn = signInDuringRefusal(account, state, "b10000001", "pw")
        vm.setSearchText("期中")

        vm.submitSearch()
        signIn.join()

        assertFalse(account.authFailed.value)
    }

    @Test
    fun `a search under way shows no mail until its matches come`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考"), mailSummary(2, subject = "other"))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        assertEquals(2, vm.state.value.displayed.size)
        val gate = CompletableDeferred<Unit>()
        repo.searchGate = gate
        vm.setSearchText("期中")

        vm.submitSearch()
        assertEquals(0, vm.state.value.displayed.size)

        repo.searchGate = null
        gate.complete(Unit)
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `the search field spins while a further page of matches is on its way`() {
        repo.pageSize = 2
        repo.add("INBOX", *(1L..120L).map { mailSummary(it, subject = "期中考 $it") }.toTypedArray())
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.setSearchText("期中")
        vm.submitSearch()
        assertFalse(vm.state.value.searchBusy)
        val gate = CompletableDeferred<Unit>()
        repo.searchPageGate = gate

        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        assertTrue(vm.state.value.searchBusy)

        repo.searchPageGate = null
        gate.complete(Unit)
        assertFalse(vm.state.value.searchBusy)
    }

    @Test
    fun `a page of an old search still on its way leaves the newer search's field still once it ends`() {
        repo.pageSize = 2
        repo.add("INBOX", *(1L..120L).map { mailSummary(it, subject = "期中考 $it") }.toTypedArray())
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.setSearchText("期中")
        vm.submitSearch()
        val gate = CompletableDeferred<Unit>()
        repo.searchPageGate = gate
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())

        repo.searchPageGate = null
        vm.setSearchText("考 1")
        vm.submitSearch()
        assertFalse("the newer search has ended", vm.state.value.searchBusy)

        gate.complete(Unit)
        assertFalse(vm.state.value.searchBusy)
    }

    @Test
    fun `the list paging back on its own leaves the search field still`() {
        val paging = SchoolMailListViewModel.UiState(isPaginating = true)
        assertFalse(paging.searchBusy)
    }

    @Test
    fun `a search overtaken by a newer one does not stop the newer one's spinner`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考"))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        val first = CompletableDeferred<Unit>()
        repo.searchGate = first
        vm.setSearchText("期中")
        vm.submitSearch()
        val second = CompletableDeferred<Unit>()
        repo.searchGate = second
        vm.setSearchText("考")
        vm.submitSearch()

        first.complete(Unit)
        assertTrue("the newer search is still out", vm.state.value.searchBusy)

        repo.searchGate = null
        second.complete(Unit)
        assertFalse(vm.state.value.searchBusy)
    }

    @Test
    fun `clearing a search still out stops its spinner at once`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考"))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        val gate = CompletableDeferred<Unit>()
        repo.searchGate = gate
        vm.setSearchText("期中")
        vm.submitSearch()
        assertTrue(vm.state.value.searchBusy)

        vm.setSearchText("")
        assertFalse(vm.state.value.searchBusy)

        repo.searchGate = null
        gate.complete(Unit)
        assertFalse(vm.state.value.searchBusy)
    }

    @Test
    fun `another folder picked while a search is out stops its spinner`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考"))
        repo.add(SENT, mailSummary(2))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        val gate = CompletableDeferred<Unit>()
        repo.searchGate = gate
        vm.setSearchText("期中")
        vm.submitSearch()

        vm.selectFolder(FolderSelection.Real(SENT))
        assertFalse(vm.state.value.searchBusy)

        repo.searchGate = null
        gate.complete(Unit)
        assertFalse(vm.state.value.searchBusy)
    }

    @Test
    fun `a certificate failure of a search the user has cleared still stays on screen`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考"))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        val gate = CompletableDeferred<Unit>()
        repo.searchGate = gate
        repo.searchError = MailError.Certificate()
        vm.setSearchText("期中")
        vm.submitSearch()
        // Cleared while the SEARCH is out; it then fails because the connection could not be
        // trusted -- which is this account's to know, whatever became of the search.
        vm.setSearchText("")
        repo.searchGate = null
        gate.complete(Unit)
        assertTrue((vm.state.value.loadState as? SchoolMailListViewModel.LoadState.Failed)?.error is MailError.Certificate)
        assertNull(vm.state.value.searchResults)
    }

    @Test
    fun `a password rejected during a search the user moved away from still reaches the account and the screen`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考"))
        repo.add(SENT, mailSummary(2))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        val gate = CompletableDeferred<Unit>()
        repo.searchGate = gate
        repo.searchError = MailError.AuthFailed()
        vm.setSearchText("期中")
        vm.submitSearch()
        vm.selectFolder(FolderSelection.Real(SENT))
        repo.searchGate = null
        gate.complete(Unit)
        assertTrue(account.authFailed.value)
        assertTrue(vm.state.value.actionError is MailError.AuthFailed)
    }

    @Test
    fun `a server that cannot search matches the mail already loaded, pages back included`() {
        repo.pageSize = 2
        repo.add("INBOX", *(1L..5L).map { mailSummary(it, subject = "期中考 $it") }.toTypedArray())
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        assertEquals(listOf(5L, 4L, 3L, 2L), vm.state.value.displayed.map { it.uid })
        // The repository keeps only the folder's newest page; the list has paged further, and
        // every match it has loaded shows, not just the newest page's.
        repo.searchUnsupported = true
        vm.setSearchText("期中")
        vm.submitSearch()
        assertEquals(listOf(5L, 4L, 3L, 2L), vm.state.value.displayed.map { it.uid })
        assertTrue(vm.state.value.searchLocalOnly)
    }

    @Test
    fun `a server that cannot search does not match rows of a mailbox generation the server has replaced`() {
        repo.pageSize = 2
        repo.add("INBOX", mailSummary(1, subject = "期中考 1", sentAt = at(100)), mailSummary(2, subject = "期中考 2", sentAt = at(200)))
        repo.add(SENT, mailSummary(9, subject = "other", sentAt = at(50)))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        // The inbox was recreated, with one mail that reads differently under the same UID. A
        // refresh of All mail caches its page under the new UIDVALIDITY but fails at Sent, so the
        // rows on screen stay the old mailbox's.
        repo.mail.getValue("INBOX").clear()
        repo.add("INBOX", mailSummary(1, subject = "期中考 新", sentAt = at(300)))
        repo.pageValidity = 2
        repo.loadError = MailError.Network()
        repo.loadErrorFolder = SENT
        selectAll()
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
        assertEquals(listOf(2L, 1L, 9L), vm.state.value.displayed.map { it.uid })
        assertEquals(2L, runBlocking { repo.cachedPage("INBOX") }!!.uidValidity)
        // The server cannot search. The repository matched the new mailbox's cached page; the
        // inbox rows on screen were read under the old UIDVALIDITY and must not stand in for it.
        repo.searchUnsupported = true
        vm.setSearchText("期中")
        vm.submitSearch()
        assertEquals(listOf(1L to "期中考 新"), vm.state.value.displayed.map { it.uid to it.summary.subject })
        assertTrue(vm.state.value.searchLocalOnly)
    }

    @Test
    fun `a scroll to the end fetches a few pages of search results at most`() {
        repo.pageSize = 2
        // Under unread only every match is read but the oldest, so page after page adds nothing.
        repo.add("INBOX", mailSummary(1, subject = "期中考 1"))
        // More than four pages of them: a page of search results is MailRepository.PAGE_SIZE matches.
        repo.add("INBOX", *(2L..301L).map { mailSummary(it, subject = "期中考 $it", seen = true) }.toTypedArray())
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.setUnreadOnly(true)
        vm.setSearchText("期中")
        vm.submitSearch()
        assertEquals(1 + SchoolMailListViewModel.EMPTY_PAGE_WALKBACK, repo.searchPages.size)
        assertFalse(vm.state.value.isPaginating)
    }

    @Test
    fun `a page of results still on its way when the search is cleared is dropped, and nothing more is fetched`() {
        repo.pageSize = 2
        // More than one page of matches left (a page is MailRepository.PAGE_SIZE), so there is a next one to not fetch.
        repo.add("INBOX", *(1L..120L).map { mailSummary(it, subject = "期中考 $it") }.toTypedArray())
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.setSearchText("期中")
        vm.submitSearch()
        val gate = CompletableDeferred<Unit>()
        repo.searchPageGate = gate
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        vm.setSearchText("")
        repo.searchPageGate = null
        gate.complete(Unit)
        assertNull(vm.state.value.searchResults)
        assertEquals(1, repo.searchPages.size)
        assertFalse(vm.state.value.isPaginating)
    }

    @Test
    fun `signing out while a page of results is on its way leaves nothing of the search behind`() {
        repo.pageSize = 2
        repo.add("INBOX", *(1L..9L).map { mailSummary(it, subject = "期中考 $it") }.toTypedArray())
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.setSearchText("期中")
        vm.submitSearch()
        val gate = CompletableDeferred<Unit>()
        repo.searchPageGate = gate
        repo.searchPageError = MailError.Network()
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        account.signOut()
        repo.searchPageGate = null
        gate.complete(Unit)
        val s = vm.state.value
        assertNull(s.searchResults)
        assertTrue(s.messages.isEmpty())
        // The departed account's failure is not reported to whoever signs in next.
        assertNull(s.actionError)
        assertEquals(1, repo.searchPages.size)
    }

    @Test
    fun `clearing the search drops the matches it had left to fetch`() {
        repo.pageSize = 2
        repo.add("INBOX", *(1L..5L).map { mailSummary(it, subject = "期中考 $it") }.toTypedArray())
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.setSearchText("期中")
        vm.submitSearch()
        vm.setSearchText("")
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        assertTrue(repo.searchPages.isEmpty())
        assertNull(vm.state.value.searchResults)
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
    fun `the list keeps paging while unread only hides its last rows`() {
        repo.pageSize = 2
        repo.add("INBOX", mailSummary(1), mailSummary(2), mailSummary(3, seen = true), mailSummary(4))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.setUnreadOnly(true)
        // 3 is read, so 4 is the last row on screen while the list's own last row is 3.
        assertEquals(listOf(4L), vm.state.value.displayed.map { it.uid })
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        assertEquals(listOf(4L, 2L, 1L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `an older page updates the rows it covers and drops the ones the server no longer lists`() {
        repo.pageSize = 2
        repo.add("INBOX", mailSummary(1), mailSummary(2), mailSummary(3), mailSummary(4))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        assertEquals(listOf(4L, 3L), vm.state.value.displayed.map { it.uid })
        // Mail below the list's cursor was expunged on webmail, so the next older range reaches
        // back up over rows already listed: 4 has been read since, and 3 is gone.
        repo.olderPage = { _, _ -> MailPage(1, 3, listOf(mailSummary(4, seen = true), mailSummary(2)), null) }
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        val rows = vm.state.value.displayed
        assertEquals(listOf(4L, 2L), rows.map { it.uid })
        assertTrue(rows.first().summary.flags.seen)
    }

    @Test
    fun `an older page's range reaches past the mail it lists when that range ends in deleted mail`() {
        repo.pageSize = 2
        repo.add("INBOX", mailSummary(1), mailSummary(2), mailSummary(3), mailSummary(4))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        // The range read 2 and 3, and 3 is flagged deleted: the page lists 2 alone.
        repo.olderPage = { _, _ -> MailPage(1, 3, listOf(mailSummary(2)), null, windowUids = 2L..3L) }
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        assertEquals(listOf(4L, 2L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `an older page asked for before a refresh landed is dropped, not merged into the new list`() {
        repo.pageSize = 2
        repo.add("INBOX", mailSummary(1), mailSummary(2), mailSummary(3), mailSummary(4))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        val gate = CompletableDeferred<Unit>()
        repo.olderPageGate = gate
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        // New mail, and a refresh lands while that older page is still on its way.
        repo.add("INBOX", mailSummary(5), mailSummary(6))
        vm.refresh()
        assertEquals(listOf(6L, 5L), vm.state.value.displayed.map { it.uid })
        repo.olderPageGate = null
        gate.complete(Unit)
        // Its cursor was taken from the list the refresh replaced: merging it would skip mail.
        assertEquals(listOf(6L, 5L), vm.state.value.displayed.map { it.uid })
        assertEquals(mapOf("INBOX" to 2), vm.state.value.cursors)
        assertFalse(vm.state.value.isPaginating)
    }

    @Test
    fun `an older page from a recreated folder reloads the list instead of merging into it`() {
        repo.pageSize = 2
        repo.add("INBOX", mailSummary(1), mailSummary(2), mailSummary(3), mailSummary(4))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        val firstPages = repo.pageLimits.size
        // UIDVALIDITY 2: the UIDs on this page name other mail than the list's.
        repo.olderPage = { _, _ -> MailPage(2, 4, listOf(mailSummary(9), mailSummary(8)), null) }
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        assertEquals(listOf(4L, 3L), vm.state.value.displayed.map { it.uid })
        // The older page, then the first page again.
        assertEquals(firstPages + 2, repo.pageLimits.size)
    }

    @Test
    fun `an older page from a recreated folder whose reload fails leaves none of the old rows to page or search`() {
        repo.pageSize = 2
        repo.add("INBOX", *(1L..4L).map { mailSummary(it, subject = "期中考 $it") }.toTypedArray())
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        // The older page comes down under UIDVALIDITY 2, and the connection goes right after it,
        // so the reload from the top fails.
        repo.olderPage = { _, _ ->
            repo.loadError = MailError.Network()
            MailPage(2, 4, listOf(mailSummary(9), mailSummary(8)), null)
        }
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        // The list's rows and cursor belong to the mailbox before: with the reload failed, they
        // go rather than stay to be paged again or shown as another mailbox's mail -- and so does
        // the cached page they came from, or the next visit would paint them again.
        assertEquals(emptyList<Long>(), vm.state.value.displayed.map { it.uid })
        assertTrue(vm.state.value.cursors.isEmpty())
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
        assertNull(runBlocking { repo.cachedPage("INBOX") })
        // And a search that cannot reach the server has nothing of the old mailbox to fall back to.
        repo.searchError = MailError.Network()
        vm.setSearchText("期中")
        vm.submitSearch()
        assertEquals(emptyList<Long>(), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `a list painted from the cache whose refresh failed still checks UIDVALIDITY before paging`() {
        repo.pageSize = 2
        repo.add("INBOX", mailSummary(1), mailSummary(2), mailSummary(3), mailSummary(4))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        // The app is opened again: a new view model paints the inbox from the cache, and the
        // first refresh fails, so the cached page is all it knows about the folder.
        val reopened = SchoolMailListViewModel(
            repo, account, checker, schoolMailSite(),
            { main.dispatcher.scheduler.currentTime }, notices,
        )
        repo.loadError = MailError.Network()
        reopened.load()
        reopened.selectFolder(FolderSelection.Real("INBOX"))
        assertEquals(listOf(4L, 3L), reopened.state.value.displayed.map { it.uid })
        assertTrue(reopened.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
        // The folder was recreated meanwhile (UIDVALIDITY 2): the older page's UIDs name other
        // mail than the cached rows, so merging them would mix two mailboxes into one list.
        repo.loadError = null
        val firstPages = repo.pageLimits.size
        repo.olderPage = { _, _ -> MailPage(2, 4, listOf(mailSummary(9), mailSummary(8)), null) }
        reopened.loadMoreIfNeeded(reopened.state.value.displayed.last())
        assertEquals(listOf(4L, 3L), reopened.state.value.displayed.map { it.uid })
        // The older page, then the first page again.
        assertEquals(firstPages + 2, repo.pageLimits.size)
    }

    @Test
    fun `an older page that adds nothing on screen walks back for one that does`() {
        repo.pageSize = 2
        repo.add("INBOX", *(1L..6L).map { mailSummary(it) }.toTypedArray())
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        // The range after the first page holds only mail flagged deleted.
        repo.olderPage = { _, before -> if (before == 2) MailPage(1, 6, emptyList(), 4, windowUids = 3L..4L) else null }
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        assertEquals(listOf(6L, 5L, 2L, 1L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `walking back for something to show stops after a few pages`() {
        repo.pageSize = 2
        repo.add("INBOX", *(1L..40L).map { mailSummary(it) }.toTypedArray())
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        var asked = 0
        repo.olderPage = { _, before -> asked++; MailPage(1, 40, emptyList(), before + 2) }
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        assertEquals(1 + SchoolMailListViewModel.EMPTY_PAGE_WALKBACK, asked)
        assertFalse(vm.state.value.isPaginating)
    }

    @Test
    fun `retrying a load whose folder list failed loads the list`() {
        repo.add("INBOX", mailSummary(1))
        repo.foldersError = MailError.Network()
        vm.load()
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
        repo.foldersError = null
        // What Retry and a pull both call: with no folders resolved yet there is no page to
        // fetch, so it has to start from the folder list again.
        vm.refresh()
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loaded)
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `Retry while the folder list is still on its way starts no second load`() {
        repo.add("INBOX", mailSummary(1))
        repo.foldersError = MailError.Network()
        vm.load()
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
        repo.foldersError = null
        val gate = CompletableDeferred<Unit>()
        repo.foldersGate = gate
        vm.refresh()
        // The failed state, and its Retry, give way at once to the load.
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loading)
        vm.refresh()
        vm.load()
        assertEquals(2, repo.foldersCalls)
        repo.foldersGate = null
        gate.complete(Unit)
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loaded)
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `coming back to a list that failed to load does not look at the inbox on its own`() {
        repo.add("INBOX", mailSummary(1))
        repo.loadError = MailError.Network()
        vm.load()
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
        val calls = repo.statusCalls
        vm.startPolling()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.RESUME_POLL_GRACE_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(calls, repo.statusCalls)
        vm.stopPolling()
    }

    @Test
    fun `the sign-in-failed banner can be closed, and comes back with the next sign-in or failure`() {
        assertFalse(vm.showAuthFailedBanner.value)
        account.onAuthFailure()
        assertTrue(vm.showAuthFailedBanner.value)
        vm.dismissAuthFailedBanner()
        assertFalse(vm.showAuthFailedBanner.value)
        // Closing it changes nothing about the account.
        assertTrue(account.authFailed.value)
        runBlocking { account.signIn("b10000001", "pw") }
        assertFalse(vm.showAuthFailedBanner.value)
        account.onAuthFailure()
        assertTrue(vm.showAuthFailedBanner.value)
    }

    @Test
    fun `a rejected password marks the account and shows the failure`() {
        repo.loadError = MailError.AuthFailed()
        vm.load()
        assertTrue(account.authFailed.value)
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
    }

    @Test
    fun `the page appearing again keeps the list it loaded and fetches nothing`() {
        // The list screen is composed again on the way back from every mail, and used to load
        // as it appeared: one IMAP round trip per mail opened, against a server that caps
        // connections. The list it left behind is still here, as on the iPhone.
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })
        val loads = repo.loadsSent.size
        val lists = repo.foldersCalls
        vm.loadIfNeeded()
        assertEquals(loads, repo.loadsSent.size)
        assertEquals(lists, repo.foldersCalls)
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `a folder the repository found recreated is fetched again as the page appears`() {
        // A move/delete on the message screen that hit MailError.FolderChanged drops the
        // folder's cache in the (shared, singleton) repository and says so. The list no longer
        // reloads on every return, so this is what keeps it from showing rows of a mailbox the
        // server has replaced.
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })

        repo.dropCache("INBOX")
        repo.add("INBOX", mailSummary(2))
        vm.loadIfNeeded()
        assertEquals(listOf(2L, 1L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `another student signing in over this one gets their own load when the page appears`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        val lists = repo.foldersCalls
        signInAnotherOverThisOne()
        vm.loadIfNeeded()
        assertEquals(lists + 1, repo.foldersCalls)
    }

    /**
     * A sign-in over a rejected account never signs out, so the list's sign-out reset never ran:
     * the first student's rows stayed up, and a tap or a swipe acted on the second student's mail
     * with the first one's UIDs.
     */
    @Test
    fun `another student signing in over this one never sees this one's mail`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })
        // Before their own list lands, or when it never does.
        repo.loadError = MailError.Network()
        signInAnotherOverThisOne()
        main.dispatcher.scheduler.runCurrent()
        assertEquals(emptyList<Long>(), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `a list that failed with nothing to show is tried again as the page appears`() {
        repo.add("INBOX", mailSummary(1))
        repo.loadError = MailError.Network()
        vm.loadIfNeeded()
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
        repo.loadError = null
        vm.loadIfNeeded()
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `a mail marked read on its own screen is read on the list it comes back to, with no fetch`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        val loads = repo.loadsSent.size
        // As SchoolMailMessageViewModel does when the mail opens.
        runBlocking { repo.setSeen("INBOX", 1, true) }
        main.dispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.displayed.single().summary.flags.seen)
        vm.loadIfNeeded()
        assertEquals(loads, repo.loadsSent.size)
    }

    @Test
    fun `a mail moved or deleted on its own screen leaves the list, and a folder off screen is not fetched for it`() {
        repo.add("INBOX", mailSummary(1), mailSummary(2))
        vm.loadIfNeeded()
        val loads = repo.loadsSent.size
        runBlocking { repo.move("INBOX", 2, repo.resolved.nameOf(SpecialFolder.TRASH)!!) }
        main.dispatcher.scheduler.runCurrent()
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })
        vm.loadIfNeeded()
        assertEquals(loads, repo.loadsSent.size)
    }

    @Test
    fun `a mark-read that finishes after another student signed in over this one leaves their list alone`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        // The first student's mail screen marks a mail read, and the write waits on the connection.
        val gate = CompletableDeferred<Unit>()
        repo.seenGate = gate
        val late = CoroutineScope(main.dispatcher).launch { repo.setSeen("INBOX", 1, true) }
        assertTrue(late.isActive)
        // Another student signs in over the first, and the page loads their mail -- in which the
        // same folder and UID name another mail altogether.
        signInAnotherOverThisOne()
        vm.loadIfNeeded()
        assertFalse(vm.state.value.displayed.single().summary.flags.seen)
        repo.seenGate = null
        gate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
        assertFalse(late.isActive)
        assertFalse("the first student's write marked the second's mail read", vm.state.value.displayed.single().summary.flags.seen)
    }

    @Test
    fun `a mark-read that fails after another student signed in over this one is not theirs to see`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        // The first student's swipe marks a mail read, and the write waits on the connection.
        val gate = CompletableDeferred<Unit>()
        repo.seenGate = gate
        repo.seenError = MailError.AuthFailed()
        vm.toggleRead(vm.state.value.displayed.single())
        signInAnotherOverThisOne()
        vm.loadIfNeeded()
        repo.seenGate = null
        gate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
        assertNull(vm.state.value.actionError)
        assertFalse("the first student's rejected password locked the second out", account.authFailed.value)
    }

    /**
     * Holds the answer to [folder]'s next page on its way up, so the fetch that asked for it is
     * still out while the test writes; the page was read before the write and has none of it.
     */
    private fun holdAnswerOf(folder: String): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        repo.onLoaded = {
            if (it == folder) {
                repo.onLoaded = null
                repo.answerGate = gate
            }
        }
        return gate
    }

    private fun releaseAnswer(gate: CompletableDeferred<Unit>) {
        repo.answerGate = null
        gate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `a mail marked read while its page was being fetched is read once the page lands`() {
        repo.add("INBOX", mailSummary(1))
        repo.add("寄件備份匣", mailSummary(7))
        vm.loadIfNeeded()
        assertEquals(FolderSelection.AllMail, vm.state.value.selected)
        val gate = holdAnswerOf("INBOX")
        vm.refresh()
        // The inbox's page is read and on its way, and the mail's own screen marks the mail read.
        runBlocking { repo.setSeen("INBOX", 1, true) }
        assertTrue(vm.state.value.displayed.first { it.uid == 1L }.summary.flags.seen)
        releaseAnswer(gate)
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loaded)
        assertTrue("the page put the flag back", vm.state.value.displayed.first { it.uid == 1L }.summary.flags.seen)
    }

    @Test
    fun `a mail moved away while its page was being fetched does not come back with the page`() {
        repo.add("INBOX", mailSummary(1), mailSummary(2))
        repo.add("寄件備份匣", mailSummary(7))
        vm.loadIfNeeded()
        val gate = holdAnswerOf("INBOX")
        vm.refresh()
        runBlocking { repo.move("INBOX", 2, repo.resolved.nameOf(SpecialFolder.TRASH)!!) }
        assertEquals(listOf(1L), vm.state.value.displayed.filter { it.folder == "INBOX" }.map { it.uid })
        releaseAnswer(gate)
        assertEquals(listOf(1L), vm.state.value.displayed.filter { it.folder == "INBOX" }.map { it.uid })
    }

    @Test
    fun `a folder changed while its page was being fetched is fetched again at once, and the page appearing then asks nothing`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        val sentFolder = repo.resolved.nameOf(SpecialFolder.SENT)!!
        val mail = OutgoingMail(repo.self, listOf(MailAddress(null, "a@x.tw")), emptyList(), emptyList(), "s", "b")
        val gate = holdAnswerOf(sentFolder)
        vm.refresh()
        // Sent's page is read and on its way, without the copy of a mail sent now.
        runBlocking { repo.send(mail, null) }
        val loads = repo.loadsSent.size
        releaseAnswer(gate)
        assertTrue(sentFolder in repo.loadsSent.drop(loads))
        val after = repo.loadsSent.size
        vm.loadIfNeeded()
        assertEquals(after, repo.loadsSent.size)
    }

    @Test
    fun `a change during an older page's fetch lands on that page, and nothing of the list or the place in it is lost`() {
        repo.add("INBOX", *(1L..60L).map { mailSummary(it) }.toTypedArray())
        vm.loadIfNeeded()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        val gate = holdAnswerOf("INBOX")
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        val loads = repo.loadsSent.size
        // The older page is read and on its way; the mail's own screen marks one of its rows read
        // and moves another away.
        runBlocking {
            repo.setSeen("INBOX", 5, true)
            repo.move("INBOX", 3, repo.resolved.nameOf(SpecialFolder.TRASH)!!)
        }
        releaseAnswer(gate)
        // Sixty rows paged in stay sixty less the moved one, in place: no fetch of the first page.
        val displayed = vm.state.value.displayed
        assertEquals((60L downTo 1L).filter { it != 3L }, displayed.map { it.uid })
        assertTrue(displayed.first { it.uid == 5L }.summary.flags.seen)
        assertEquals(loads, repo.loadsSent.size)
        assertTrue(vm.state.value.cursors.isEmpty())
    }

    @Test
    fun `a change for the later folder of an older-page batch that came before its request is not written over its page`() {
        val sent = repo.resolved.nameOf(SpecialFolder.SENT)!!
        repo.add("INBOX", *(1L..60L).map { mailSummary(it) }.toTypedArray())
        repo.add(sent, *(1L..60L).map { mailSummary(it) }.toTypedArray())
        vm.loadIfNeeded()
        assertEquals(FolderSelection.AllMail, vm.state.value.selected)
        val gate = holdAnswerOf("INBOX")
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        // While the inbox's older page is on its way, a Sent mail is marked read from its own
        // screen, and another client marks it unread again -- which no change tells of -- before
        // Sent's own older page is read. That page has the newer flag.
        runBlocking { repo.setSeen(sent, 5, true) }
        repo.mail.getValue(sent).replaceAll { if (it.uid == 5L) it.copy(flags = it.flags.copy(seen = false)) else it }
        releaseAnswer(gate)
        assertEquals(120, vm.state.value.displayed.size)
        val row = vm.state.value.displayed.first { it.folder == sent && it.uid == 5L }
        assertFalse("the earlier change was written over the page's newer flag", row.summary.flags.seen)
    }

    @Test
    fun `a cached page read across a mark-read is fetched again as the page appears, even after its own fetch failed`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        // Back to the inbox: its cached page is read from disk, and while it is, the mail's own
        // screen marks the mail read -- the read had the row from before that. The server's page
        // that follows fails.
        val gate = CompletableDeferred<Unit>()
        repo.cachedGate = gate
        repo.loadError = MailError.Network()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        runBlocking { repo.setSeen("INBOX", 1, true) }
        repo.cachedGate = null
        gate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })
        // The rows on screen are in doubt, so coming back fetches the folder again; the server answers now.
        repo.loadError = null
        vm.loadIfNeeded()
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loaded)
        assertTrue(vm.state.value.displayed.single().summary.flags.seen)
    }

    @Test
    fun `a swipe whose write ends after another student signed in over this one leaves their list alone`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        val gate = CompletableDeferred<Unit>()
        repo.seenGate = gate
        vm.toggleRead(vm.state.value.displayed.single())
        signInAnotherOverThisOne()
        vm.loadIfNeeded()
        assertFalse(vm.state.value.displayed.single().summary.flags.seen)
        repo.seenGate = null
        gate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
        assertFalse("the first student's swipe marked the second's mail read", vm.state.value.displayed.single().summary.flags.seen)
    }

    @Test
    fun `a flag another client set after a mark-unread is what the list ends up showing`() {
        repo.add("INBOX", mailSummary(1, seen = true))
        vm.loadIfNeeded()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        val loads = repo.loadsSent.size
        // A refresh is asked for and its page not read yet when a mark-unread from the mail's own
        // screen lands -- and then another client marks the mail read again, which no change tells.
        val gate = CompletableDeferred<Unit>()
        repo.loadGate = gate
        vm.refresh()
        runBlocking { repo.setSeen("INBOX", 1, false) }
        assertFalse(vm.state.value.displayed.single().summary.flags.seen)
        repo.mail.getValue("INBOX").replaceAll { if (it.uid == 1L) it.copy(flags = it.flags.copy(seen = true)) else it }
        repo.loadGate = null
        gate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
        // The page read after both has the truth, and the list is fetched once more to be sure of it -- once.
        assertTrue(vm.state.value.displayed.single().summary.flags.seen)
        assertEquals(listOf("INBOX", "INBOX"), repo.loadsSent.drop(loads))
    }

    @Test
    fun `a burst of changes while a page was out ends in the server's list, by one fetch more`() {
        repo.add("INBOX", *(1L..70L).map { mailSummary(it) }.toTypedArray())
        vm.loadIfNeeded()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        val gate = holdAnswerOf("INBOX")
        vm.refresh()
        val loads = repo.loadsSent.size
        // Every mail marked read, newest first, while the page is on its way.
        runBlocking { (70L downTo 1L).forEach { repo.setSeen("INBOX", it, true) } }
        releaseAnswer(gate)
        assertEquals(50, vm.state.value.displayed.size)
        assertTrue(vm.state.value.displayed.all { it.summary.flags.seen })
        assertEquals(listOf("INBOX"), repo.loadsSent.drop(loads))
    }

    @Test
    fun `a mail sent from compose has its copy fetched into All mail on the way back, and into nothing off screen`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        assertEquals(FolderSelection.AllMail, vm.state.value.selected)
        val sentFolder = repo.resolved.nameOf(SpecialFolder.SENT)!!
        val mail = OutgoingMail(repo.self, listOf(MailAddress(null, "a@x.tw")), emptyList(), emptyList(), "s", "b")
        var loads = repo.loadsSent.size
        runBlocking { repo.send(mail, null) }
        main.dispatcher.scheduler.runCurrent()
        vm.loadIfNeeded()
        assertTrue(sentFolder in repo.loadsSent.drop(loads))

        vm.selectFolder(FolderSelection.Real("INBOX"))
        loads = repo.loadsSent.size
        runBlocking { repo.send(mail, null) }
        main.dispatcher.scheduler.runCurrent()
        vm.loadIfNeeded()
        assertEquals(loads, repo.loadsSent.size)
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
    fun `coming back to the list checks the inbox at once, not a minute later`() {
        repo.add("INBOX", mailSummary(1))
        repo.status = FolderStatus(server.uidValidity, 2, 1, 1)
        vm.load()
        vm.startPolling()
        vm.stopPolling()
        // The app away for ten minutes, and mail arrived meanwhile.
        main.dispatcher.scheduler.advanceTimeBy(10 * 60_000L)
        repo.add("INBOX", mailSummary(2))
        repo.status = FolderStatus(server.uidValidity, 3, 2, 2)
        vm.startPolling(fromBackground = true)
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.RESUME_POLL_GRACE_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(listOf(2L, 1L), vm.state.value.displayed.map { it.uid })
        vm.stopPolling()
    }

    @Test
    fun `coming back from a mail does not look at the inbox, and coming back from the background does`() {
        repo.add("INBOX", mailSummary(1))
        vm.load()
        vm.startPolling()
        vm.stopPolling()
        // Half a minute reading a mail, then back to the list: the one it left, kept (see
        // loadIfNeeded), and not due for the poll's next look yet. A look on every return
        // would be the round trip per mail opened that keeping the list is there to save.
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS / 2)
        val calls = repo.statusCalls
        vm.startPolling()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.RESUME_POLL_GRACE_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(calls, repo.statusCalls)
        vm.stopPolling()
        // The app itself away for as long: mail may have arrived, its notification perhaps
        // already in the shade, and the list should show it as soon as the app is back.
        vm.startPolling(fromBackground = true)
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.RESUME_POLL_GRACE_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(calls + 1, repo.statusCalls)
        vm.stopPolling()
    }

    @Test
    fun `coming back from a mail after the poll missed a tick takes the look it owes`() {
        repo.add("INBOX", mailSummary(1))
        vm.load()
        vm.startPolling()
        vm.stopPolling()
        main.dispatcher.scheduler.advanceTimeBy(10 * 60_000L)
        val calls = repo.statusCalls
        vm.startPolling()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.RESUME_POLL_GRACE_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(calls + 1, repo.statusCalls)
        vm.stopPolling()
    }

    @Test
    fun `a look the poll took counts, so a return within a minute of it asks nothing`() {
        repo.add("INBOX", mailSummary(1))
        vm.load()
        vm.startPolling()
        // The poll's first tick, finding nothing new.
        pollOnce()
        vm.stopPolling()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS / 2)
        val calls = repo.statusCalls
        vm.startPolling()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.RESUME_POLL_GRACE_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(calls, repo.statusCalls)
        vm.stopPolling()
    }

    @Test
    fun `the screen appearing right after its own load does not check the inbox again at once`() {
        repo.add("INBOX", mailSummary(1))
        vm.load()
        val calls = repo.statusCalls
        vm.startPolling()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.RESUME_POLL_GRACE_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(calls, repo.statusCalls)
        vm.stopPolling()
    }

    @Test
    fun `polling does not check at once while the list has not loaded yet`() {
        repo.add("INBOX", mailSummary(1))
        vm.startPolling()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.RESUME_POLL_GRACE_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(0, repo.statusCalls)
        vm.stopPolling()
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
        assertTrue(vm.state.value.actionError is MailError.AuthFailed)
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

        // Signing in again clears the flag, and polling works from then on: a look at once, the
        // list having loaded minutes ago, then one a minute.
        runBlocking { account.signIn("b10000001", "pw") }
        assertFalse(account.authFailed.value)
        vm.startPolling()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(1, repo.acquired)
        assertEquals(statusCallsAfterLoad + 2, repo.statusCalls)
    }

    @Test
    fun `a certificate failure during a poll stays on screen instead of fading like other errors`() {
        // Unlike a busy server, an untrusted certificate says the connection to the mail server
        // could not be verified -- on a campus network, the interception case. A toast that
        // fades after a few seconds, once a minute, is one a user can miss indefinitely, so
        // this one error keeps the page in Failed (§12.3).
        repo.add("INBOX", mailSummary(1))
        vm.load()
        vm.startPolling()
        repo.statusError = MailError.Certificate()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS + 1)
        main.dispatcher.scheduler.runCurrent()

        val state = vm.state.value
        assertTrue("stays on the page", state.loadState is SchoolMailListViewModel.LoadState.Failed)
        assertTrue(
            (state.loadState as SchoolMailListViewModel.LoadState.Failed).error is MailError.Certificate,
        )
        // Not routed to the transient channel, so dismissing a toast cannot clear it.
        assertNull(state.actionError)
        assertFalse(account.authFailed.value)
    }

    // --- a failed action is not a failed page -----------------------------------------------

    @Test
    fun `one flaky poll does not turn the loaded page into a failed one`() {
        // Mail2000 caps connections and starts answering "server busy" under load, so a single
        // unlucky minute is ordinary. It used to leave the header's status dot red for the rest
        // of the session: nothing reset loadState short of a successful fetchFirstPage.
        repo.add("INBOX", mailSummary(1))
        vm.load()
        vm.startPolling()
        repo.statusError = MailError.ServerBusy()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS + 1)
        main.dispatcher.scheduler.runCurrent()

        assertTrue(vm.state.value.actionError is MailError.ServerBusy)
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loaded)
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })

        // Shown once, then gone -- it must not linger into the next screenful.
        vm.dismissActionError()
        assertNull(vm.state.value.actionError)
    }

    @Test
    fun `a mark-read the server refuses reports as an action, not as the list failing to load`() {
        repo.add("INBOX", mailSummary(1))
        vm.load()
        repo.seenError = MailError.ServerBusy()
        vm.toggleRead(vm.state.value.displayed.single())

        assertTrue(vm.state.value.actionError is MailError.ServerBusy)
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loaded)
        // The row is unchanged, and the page it lives on is still the page that loaded fine.
        assertFalse(vm.state.value.displayed.single().summary.flags.seen)
    }

    @Test
    fun `an empty folder whose mark-read failed still reads as empty, not as unloadable`() {
        // Nothing to toggle here, but the same failure used to replace a perfectly good empty
        // folder with the "couldn't load" state, because displayed.isEmpty() && failed != null.
        vm.load()
        assertTrue(vm.state.value.displayed.isEmpty())
        repo.statusError = MailError.Network()
        vm.startPolling()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS + 1)
        main.dispatcher.scheduler.runCurrent()

        assertTrue(vm.state.value.actionError is MailError.Network)
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loaded)
    }

    @Test
    fun `a rejected password still reaches the account from a mark-read, action error or not`() {
        // Spec §7.4: whichever call happens to meet the rejected password, the account has to
        // hear about it -- the lockout protection must not depend on that being a load.
        repo.add("INBOX", mailSummary(1))
        vm.load()
        repo.seenError = MailError.AuthFailed()
        vm.toggleRead(vm.state.value.displayed.single())

        assertTrue(account.authFailed.value)
        assertTrue(vm.state.value.actionError is MailError.AuthFailed)
    }

    @Test
    fun `a sent-copy notice outlives the reload after compose and goes only when dismissed`() {
        repo.add("INBOX", mailSummary(1))
        vm.load()
        notices.report(SentCopyNotice.NoFolder, SentCopyOrigin.List, notices.accountGeneration)
        vm.refresh()
        vm.load()
        assertEquals(SentCopyNotice.NoFolder, vm.sentCopyNotice.value)
        vm.dismissSentCopyNotice()
        assertNull(vm.sentCopyNotice.value)
    }

    @Test
    fun `a notice about a reply sent from a mail is that mail's, not the list's`() {
        notices.report(SentCopyNotice.NoFolder, SentCopyOrigin.Message("INBOX", 1), notices.accountGeneration)
        assertNull(vm.sentCopyNotice.value)
    }

    @Test
    fun `signing out takes the sent-copy notice with it`() {
        notices.report(SentCopyNotice.Unknown, SentCopyOrigin.List, notices.accountGeneration)
        account.signOut()
        main.dispatcher.scheduler.runCurrent()
        assertNull(notices.posted)
        assertNull(vm.sentCopyNotice.value)
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

    // --- the "Last synced" row --------------------------------------------------------------

    @Test
    fun `loading the folders on screen dates them, and a load that fails leaves the date`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        vm.load()
        // All mail: the inbox and Sent, both read just now.
        assertEquals(5_000L, vm.state.value.fetchedAt)
        assertEquals(5_000L, state.fetchedAt("INBOX"))
        assertEquals(5_000L, state.fetchedAt("寄件備份匣"))

        now = 9_000
        repo.loadError = MailError.Network()
        vm.refresh()
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
        assertEquals(5_000L, vm.state.value.fetchedAt)
        assertEquals(5_000L, state.fetchedAt("INBOX"))
        assertEquals(5_000L, state.fetchedAt("寄件備份匣"))
    }

    /**
     * Each folder is dated on its own, and the row dates the folders on screen: the one
     * selected, or for All mail the older of its two, as on the iPhone. Here a poll's check of
     * Sent fails, so the inbox is dated and Sent is not, and All mail shows Sent's older date.
     */
    @Test
    fun `the date is the folder on screen's, and All mail shows the older of its two`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        repo.add("寄件備份匣", mailSummary(1))
        repo.status = FolderStatus(server.uidValidity, 2, 1, 1)
        vm.load()
        assertEquals(FolderSelection.AllMail, vm.state.value.selected)
        assertEquals(5_000L, vm.state.value.fetchedAt)

        vm.startPolling()
        now = 9_000
        repo.folderStatusErrors["寄件備份匣"] = MailError.ServerBusy()
        pollOnce()
        assertEquals(9_000L, state.fetchedAt("INBOX"))
        assertEquals(5_000L, state.fetchedAt("寄件備份匣"))
        assertEquals(5_000L, vm.state.value.fetchedAt)
        vm.stopPolling()

        vm.selectFolder(FolderSelection.Real("INBOX"))
        assertEquals(9_000L, vm.state.value.fetchedAt)

        now = 12_000
        vm.selectFolder(FolderSelection.Real("寄件備份匣"))
        assertEquals(12_000L, vm.state.value.fetchedAt)
        assertEquals(9_000L, state.fetchedAt("INBOX"))

        vm.selectFolder(FolderSelection.AllMail)
        assertEquals(12_000L, vm.state.value.fetchedAt)
    }

    /**
     * A poll that finds nothing new must still move the row, or a user would read the time as
     * stale and think the page had not synced -- in All mail too. So the poll asks every folder on
     * screen for its STATUS, the inbox for new mail as always and Sent for nothing more than an
     * answer, and dates each that answered; one whose check failed keeps its date.
     */
    @Test
    fun `in All mail a poll that finds nothing new moves the visible date, having verified Sent too`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        repo.add("寄件備份匣", mailSummary(1))
        repo.status = FolderStatus(server.uidValidity, 2, 1, 1)
        vm.load()
        vm.startPolling()

        now = 9_000
        pollOnce()
        assertEquals(listOf("寄件備份匣"), repo.folderStatusCalls)
        assertEquals(9_000L, state.fetchedAt("INBOX"))
        assertEquals(9_000L, state.fetchedAt("寄件備份匣"))
        assertEquals(9_000L, vm.state.value.fetchedAt)
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loaded)

        now = 12_000
        repo.folderStatusErrors["寄件備份匣"] = MailError.ServerBusy()
        pollOnce()
        assertEquals(12_000L, state.fetchedAt("INBOX"))
        assertEquals(9_000L, state.fetchedAt("寄件備份匣"))
        assertEquals(9_000L, vm.state.value.fetchedAt)
        vm.stopPolling()
    }

    /** A folder viewed on its own is verified by the poll too; the inbox, off screen, is not asked. */
    @Test
    fun `a folder viewed on its own is verified by the poll, and dated when it answers`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        repo.add("寄件備份匣", mailSummary(1))
        vm.load()
        vm.selectFolder(FolderSelection.Real("寄件備份匣"))
        vm.startPolling()
        val inboxChecks = repo.statusCalls

        now = 9_000
        pollOnce()
        assertEquals(listOf("寄件備份匣"), repo.folderStatusCalls)
        assertEquals(inboxChecks, repo.statusCalls)
        assertEquals(9_000L, state.fetchedAt("寄件備份匣"))
        assertEquals(5_000L, state.fetchedAt("INBOX"))
        assertEquals(9_000L, vm.state.value.fetchedAt)
        vm.stopPolling()
    }

    /**
     * A page still out across a sign-out and another account's sign-in is the first account's: it
     * must not go on the second account's screen, nor into the cache, nor date a folder nothing was
     * fetched for (spec §7.5). The fetch is blocking IMAP I/O the sign-out cannot cancel, so the
     * page really comes back -- held here after it was sent, on a refresh, which runs outside the
     * load a sign-out cancels -- and is dropped when it lands.
     */
    @Test
    fun `a page that comes back after a sign-out and another sign-in shows, caches and dates nothing`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        assertEquals(5_000L, state.fetchedAt("INBOX"))
        // As the real repository: a page whose account moved while it was out is not cached.
        repo.accountGeneration = { account.accountGeneration }
        repo.dropCache("INBOX")
        repo.onLoaded = {
            repo.onLoaded = null
            now = 9_000
            signOutAndSignInAnother()
        }
        val sent = repo.loadsSent.size
        vm.refresh()

        // The page was sent, and came back across the change.
        assertEquals(listOf("INBOX"), repo.loadsSent.drop(sent))
        assertEquals(SchoolMailListViewModel.UiState(), vm.state.value)
        assertNull(runBlocking { repo.cachedPage("INBOX") })
        assertEquals(0L, state.fetchedAt("INBOX"))
        assertEquals(0L, state.fetchedAt("寄件備份匣"))
    }

    /** Each folder is dated as its own page lands, so a failure of Sent's does not lose the inbox's. */
    @Test
    fun `in All mail a folder that loads is dated even when the other one fails`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        repo.folderLoadErrors["寄件備份匣"] = MailError.Network()
        vm.load()

        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
        assertEquals(5_000L, state.fetchedAt("INBOX"))
        assertEquals(0L, state.fetchedAt("寄件備份匣"))
        // All mail is as fresh as its staler half, which never loaded.
        assertEquals(0L, vm.state.value.fetchedAt)
    }

    /**
     * A chip tapped while a load is out does not lose the date of a page already on its way
     * back -- and the batch sends nothing more for the view that left.
     */
    @Test
    fun `a page that lands after the chip moved on still dates its folder`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        repo.add("寄件備份匣", mailSummary(1))
        repo.onLoaded = { folder ->
            if (folder == "INBOX") {
                repo.onLoaded = null
                now = 9_000
                vm.selectFolder(FolderSelection.Real("寄件備份匣"))
            }
        }
        vm.load()

        assertEquals(FolderSelection.Real("寄件備份匣"), vm.state.value.selected)
        assertEquals(9_000L, state.fetchedAt("INBOX"))
        assertEquals(9_000L, state.fetchedAt("寄件備份匣"))
        assertEquals(9_000L, vm.state.value.fetchedAt)
        assertEquals(listOf(1L), vm.state.value.messages.map { it.uid })
        // The inbox page, then Sent's own load; All mail's Sent request was never sent.
        assertEquals(listOf("INBOX", "寄件備份匣"), repo.loadsSent)
    }

    /** The inbox answered the poll's STATUS; that it had grown and the refetch then failed takes nothing from that. */
    @Test
    fun `a poll whose STATUS finds new mail dates the inbox even when the reload then fails`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.startPolling()

        now = 9_000
        repo.status = FolderStatus(server.uidValidity, 5, 3, 3)
        repo.loadError = MailError.Network()
        pollOnce()

        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
        assertEquals(9_000L, state.fetchedAt("INBOX"))
        assertEquals(9_000L, vm.state.value.fetchedAt)
        vm.stopPolling()
    }

    /** A chip tapped while the poll's inbox STATUS is out: the poll asks nothing more for the view that left. */
    @Test
    fun `a poll does not STATUS a folder that left the screen while its inbox check was out`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        repo.add("寄件備份匣", mailSummary(1))
        repo.status = FolderStatus(server.uidValidity, 2, 1, 1)
        vm.load()
        vm.startPolling()
        repo.statusGate = CompletableDeferred()
        now = 9_000
        pollOnce()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        repo.statusGate!!.complete(Unit)

        assertEquals(emptyList<String>(), repo.folderStatusCalls)
        assertEquals(9_000L, state.fetchedAt("INBOX"))
        vm.stopPolling()
    }

    /**
     * A's All mail load has its inbox page when A signs out and B signs in. The batch's next
     * request would go out with B's credentials under A's generation, and B's answer -- a failure
     * too, even a rejected password -- would be dropped as A's. So a batch stops before each
     * further request once its account has left, and sends nothing more.
     */
    @Test
    fun `a batch whose account left between two of its requests sends no more`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        repo.add("寄件備份匣", mailSummary(1))
        repo.onLoaded = { folder ->
            if (folder == "INBOX") {
                repo.onLoaded = null
                signOutAndSignInAnother()
            }
        }
        vm.load()

        assertEquals(listOf("INBOX"), repo.loadsSent)
        assertEquals(SchoolMailListViewModel.UiState(), vm.state.value)
        assertEquals(0L, state.fetchedAt("INBOX"))
        assertEquals(0L, state.fetchedAt("寄件備份匣"))
    }

    /** The folder list a load starts with is its first request, and waits for the session like any other. */
    @Test
    fun `a load whose folder list waits across the account change sends it for nobody`() {
        repo.add("INBOX", mailSummary(1))
        repo.foldersGate = CompletableDeferred()
        vm.load()
        signOutAndSignInAnother()
        repo.foldersGate!!.complete(Unit)

        assertEquals(0, repo.foldersSent)
        assertEquals(emptyList<String>(), repo.loadsSent)
        assertEquals(SchoolMailListViewModel.UiState(), vm.state.value)
    }

    /** A search is a batch too, one SEARCH per folder of the view. */
    @Test
    fun `a search whose account left between two of its folders sends no more`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考"))
        repo.add("寄件備份匣", mailSummary(2, subject = "期中考"))
        vm.load()
        repo.onSearched = { folder ->
            if (folder == "INBOX") {
                repo.onSearched = null
                signOutAndSignInAnother()
            }
        }
        vm.setSearchText("期中考")
        vm.submitSearch()

        assertEquals(listOf("INBOX"), repo.searchesSent)
        assertEquals(SchoolMailListViewModel.UiState(), vm.state.value)
    }

    /**
     * The poll's check of Sent passed, but its STATUS then queued behind another request holding
     * the session, and the user moved to Inbox meanwhile. Asked again with the session held, right
     * before the command, the STATUS is not sent for a folder that has left the screen.
     */
    @Test
    fun `a poll's STATUS queued behind the session is not sent once its folder left the screen`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        repo.add("寄件備份匣", mailSummary(1))
        repo.status = FolderStatus(server.uidValidity, 2, 1, 1)
        vm.load()
        vm.startPolling()
        repo.folderStatusGate = CompletableDeferred()
        now = 9_000
        pollOnce()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        repo.folderStatusGate!!.complete(Unit)

        assertEquals(emptyList<String>(), repo.folderStatusCalls)
        assertEquals(9_000L, state.fetchedAt("INBOX"))
        assertEquals(5_000L, state.fetchedAt("寄件備份匣"))
        vm.stopPolling()
    }

    /** Another student signs in over this one with no sign-out between, as the sign-in sheet after a rejected password allows. */
    private fun signInAnotherOverThisOne() {
        server.passwords["B10000002"] = "pw"
        runBlocking { account.signIn("b10000002", "pw") }
    }

    /** The account leaves and another arrives, as Settings does it, in the middle of whatever is running. */
    private fun signOutAndSignInAnother() {
        account.signOut()
        main.dispatcher.scheduler.runCurrent()
        server.passwords["B10000002"] = "pw"
        runBlocking { account.signIn("b10000002", "pw") }
    }

    @Test
    fun `a poll that finds nothing new still dates the inbox, and one that fails does not`() {
        repo.add("INBOX", mailSummary(1))
        repo.status = FolderStatus(server.uidValidity, 2, 1, 1)
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.startPolling()

        now = 9_000
        pollOnce()
        assertEquals(9_000L, vm.state.value.fetchedAt)

        now = 12_000
        repo.statusError = MailError.ServerBusy()
        pollOnce()
        assertEquals(9_000L, vm.state.value.fetchedAt)
        vm.stopPolling()
    }

    /** Another visit to the page, over the same mailbox, cache and checker. */
    private fun anotherVisit() = SchoolMailListViewModel(
        repo, account, checker, schoolMailSite(),
        { main.dispatcher.scheduler.currentTime }, notices,
    )

    /**
     * Rows a failed load left painted from the cache are a previous visit's: their flags and
     * deletions are that visit's too. A poll that finds nothing new in their folders must not
     * date them "just now", as the iPhone's tigerduck-app/tigerduck-app#647 found; a page from
     * the server that replaces them dates them again.
     */
    @Test
    fun `a poll after a failed load keeps the cached rows' date, until a page from the server replaces them`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        repo.add("寄件備份匣", mailSummary(1))
        repo.status = FolderStatus(server.uidValidity, 2, 1, 1)
        vm.load()
        assertEquals(5_000L, vm.state.value.fetchedAt)

        now = 9_000
        repo.loadError = MailError.Network()
        val visit = anotherVisit()
        visit.load()
        assertTrue(visit.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
        assertTrue("the cache painted the rows", visit.state.value.messages.isNotEmpty())
        visit.startPolling()
        pollOnce()
        assertEquals(5_000L, visit.state.value.fetchedAt)
        assertEquals(5_000L, state.fetchedAt("INBOX"))
        assertEquals(5_000L, state.fetchedAt("寄件備份匣"))

        now = 12_000
        repo.loadError = null
        visit.refresh()
        assertTrue(visit.state.value.loadState is SchoolMailListViewModel.LoadState.Loaded)
        assertEquals(12_000L, visit.state.value.fetchedAt)

        now = 15_000
        pollOnce()
        assertEquals(15_000L, visit.state.value.fetchedAt)
        visit.stopPolling()
    }

    /** The same rows dated by a check of the inbox that ran meanwhile: it found no new mail, and verified nothing on screen. */
    @Test
    fun `a check that found nothing new does not date rows a failed load left painted from the cache`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))

        repo.loadError = MailError.Network()
        val visit = anotherVisit()
        visit.load()
        visit.selectFolder(FolderSelection.Real("INBOX"))
        assertTrue(visit.state.value.messages.isNotEmpty())
        now = 7_000
        assertEquals(CheckOutcome.NoChange, runBlocking { checker.check(CheckSource.ALARM) })

        visit.startPolling()
        assertEquals(5_000L, visit.state.value.fetchedAt)
        visit.stopPolling()
    }

    @Test
    fun `a check that ran while the page was away shows when the page comes back`() {
        repo.add("INBOX", mailSummary(1))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        now = 7_000
        assertEquals(CheckOutcome.NoChange, runBlocking { checker.check(CheckSource.ALARM) })

        vm.startPolling()
        assertEquals(7_000L, vm.state.value.fetchedAt)
        vm.stopPolling()
    }

    @Test
    fun `the demo mailbox is never dated, having no server to answer`() {
        runBlocking { account.signIn("B10000099", "demo") }
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        vm.load()
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loaded)
        assertEquals(0L, vm.state.value.fetchedAt)
        assertEquals(0L, state.fetchedAt("INBOX"))
        assertEquals(0L, state.fetchedAt("寄件備份匣"))
    }

    @Test
    fun `signing out forgets every folder's date with the rest of the mail state`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        vm.load()
        assertEquals(5_000L, vm.state.value.fetchedAt)

        account.signOut()
        main.dispatcher.scheduler.runCurrent()
        assertEquals(0L, vm.state.value.fetchedAt)
        assertEquals(0L, state.fetchedAt("INBOX"))
        assertEquals(0L, state.fetchedAt("寄件備份匣"))
    }

    // --- a new mail's body ---------------------------------------------------------------

    /** One poll tick. Not advanceUntilIdle: the poll loop never idles, it delays again. */
    private fun pollOnce() {
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS + 1)
        main.dispatcher.scheduler.runCurrent()
    }

    /** Replaces the inbox with exactly [uids], and the status a server would report for it. */
    private fun inboxOf(vararg uids: Long) {
        repo.mail.getOrPut("INBOX") { mutableListOf() }.apply {
            clear()
            addAll(uids.map { mailSummary(it) })
        }
        repo.status = FolderStatus(server.uidValidity, uids.max() + 1, uids.size, uids.size)
    }

    @Test
    fun `a poll that finds new mail warms its body, so tapping it does not spin`() {
        // Spec §5. MailChecker does this on the connection its own check holds, but the page poll
        // never goes through MailChecker -- it calls fetchFirstPage itself. So in the one case
        // where the mail is certain to be opened within seconds, the app already on this list,
        // the row arrived within the minute and tapping it still spun.
        inboxOf(1)
        vm.load()
        vm.startPolling()
        repo.bodyCalls.clear()

        repo.bodies[2] = MailBody(null, "hello", emptyList(), emptyMap())
        inboxOf(1, 2)
        pollOnce()

        assertEquals(listOf("INBOX" to 2L), repo.bodyCalls)
    }

    @Test
    fun `a burst of new mail warms the newest five and nothing the list already had`() {
        // Bounded for the same reason MailChecker's is: a burst must not turn one poll into a
        // long one on a connection the screen is waiting behind.
        inboxOf(1)
        vm.load()
        vm.startPolling()
        repo.bodyCalls.clear()

        inboxOf(*(1L..9L).toList().toLongArray())
        pollOnce()

        assertEquals(listOf(9L, 8L, 7L, 6L, 5L), repo.bodyCalls.map { it.second })
    }

    @Test
    fun `a body that will not come down leaves the poll's page exactly as it was`() {
        inboxOf(1)
        vm.load()
        vm.startPolling()
        repo.bodyCalls.clear()
        repo.bodyError = MailError.ServerBusy()

        inboxOf(1, 2)
        pollOnce()

        // The fetch was actually attempted -- without this the test would pass just as well with
        // the prefetch deleted outright.
        assertEquals(listOf("INBOX" to 2L), repo.bodyCalls)
        assertEquals(listOf(2L, 1L), vm.state.value.displayed.map { it.uid })
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loaded)
        assertNull(vm.state.value.actionError)
    }

    @Test
    fun `a poll that finds nothing new warms nothing`() {
        inboxOf(1)
        vm.load()
        vm.startPolling()
        repo.bodyCalls.clear()

        pollOnce()

        assertTrue(repo.bodyCalls.isEmpty())
    }

    // --- prefetch --------------------------------------------------------------------------

    @Test
    fun `entering the page warms the other folders at twenty rows each`() = runTest {
        vm.load()
        advanceUntilIdle()

        val prefetched = repo.pageLimits.filter { it.second == SchoolMailListViewModel.PREFETCH_LIMIT }
        // The real decoded names (see SpecialFolder): DRAFTS, JUNK, TRASH -- everything but the
        // two folders All mail already merged in (INBOX, SENT).
        assertEquals(
            setOf("草稿匣", "廣告信匣", "回收筒"),
            prefetched.map { it.first }.toSet(),
        )
    }

    @Test
    fun `the visible folders are loaded at full size, not the prefetch size`() = runTest {
        vm.load()
        advanceUntilIdle()

        val first = repo.pageLimits.first()
        assertEquals(50, first.second)
    }

    @Test
    fun `no folder is fetched twice`() = runTest {
        vm.load()
        advanceUntilIdle()

        val names = repo.pageLimits.map { it.first }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun `a prefetch failure never reaches the page`() = runTest {
        repo.prefetchError = MailError.ServerBusy()
        vm.load()
        advanceUntilIdle()

        // The failure actually happened to every folder in the queue -- proof it was
        // swallowed, not just that prefetch never got a chance to run at all.
        val prefetched = repo.pageLimits.filter { it.second == SchoolMailListViewModel.PREFETCH_LIMIT }
        assertEquals(setOf("草稿匣", "廣告信匣", "回收筒"), prefetched.map { it.first }.toSet())

        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loaded)
        assertNull(vm.state.value.actionError)
    }

    @Test
    fun `switching folder cancels the prefetch in flight`() = runTest {
        repo.blockPrefetch = true
        vm.load()
        advanceUntilIdle()
        val before = repo.pageLimits.size

        vm.selectFolder(FolderSelection.Real("草稿匣"))
        advanceUntilIdle()
        repo.blockPrefetch = false
        advanceUntilIdle()

        // The switch's own load comes first, at full size and never queued behind the warm.
        assertEquals("草稿匣" to 50, repo.pageLimits[before])
        // The parked call really was cancelled rather than merely abandoned: it released its slot
        // inside loadPage before the switch's own load entered it, so no two loads were ever open
        // at once. A prefetch left running would have overlapped that load and made this 2.
        assertEquals(1, repo.maxConcurrentLoads)
        // And the cancelled queue did not carry on beside the one the switch starts: it would
        // have gone on to 廣告信匣 and 回收筒, which the new queue also covers, so a survivor
        // shows up as a folder warmed twice.
        val prefetched = repo.pageLimits.filter { it.second == SchoolMailListViewModel.PREFETCH_LIMIT }
        assertEquals(prefetched.size, prefetched.distinct().size)
    }

    @Test
    fun `switching folder warms whatever the new view is not showing`() = runTest {
        // startPrefetch used to run from load() alone, and load() is keyed on (signedIn,
        // authFailed) -- so the first chip tap cancelled the warm and nothing ever started it
        // again. A user who tapped a chip early left every other mailbox cold for the rest of
        // this view model's life, which is the feature quietly switching itself off.
        vm.load()
        advanceUntilIdle()
        val before = repo.pageLimits.size

        vm.selectFolder(FolderSelection.Real("草稿匣"))
        advanceUntilIdle()

        val after = repo.pageLimits.drop(before)
        assertEquals("草稿匣" to 50, after.first())
        assertEquals(
            setOf("INBOX", "寄件備份匣", "廣告信匣", "回收筒"),
            after.filter { it.second == SchoolMailListViewModel.PREFETCH_LIMIT }.map { it.first }.toSet(),
        )
    }

    @Test
    fun `the prefetch never has two loadPage calls open at the same time`() = runTest {
        // Mail2000 caps connections and answers "server busy" under load -- a fan-out here
        // (e.g. one async{} per folder) would be paid for by the screen the user is actually
        // reading. blockPrefetch parks a call *inside* loadPage rather than before it, so a
        // fan-out implementation gets the chance to start a second call while the first is
        // still open -- which is exactly what maxConcurrentLoads would catch.
        repo.blockPrefetch = true
        vm.load()
        advanceUntilIdle()
        repo.blockPrefetch = false
        advanceUntilIdle()

        assertEquals(1, repo.maxConcurrentLoads)
    }

    // --- All mail ------------------------------------------------------------------------

    private fun at(millis: Long) = Instant.ofEpochMilli(millis)

    private fun selectAll() = vm.selectFolder(FolderSelection.AllMail)

    @Test
    fun `All mail merges only the inbox and sent folders, newest first`() {
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
        // UIDs are unique only within a folder: Inbox and Sent both holding a UID 42 is
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
    fun `a Sent row acted on through All mail addresses Sent, not the selected chip`() {
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
        // All mail has no server-side existence at all -- a synthetic name reaching an IMAP
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
    fun `searching All mail covers both folders and still reports a local-only fallback`() {
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
    fun `All mail keeps checking for new mail and moving the seen marker, same as Inbox always did`() {
        // Inbox is one of the folders All mail merges, so the inbox is genuinely on screen while
        // viewing All mail -- defaulting to it must not cost the user the new-mail check or
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
        // The merged list itself picked up the new inbox mail, same as Inbox always refreshed.
        assertEquals(listOf("INBOX" to 2L, SENT to 9L, "INBOX" to 1L), vm.state.value.messages.map { it.folder to it.uid })
    }

    @Test
    fun `All mail paints from each folder's cache before the server answers`() {
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

    @Test
    fun `a warm never shortens a folder cache that already holds more`() = runTest {
        // More mail than one warm brings back, so a warm's page really is the shorter one.
        repo.add("草稿匣", *(1..30).map { mailSummary(it.toLong()) }.toTypedArray())

        // Opening the folder caches a full foreground page.
        vm.selectFolder(FolderSelection.Real("草稿匣"))
        advanceUntilIdle()
        val full = repo.cachedPage("草稿匣")!!.messages.size
        assertTrue("fixture must hold more than a prefetch page", full > SchoolMailListViewModel.PREFETCH_LIMIT)

        // Going back to All mail warms 草稿匣 again, at twenty rows.
        vm.selectFolder(FolderSelection.AllMail)
        advanceUntilIdle()

        // Twenty rows must not be what is left on disk for an offline open.
        assertEquals(full, repo.cachedPage("草稿匣")!!.messages.size)
    }

    @Test
    fun `a warm still seeds a folder that has no cache at all`() = runTest {
        vm.load()
        advanceUntilIdle()

        // 廣告信匣 is not in All mail's merge, so only the warm can have populated it.
        assertNotNull(repo.cachedPage("廣告信匣"))
    }

    @Test
    fun `a refresh stops the warm queue and starts a new one once it lands`() = runTest {
        repo.blockPrefetch = true
        vm.load()
        advanceUntilIdle()
        val before = repo.pageLimits.size

        vm.refresh()
        advanceUntilIdle()
        repo.blockPrefetch = false
        advanceUntilIdle()

        // The refresh's own load went first, at full size.
        assertEquals(50, repo.pageLimits[before].second)
        // Never two loads inside the repository at once: the parked warm really was cancelled.
        assertEquals(1, repo.maxConcurrentLoads)
        // And warming resumed afterwards rather than ending for the view model's life -- the bug
        // that cancelling without restarting would have reintroduced.
        val after = repo.pageLimits.drop(before + 1).filter { it.second == SchoolMailListViewModel.PREFETCH_LIMIT }
        assertEquals(setOf("草稿匣", "廣告信匣", "回收筒"), after.map { it.first }.toSet())
    }

    @Test
    fun `the warm holds off, so a refresh in the first moments queues behind nothing`() = runTest {
        vm.load()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.PREFETCH_START_DELAY_MS / 2)

        // Inside the grace the connection has not been taken for warming at all, so a cancel
        // landing here costs a foreground request nothing -- not even the one page it would
        // otherwise have had to wait out.
        assertEquals(0, repo.pageLimits.count { it.second == SchoolMailListViewModel.PREFETCH_LIMIT })

        advanceUntilIdle()
        // And the warm does still happen once the moment has passed.
        assertEquals(3, repo.pageLimits.count { it.second == SchoolMailListViewModel.PREFETCH_LIMIT })
    }

    @Test
    fun `leaving before the warm starts does not leave the other mailboxes cold`() {
        val warm = { repo.pageLimits.filter { it.second == SchoolMailListViewModel.PREFETCH_LIMIT } }
        vm.load()
        // Still inside the start grace, so the queue is parked and has touched nothing.
        assertEquals(0, warm().size)

        // Leaving here is what following a notification straight back out looks like.
        vm.stopPolling()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.PREFETCH_START_DELAY_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(0, warm().size)

        // Coming back has to re-arm it; nothing else would until a load, refresh or folder tap.
        vm.startPolling()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.PREFETCH_START_DELAY_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(setOf("草稿匣", "廣告信匣", "回收筒"), warm().map { it.first }.toSet())
        vm.stopPolling()
    }

    @Test
    fun `a resume with nothing left to warm does not go back to the server`() {
        val warm = { repo.pageLimits.filter { it.second == SchoolMailListViewModel.PREFETCH_LIMIT } }
        vm.load()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.PREFETCH_START_DELAY_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(3, warm().size)

        vm.stopPolling()
        vm.startPolling()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.PREFETCH_START_DELAY_MS + 1)
        main.dispatcher.scheduler.runCurrent()

        // The queue ran out before the pause, so resuming must not warm the same three again.
        assertEquals(3, warm().size)
        vm.stopPolling()
    }

    // --- delete and move -------------------------------------------------------------------

    @Test
    fun `delete in normal folder asks for confirmation and moves mail on confirm`() {
        repo.add("INBOX", mailSummary(1), mailSummary(2))
        vm.load()
        val row = vm.state.value.displayed.first { it.uid == 2L }
        vm.delete(row)
        assertEquals(SchoolMailListViewModel.PendingDelete(row, permanently = false, account.accountGeneration), vm.state.value.pendingDelete)

        vm.confirmDelete(false)
        assertNull(vm.state.value.pendingDelete)
        assertEquals(2, vm.state.value.displayed.size)
        assertTrue(repo.deleted.isEmpty())

        vm.delete(row)
        vm.confirmDelete(true)
        assertNull(vm.state.value.pendingDelete)
        assertEquals(listOf("INBOX" to 2L), repo.deleted)
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `delete in trash asks for permanent confirmation and deletes permanently on confirm`() {
        val trash = repo.resolved.nameOf(SpecialFolder.TRASH)!!
        repo.add(trash, mailSummary(7), mailSummary(8))
        vm.load()
        vm.selectFolder(FolderSelection.Real(trash))
        val row = vm.state.value.displayed.first { it.uid == 8L }
        vm.delete(row)
        assertEquals(SchoolMailListViewModel.PendingDelete(row, permanently = true, account.accountGeneration), vm.state.value.pendingDelete)

        vm.confirmDelete(false)
        assertNull(vm.state.value.pendingDelete)
        assertEquals(2, vm.state.value.displayed.size)
        assertTrue(repo.deleted.isEmpty())

        vm.delete(row)
        vm.confirmDelete(true)
        assertNull(vm.state.value.pendingDelete)
        assertEquals(listOf(trash to 8L), repo.deleted)
        assertEquals(listOf(7L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `delete failure surfaces action error and leaves the list intact`() {
        repo.add("INBOX", mailSummary(1))
        vm.load()
        val row = vm.state.value.displayed.single()
        repo.deleteError = MailError.Network()
        vm.delete(row)
        vm.confirmDelete(true)

        assertTrue(vm.state.value.actionError is MailError.Network)
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `moveTo moves mail to target folder and removes row from list`() {
        repo.add("INBOX", mailSummary(1), mailSummary(2))
        vm.load()
        val row = vm.state.value.displayed.first { it.uid == 2L }
        val target = repo.resolved.nameOf(SpecialFolder.TRASH)!!

        vm.requestMove(row)
        assertEquals(row, vm.state.value.pendingMove?.row)
        val targets = vm.state.value.pendingMove!!.targets
        assertFalse("target folders cannot include own folder", targets.any { it.folder == row.folder })
        assertTrue(SchoolMailListViewModel.MoveTarget(target, SpecialFolder.TRASH) in targets)
        assertTrue("a folder of the student's own is offered by its name", SchoolMailListViewModel.MoveTarget("Moodle 課程討論區", null) in targets)
        vm.dismissMove()
        assertNull(vm.state.value.pendingMove)
        vm.moveTo(target)
        assertTrue("a move dismissed is not made", repo.moved.isEmpty())

        vm.requestMove(row)
        vm.moveTo(target)
        assertNull(vm.state.value.pendingMove)
        assertEquals(listOf(Triple("INBOX", 2L, target)), repo.moved)
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `move failure surfaces action error and leaves list intact`() {
        repo.add("INBOX", mailSummary(1))
        vm.load()
        val row = vm.state.value.displayed.single()
        repo.moveError = MailError.ServerBusy()
        val target = repo.resolved.nameOf(SpecialFolder.TRASH)!!
        vm.requestMove(row)
        vm.moveTo(target)

        assertTrue(vm.state.value.actionError is MailError.ServerBusy)
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `a delete asked before another student signed in over this one asks them nothing`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        val row = vm.state.value.displayed.single()
        // The first student's swipe waits on the server to say whether the delete is permanent:
        // with no trash yet, that answer costs a CREATE and a LIST.
        val gate = CompletableDeferred<Unit>()
        repo.trashGate = gate
        vm.delete(row)
        signOutAndSignInAnother()
        vm.loadIfNeeded()
        repo.trashGate = null
        gate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
        assertNull("the first student's question was put to the second", vm.state.value.pendingDelete)
        vm.confirmDelete(true)
        assertTrue(repo.deleted.isEmpty())
    }

    @Test
    fun `a delete question whose answer fails after its student signed in again is not shown`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        val gate = CompletableDeferred<Unit>()
        repo.trashGate = gate
        vm.delete(vm.state.value.displayed.single())
        // The same student signs in again, with no sign-out between: the question is still out,
        // as no other student's sign-in would leave it, and its failure reaches it.
        runBlocking { account.signIn("b10000001", "pw") }
        repo.trashGate = null
        // The password the question was asked under is rejected on the way.
        gate.completeExceptionally(MailError.AuthFailed())
        main.dispatcher.scheduler.runCurrent()
        assertNull(vm.state.value.pendingDelete)
        assertNull(vm.state.value.actionError)
        assertFalse("the old password's rejection marked the new sign-in rejected", account.authFailed.value)
    }

    @Test
    fun `a move question whose folders fail after its student signed in again is not shown`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        // The folder list the question needs is out on the connection.
        val gate = CompletableDeferred<Unit>()
        repo.foldersAnswerGate = gate
        vm.requestMove(vm.state.value.displayed.single())
        repo.foldersAnswerGate = null
        runBlocking { account.signIn("b10000001", "pw") }
        gate.completeExceptionally(MailError.AuthFailed())
        main.dispatcher.scheduler.runCurrent()
        assertNull(vm.state.value.pendingMove)
        assertNull(vm.state.value.actionError)
        assertFalse("the old password's rejection marked the new sign-in rejected", account.authFailed.value)
    }

    @Test
    fun `a delete that ends after another student signed in over this one leaves their list alone`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        // The first student confirms a delete, and the write waits on the connection.
        val gate = CompletableDeferred<Unit>()
        repo.writeGate = gate
        vm.delete(vm.state.value.displayed.single())
        vm.confirmDelete(true)
        // Another student signs in over the first, whose INBOX holds a mail under the same UID.
        signInAnotherOverThisOne()
        vm.loadIfNeeded()
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })
        repo.writeGate = null
        gate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
        assertEquals("the first student's delete took the second's mail off the list", listOf(1L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `a delete that fails after another student signed in over this one is not theirs to see`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        val gate = CompletableDeferred<Unit>()
        repo.writeGate = gate
        repo.deleteError = MailError.AuthFailed()
        vm.delete(vm.state.value.displayed.single())
        vm.confirmDelete(true)
        signInAnotherOverThisOne()
        vm.loadIfNeeded()
        repo.writeGate = null
        gate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
        assertNull(vm.state.value.actionError)
        assertFalse("the first student's rejected password locked the second out", account.authFailed.value)
    }

    @Test
    fun `a move that ends after another student signed in over this one leaves their list alone`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        val gate = CompletableDeferred<Unit>()
        repo.writeGate = gate
        vm.requestMove(vm.state.value.displayed.single())
        vm.moveTo(repo.resolved.nameOf(SpecialFolder.JUNK)!!)
        signInAnotherOverThisOne()
        vm.loadIfNeeded()
        repo.writeGate = null
        gate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
        assertEquals("the first student's move took the second's mail off the list", listOf(1L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `a move that fails after another student signed in over this one is not theirs to see`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        val gate = CompletableDeferred<Unit>()
        repo.writeGate = gate
        repo.moveError = MailError.AuthFailed()
        vm.requestMove(vm.state.value.displayed.single())
        vm.moveTo(repo.resolved.nameOf(SpecialFolder.JUNK)!!)
        signOutAndSignInAnother()
        vm.loadIfNeeded()
        repo.writeGate = null
        gate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
        assertNull(vm.state.value.actionError)
        assertFalse("the first student's rejected password locked the second out", account.authFailed.value)
    }

    @Test
    fun `a mail whose delete is still out is not deleted, moved or marked again`() {
        repo.add("INBOX", mailSummary(1), mailSummary(2))
        vm.loadIfNeeded()
        val row = vm.state.value.displayed.first { it.uid == 2L }
        // The delete waits on the connection, and the mail stays on screen until it is done.
        val gate = CompletableDeferred<Unit>()
        repo.writeGate = gate
        vm.delete(row)
        vm.confirmDelete(true)
        assertEquals("the card does not say its delete is out", setOf(row.key), vm.state.value.writing)
        // So it is swiped again, and moved and marked through its menu.
        vm.delete(row)
        assertNull("a mail being deleted was asked about again", vm.state.value.pendingDelete)
        vm.requestMove(row)
        assertNull("a mail being deleted was offered a move", vm.state.value.pendingMove)
        vm.toggleRead(row)
        // The mail next to it is its own.
        val other = vm.state.value.displayed.first { it.uid == 1L }
        vm.delete(other)
        assertEquals(other, vm.state.value.pendingDelete?.row)
        vm.confirmDelete(false)
        repo.writeGate = null
        gate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
        assertEquals("a second delete went out", listOf("INBOX" to 2L), repo.deleted)
        assertTrue(repo.seenCalls.isEmpty())
        assertTrue(vm.state.value.writing.isEmpty())
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `a mail whose move is still out is not moved or deleted again`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        val row = vm.state.value.displayed.single()
        val junk = repo.resolved.nameOf(SpecialFolder.JUNK)!!
        val gate = CompletableDeferred<Unit>()
        repo.writeGate = gate
        vm.requestMove(row)
        vm.moveTo(junk)
        vm.requestMove(row)
        vm.moveTo(junk)
        vm.delete(row)
        vm.confirmDelete(true)
        repo.writeGate = null
        gate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
        assertEquals("a second COPY went out", listOf(Triple("INBOX", 1L, junk)), repo.moved)
        assertTrue(repo.deleted.isEmpty())
        assertTrue(vm.state.value.displayed.isEmpty())
    }

    @Test
    fun `a mail whose delete failed can be deleted again`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        val row = vm.state.value.displayed.single()
        repo.deleteError = MailError.Network()
        vm.delete(row)
        vm.confirmDelete(true)
        assertTrue(vm.state.value.actionError is MailError.Network)
        assertTrue("a failed delete held its card", vm.state.value.writing.isEmpty())
        vm.dismissActionError()
        repo.deleteError = null
        vm.delete(row)
        vm.confirmDelete(true)
        assertEquals(listOf("INBOX" to 1L), repo.deleted)
        assertTrue(vm.state.value.displayed.isEmpty())
    }

    @Test
    fun `a second swipe before the first delete's question is up leaves the first one asked`() {
        repo.add("INBOX", mailSummary(1), mailSummary(2))
        vm.loadIfNeeded()
        val first = vm.state.value.displayed.first { it.uid == 2L }
        val second = vm.state.value.displayed.first { it.uid == 1L }
        // The first swipe's answer is still out when the second row is swiped.
        val gate = CompletableDeferred<Unit>()
        repo.trashGate = gate
        vm.delete(first)
        vm.delete(second)
        repo.trashGate = null
        gate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
        assertEquals("the second swipe dropped the first delete without a word", first, vm.state.value.pendingDelete?.row)
        vm.confirmDelete(true)
        assertNull(vm.state.value.pendingDelete)
        assertEquals(listOf("INBOX" to 2L), repo.deleted)
    }

    @Test
    fun `nothing else is asked while a question is up, and the next can be once it is answered`() {
        repo.add("INBOX", mailSummary(1), mailSummary(2))
        vm.loadIfNeeded()
        val first = vm.state.value.displayed.first { it.uid == 2L }
        val second = vm.state.value.displayed.first { it.uid == 1L }
        vm.delete(first)
        vm.delete(second)
        vm.requestMove(second)
        assertEquals(first, vm.state.value.pendingDelete?.row)
        assertNull("a move was asked over a delete's question", vm.state.value.pendingMove)
        vm.confirmDelete(false)

        vm.requestMove(second)
        vm.delete(first)
        assertEquals(second, vm.state.value.pendingMove?.row)
        assertNull("a delete was asked over a move's question", vm.state.value.pendingDelete)
        vm.dismissMove()

        vm.delete(second)
        assertEquals(second, vm.state.value.pendingDelete?.row)
    }

    @Test
    fun `a trash the first delete created is offered as a place to move mail to`() {
        // An account with no trash, as the list found it when it loaded.
        repo.resolved = ResolvedFolders(SpecialFolder.entries.filter { it != SpecialFolder.TRASH }.associateWith { it.decodedName }, emptyList())
        repo.add("INBOX", mailSummary(1), mailSummary(2))
        vm.loadIfNeeded()
        // The first delete's question created one on the server, and the repository knows it.
        repo.resolved = ResolvedFolders(SpecialFolder.entries.associateWith { it.decodedName }, emptyList())
        val row = vm.state.value.displayed.first { it.uid == 2L }
        vm.requestMove(row)
        val targets = vm.state.value.pendingMove!!.targets
        assertTrue("the trash created since is missing", SchoolMailListViewModel.MoveTarget(SpecialFolder.TRASH.decodedName, SpecialFolder.TRASH) in targets)
    }

    @Test
    fun `a move asked before another student signed in over this one asks them nothing`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        val row = vm.state.value.displayed.single()
        // The folders a move offers wait on the connection.
        val gate = CompletableDeferred<Unit>()
        repo.foldersGate = gate
        vm.requestMove(row)
        signOutAndSignInAnother()
        repo.foldersGate = null
        gate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
        assertNull("the first student's question was put to the second", vm.state.value.pendingMove)
        assertNull(vm.state.value.actionError)
    }

    @Test
    fun `a move whose folders cannot be read says so and asks nothing`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        repo.foldersError = MailError.Network()
        vm.requestMove(vm.state.value.displayed.single())
        assertNull(vm.state.value.pendingMove)
        assertTrue(vm.state.value.actionError is MailError.Network)
    }

    @Test
    fun `a delete or move still waiting for the connection when another student signs in is not sent`() {
        repo.add("INBOX", mailSummary(1), mailSummary(2))
        vm.loadIfNeeded()
        val first = vm.state.value.displayed.first { it.uid == 2L }
        val second = vm.state.value.displayed.first { it.uid == 1L }
        // Both writes are confirmed, and queue behind a request that holds the session.
        val gate = CompletableDeferred<Unit>()
        repo.writeQueueGate = gate
        vm.delete(first)
        vm.confirmDelete(true)
        vm.requestMove(second)
        vm.moveTo(repo.resolved.nameOf(SpecialFolder.JUNK)!!)
        // Another student signs in over the first, whose INBOX hands out the same UIDs.
        signInAnotherOverThisOne()
        repo.writeQueueGate = null
        gate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
        assertTrue("the first student's delete went out as the second", repo.deleted.isEmpty())
        assertTrue("the first student's move went out as the second", repo.moved.isEmpty())
        assertNull(vm.state.value.actionError)
    }

    @Test
    fun `a mail whose write was out while its student signed in again is given back once the write ends`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        val gate = CompletableDeferred<Unit>()
        repo.writeGate = gate
        repo.deleteError = MailError.Network()
        vm.delete(vm.state.value.displayed.single())
        vm.confirmDelete(true)
        // The same student signs in again with no sign-out between, as the sheet a rejected
        // password shows allows: the account moves, and nothing resets the list.
        runBlocking { account.signIn("b10000001", "pw") }
        vm.loadIfNeeded()
        repo.writeGate = null
        gate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
        assertTrue("the card stayed held after its write ended", vm.state.value.writing.isEmpty())
        repo.deleteError = null
        val row = vm.state.value.displayed.single()
        vm.delete(row)
        assertEquals(row, vm.state.value.pendingDelete?.row)
    }

    @Test
    fun `a write that ends after another student signed in leaves their own write of the same mail held`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        val first = CompletableDeferred<Unit>()
        repo.writeGate = first
        vm.delete(vm.state.value.displayed.single())
        vm.confirmDelete(true)
        signInAnotherOverThisOne()
        vm.loadIfNeeded()
        // The second student deletes the mail under the same folder and UID in their own INBOX.
        val second = CompletableDeferred<Unit>()
        repo.writeGate = second
        val row = vm.state.value.displayed.single()
        vm.delete(row)
        vm.confirmDelete(true)
        first.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
        assertEquals("the first student's write gave back the second's card", setOf(row.key), vm.state.value.writing)
        repo.writeGate = null
        second.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.writing.isEmpty())
    }

    @Test
    fun `a delete or move asked before its folder was found recreated is not made`() {
        repo.add("INBOX", mailSummary(1), mailSummary(2))
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.loadIfNeeded()
        val row = vm.state.value.displayed.first { it.uid == 2L }
        vm.delete(row)
        // While the question is up, the next page -- a poll's -- finds the folder recreated on the
        // server, where UID 2 is another mail altogether.
        repo.pageValidity = 2
        repo.mail["INBOX"] = mutableListOf(mailSummary(2, subject = "another mail"))
        vm.refresh()
        vm.confirmDelete(true)
        assertTrue("the delete went out for another mail under the same UID", repo.deleted.isEmpty())
        assertTrue(vm.state.value.actionError is MailError.FolderChanged)
        vm.dismissActionError()

        // A move asked now is of the mail on screen now, and goes out.
        vm.requestMove(vm.state.value.displayed.single())
        vm.moveTo(repo.resolved.nameOf(SpecialFolder.JUNK)!!)
        assertEquals(listOf(Triple("INBOX", 2L, repo.resolved.nameOf(SpecialFolder.JUNK)!!)), repo.moved)
    }

    @Test
    fun `a mail moved between the folders All mail merges stays on its list`() {
        val sent = repo.resolved.nameOf(SpecialFolder.SENT)!!
        repo.add("INBOX", mailSummary(1), mailSummary(2, subject = "moved to sent"))
        repo.add(sent, mailSummary(3))
        vm.loadIfNeeded()
        assertEquals(FolderSelection.AllMail, vm.state.value.selected)
        val row = vm.state.value.displayed.first { it.folder == "INBOX" && it.uid == 2L }
        vm.requestMove(row)
        vm.moveTo(sent)
        val moved = vm.state.value.displayed.filter { it.summary.subject == "moved to sent" }
        assertEquals("the moved mail left All mail, which still merges where it went", listOf(sent), moved.map { it.folder })

        // A folder off screen is left to be fetched when it appears.
        val loads = repo.loadsSent.size
        vm.requestMove(vm.state.value.displayed.first { it.folder == "INBOX" })
        vm.moveTo(repo.resolved.nameOf(SpecialFolder.JUNK)!!)
        assertEquals(loads, repo.loadsSent.size)
    }

    @Test
    fun `a delete question still waiting for the connection when the account moves asks the server nothing`() {
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        val gate = CompletableDeferred<Unit>()
        repo.trashGate = gate
        vm.delete(vm.state.value.displayed.single())
        // The same student signs in again, which leaves the question out rather than cancelling it.
        runBlocking { account.signIn("b10000001", "pw") }
        repo.trashGate = null
        gate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
        assertEquals("the question went to the server under the new sign-in", 0, repo.trashQuestionsSent)
        assertNull(vm.state.value.pendingDelete)
        assertNull(vm.state.value.actionError)
    }

    @Test
    fun `a delete or move asked before its folder was recreated, unseen by the list, is refused by the server`() {
        val trash = repo.resolved.nameOf(SpecialFolder.TRASH)!!
        repo.add(trash, mailSummary(7))
        repo.add("INBOX", mailSummary(1))
        vm.loadIfNeeded()
        vm.selectFolder(FolderSelection.Real(trash))
        vm.delete(vm.state.value.displayed.single())
        // While the question is up the trash is recreated on the server, and no page of it has
        // reached the list: only the server can tell.
        repo.pageValidity = 2
        vm.confirmDelete(true)
        assertTrue("a permanent delete went out under another UIDVALIDITY", repo.deleted.isEmpty())
        assertTrue(vm.state.value.actionError is MailError.FolderChanged)
        assertTrue(vm.state.value.writing.isEmpty())
        vm.dismissActionError()

        // A move of the same mail, asked under the UIDVALIDITY the list still has, is refused alike.
        vm.requestMove(vm.state.value.displayed.single())
        vm.moveTo("INBOX")
        assertTrue("a move went out under another UIDVALIDITY", repo.moved.isEmpty())
        assertTrue(vm.state.value.actionError is MailError.FolderChanged)
    }

    @Test
    fun `a search row's delete carries the folder version the search read it under`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考"))
        // The first page fails with nothing cached, so the list knows no version of the folder.
        repo.loadError = MailError.Network()
        vm.loadIfNeeded()
        vm.setSearchText("期中")
        vm.submitSearch()
        val row = vm.state.value.displayed.single()
        vm.delete(row)
        // The folder is recreated on the server while the question is up, its UIDs given out again.
        repo.pageValidity = 2
        vm.confirmDelete(true)
        assertTrue("the delete went out unchecked for another mail under the same UID", repo.deleted.isEmpty())
        assertTrue(vm.state.value.actionError is MailError.FolderChanged)
    }

    @Test
    fun `a row whose folder version is not known is neither deleted nor moved`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考"))
        repo.loadError = MailError.Network()
        vm.loadIfNeeded()
        // A search whose server did not say which version of the folder it read.
        repo.searchValidity = null
        vm.setSearchText("期中")
        vm.submitSearch()
        val row = vm.state.value.displayed.single()
        vm.delete(row)
        vm.confirmDelete(true)
        assertTrue("a delete went out with no version to check", repo.deleted.isEmpty())
        assertTrue(vm.state.value.actionError is MailError.FolderChanged)
        vm.dismissActionError()
        vm.requestMove(row)
        vm.moveTo(repo.resolved.nameOf(SpecialFolder.JUNK)!!)
        assertTrue("a move went out with no version to check", repo.moved.isEmpty())
    }

    @Test
    fun `a fresh search after its folder was recreated deletes what it found, and the list follows`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考 old"))
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.loadIfNeeded()
        // The folder is recreated on the server, its UID given to another mail, and no page of it
        // has reached the list since.
        repo.pageValidity = 2
        repo.searchValidity = 2
        repo.mail["INBOX"] = mutableListOf(mailSummary(1, subject = "期中考 new"))
        vm.setSearchText("期中")
        vm.submitSearch()
        val row = vm.state.value.displayed.single()
        assertEquals("期中考 new", row.summary.subject)
        assertEquals("the list kept the old mailbox's rows", listOf("期中考 new"), vm.state.value.messages.map { it.summary.subject })

        vm.delete(row)
        vm.confirmDelete(true)
        assertEquals("a row the search read under the folder's new version was refused", listOf("INBOX" to 1L), repo.deleted)
        assertNull(vm.state.value.actionError)
    }

    @Test
    fun `a list row read before its folder was recreated is refused by the server, not the list`() {
        repo.add("INBOX", mailSummary(1), mailSummary(2))
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.loadIfNeeded()
        val row = vm.state.value.displayed.first { it.uid == 2L }
        vm.delete(row)
        // While the question is up the folder is recreated, and the list reads its new version.
        repo.pageValidity = 2
        vm.refresh()
        vm.confirmDelete(true)
        assertEquals("the write never reached the server to be judged", 1, repo.writesRefused)
        assertTrue(repo.deleted.isEmpty())
        assertTrue(vm.state.value.actionError is MailError.FolderChanged)
    }

    @Test
    fun `a search's older look at a folder reloaded meanwhile does not drop the newer one`() {
        val sent = repo.resolved.nameOf(SpecialFolder.SENT)!!
        repo.add("INBOX", mailSummary(1, subject = "期中考"))
        repo.add(sent, mailSummary(2))
        vm.loadIfNeeded()
        // The server cannot search, so the inbox answers from the mail loaded under version 1,
        // and the sent folder's search is held while the list reads both folders again, recreated.
        repo.searchUnsupported = true
        val gate = CompletableDeferred<Unit>()
        repo.onSearched = { folder -> if (folder == "INBOX") repo.searchGate = gate }
        vm.setSearchText("期中")
        vm.submitSearch()
        repo.onSearched = null
        repo.searchGate = null
        repo.pageValidity = 2
        vm.refresh()
        gate.complete(Unit)
        main.dispatcher.scheduler.runCurrent()
        assertTrue("the newer page was dropped as the old mailbox's", repo.droppedCaches.isEmpty())
    }

    @Test
    fun `a certificate failure stays on screen when its search is cleared while a recreated folder reloads`() {
        val sent = repo.resolved.nameOf(SpecialFolder.SENT)!!
        repo.add("INBOX", mailSummary(1, subject = "期中考"))
        vm.loadIfNeeded()
        // The inbox's search finds it recreated; the sent folder's cannot trust the certificate.
        repo.searchValidity = 2
        repo.searchErrorFolder = sent
        repo.searchError = MailError.Certificate()
        // The search is cleared while the recreated inbox is set right.
        repo.onDropCachedPage = { vm.setSearchText("") }
        vm.setSearchText("期中")
        vm.submitSearch()
        main.dispatcher.scheduler.runCurrent()
        assertTrue("the certificate failure went unshown", vm.state.value.loadState.let { it is SchoolMailListViewModel.LoadState.Failed && it.error is MailError.Certificate })
    }

    @Test
    fun `a recreated folder a search finds as the server goes out of reach is not fetched at once`() {
        val sent = repo.resolved.nameOf(SpecialFolder.SENT)!!
        repo.add("INBOX", mailSummary(1, subject = "期中考 old"))
        vm.loadIfNeeded()
        val loads = repo.loadsSent.size
        // The inbox's search finds it recreated, and then the connection is gone for the sent folder's.
        repo.searchValidity = 2
        repo.searchErrorFolder = sent
        repo.searchError = MailError.Network()
        vm.setSearchText("期中")
        vm.submitSearch()
        assertEquals("the list asked an unreachable server again at once", loads, repo.loadsSent.size)
        assertTrue("the old mailbox's rows stayed", vm.state.value.messages.none { it.folder == "INBOX" })
        assertEquals(listOf("INBOX" to 1L), repo.droppedCaches)
    }
}
