@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.mail.compose.MessageBuilder
import org.ntust.app.tigerduck.mail.index.InMemoryMailSearchIndex
import org.ntust.app.tigerduck.mail.index.RecordingIndexScheduler
import org.ntust.app.tigerduck.mail.smtp.MailSender
import org.ntust.app.tigerduck.mail.smtp.MailTransport
import org.ntust.app.tigerduck.mail.store.MailCache

/**
 * A batch of mark-reads ([SchoolMailRepository.setSeen]): one `UID STORE`, sent only as the
 * account of the sign-in it was asked under and only to the folder version its mail was read in.
 */
class MailRepositorySeenBatchTest {
    @get:Rule val tmp = TemporaryFolder()

    private val appScope = testApplicationScope()

    @After
    fun stopTheAppScope() = appScope.cancelAndJoinBlocking()

    private val server = FakeMailServer().apply {
        passwords["B10000001"] = "pw"
        passwords["B10000002"] = "pw"
    }
    private val state = InMemoryMailStateStore()

    private fun setUp(scope: kotlinx.coroutines.CoroutineScope): Pair<MailAccount, MailRepository> {
        val cache = MailCache(tmp.root)
        val account = MailAccount(
            InMemoryCredentialStore(), state, server.factory(), cache, FakeDemoGate(), schoolMailSite(),
            RecordingScheduler(), RecordingNotifier(), appScope,
        )
        val repository = MailRepository(
            account, server.factory(), cache, state, MailSender(MessageBuilder(), MailTransport { _, _ -> }, pause = {}),
            MessageBuilder(), FakeDemoGate(), InMemoryMailSearchIndex(), RecordingIndexScheduler(), scope,
        )
        return account to repository
    }

    private fun unread(folder: String = "INBOX") =
        server.folders.getValue(folder).filter { !it.summary.flags.seen }.map { it.summary.uid }

    @Test
    fun `a batch is one STORE, as the account it was asked under, and each mail is told to the list`() = runTest {
        val (account, repo) = setUp(backgroundScope)
        val uids = listOf(server.deliver("a"), server.deliver("b"), server.deliver("c"))
        account.signIn("b10000001", "pw")
        val signIn = account.signInNow
        val changes = mutableListOf<MailChange>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { repo.changes.collect { changes += it } }

        repo.setSeen(signIn, "INBOX", server.uidValidity, uids, seen = true)
        runCurrent()

        assertEquals(listOf(FakeMailServer.SeenStore("B10000001", "INBOX", uids, true)), server.seenStores.toList())
        assertEquals(emptyList<Long>(), unread())
        assertEquals(uids.map { MailChange.Seen("INBOX", it, true, signIn) }, changes)
    }

    @Test
    fun `asked under a sign-in another student has since replaced, it sends nothing as them`() = runTest {
        val (account, repo) = setUp(backgroundScope)
        val uids = listOf(server.deliver("a"), server.deliver("b"))
        account.signIn("b10000001", "pw")
        // What the list read its rows under, before the next student signed in over the first.
        val first = account.signInNow
        account.signIn("b10000002", "pw")

        val sent = runCatching { repo.setSeen(first, "INBOX", server.uidValidity, uids, seen = true) }

        assertTrue(sent.exceptionOrNull() is MailError.Abandoned)
        assertEquals(emptyList<FakeMailServer.SeenStore>(), server.seenStores.toList())
        assertEquals(uids, unread())
    }

    @Test
    fun `under a UIDVALIDITY the folder no longer has, it sends nothing and the list is told to read it again`() = runTest {
        val (account, repo) = setUp(backgroundScope)
        val uids = listOf(server.deliver("a"), server.deliver("b"))
        account.signIn("b10000001", "pw")
        val signIn = account.signInNow
        val readUnder = server.uidValidity
        // The folder is recreated: the same UIDs now name other mail.
        server.uidValidity = readUnder + 1
        val changes = mutableListOf<MailChange>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { repo.changes.collect { changes += it } }

        val sent = runCatching { repo.setSeen(signIn, "INBOX", readUnder, uids, seen = true) }
        runCurrent()

        assertTrue(sent.exceptionOrNull() is MailError.FolderChanged)
        assertEquals(emptyList<FakeMailServer.SeenStore>(), server.seenStores.toList())
        assertEquals(uids, unread())
        assertEquals(listOf(MailChange.Changed("INBOX", signIn)), changes)
    }

    @Test
    fun `a STORE refused once it was out reads each mail's flags back and tells the lists them, guessing none`() = runTest {
        val (account, repo) = setUp(backgroundScope)
        val uids = listOf(server.deliver("a"), server.deliver("b"), server.deliver("c"))
        account.signIn("b10000001", "pw")
        val signIn = account.signInNow
        // The server marks the first two, then ends the STORE with a NO.
        server.refuseSeenAfter = 2
        val changes = mutableListOf<MailChange>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { repo.changes.collect { changes += it } }

        val sent = runCatching { repo.setSeen(signIn, "INBOX", server.uidValidity, uids, seen = true) }
        runCurrent()

        assertTrue(sent.exceptionOrNull() is MailError.Protocol)
        assertEquals(uids.mapIndexed { i, uid -> MailChange.Seen("INBOX", uid, i < 2, signIn) }.toSet(), changes.toSet())
        // Read again, the folder shows what the server holds: the two the STORE reached, read.
        server.refuseSeenAfter = null
        val page = repo.loadPage("INBOX", null)
        assertEquals(uids.take(2).toSet(), page.messages.filter { it.flags.seen }.map { it.uid }.toSet())
    }

    @Test
    fun `after a STORE refused partway, older mail paged from the phone shows the server's flags`() = runTest {
        val (account, repo) = setUp(backgroundScope)
        val uids = (1..60).map { server.deliver("m$it") }
        account.signIn("b10000001", "pw")
        repo.updateSearchIndex()
        val newest = repo.loadPage("INBOX", null)
        // Five of the older mail, of which the server marks the first two before it refuses.
        val batch = uids.take(5)
        server.refuseSeenAfter = 2
        runCatching { repo.setSeen(account.signInNow, "INBOX", server.uidValidity, batch, seen = true) }
        server.refuseSeenAfter = null
        val fetched = server.pageFetches.size

        val older = repo.loadPage("INBOX", newest.nextBeforeSeq)

        assertEquals("the older page came from the phone", fetched, server.pageFetches.size)
        assertEquals(batch.take(2).toSet(), older.messages.filter { it.flags.seen }.map { it.uid }.toSet())
    }

    @Test
    fun `a batch larger than one STORE names goes in parts, each as it lands`() = runTest {
        val (account, repo) = setUp(backgroundScope)
        val uids = (1..MailRepository.SEEN_STORE_UIDS + 1).map { server.deliver("m$it") }
        account.signIn("b10000001", "pw")

        repo.setSeen(account.signInNow, "INBOX", server.uidValidity, uids, seen = true)

        assertEquals(listOf(MailRepository.SEEN_STORE_UIDS, 1), server.seenStores.map { it.uids.size })
        assertFalse(server.folders.getValue("INBOX").any { !it.summary.flags.seen })
    }
}
