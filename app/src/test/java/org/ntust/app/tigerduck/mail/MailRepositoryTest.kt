@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.mail.compose.MessageBuilder
import org.ntust.app.tigerduck.mail.compose.OutgoingMail
import org.ntust.app.tigerduck.mail.imap.MailSession
import org.ntust.app.tigerduck.mail.imap.MailSessionFactory
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.model.FolderStatus
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.model.MailPage
import org.ntust.app.tigerduck.mail.smtp.MailSender
import org.ntust.app.tigerduck.mail.smtp.MailTransport
import org.ntust.app.tigerduck.mail.smtp.SentCopy
import org.ntust.app.tigerduck.mail.store.MailCache
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MailRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeMailServer()
    private val state = InMemoryMailStateStore()
    private val credentials = InMemoryCredentialStore()
    private val demo = FakeDemoGate()
    private val sent = mutableListOf<String>()
    private val transport = MailTransport { _, message -> sent += message.messageId }

    private suspend fun TestSetup.signedIn(studentId: String = "b10000001", password: String = "pw"): MailRepository {
        account.signIn(studentId, password)
        return repository
    }

    private inner class TestSetup(
        scope: kotlinx.coroutines.CoroutineScope,
        /** What the repository opens its sessions with; the account signs in on the server itself. */
        sessions: MailSessionFactory = server.factory(),
    ) {
        val cache = MailCache(tmp.root)
        val notifier = RecordingNotifier()
        val account = MailAccount(credentials, state, server.factory(), cache, demo, schoolMailSite(), RecordingScheduler(), notifier, scope)
        val repository = MailRepository(
            account, sessions, cache, state,
            MailSender(MessageBuilder(), transport, pause = {}), MessageBuilder(), demo, scope,
        )

        /** Signs out as Settings does, and waits for the cleanup the sign-out then runs on its own. */
        fun signOutAndSettle() {
            val cleaned = CountDownLatch(1)
            notifier.onCancelAll = { cleaned.countDown() }
            account.signOut()
            assertTrue("sign-out's cleanup never ran", cleaned.await(5, TimeUnit.SECONDS))
            notifier.onCancelAll = null
        }
    }

    @Test
    fun `first page is cached and folders are resolved once`() = runTest {
        server.deliver("a"); server.deliver("b")
        val repo = TestSetup(backgroundScope).signedIn()
        assertEquals("回收筒", repo.folders().nameOf(SpecialFolder.TRASH))
        assertEquals(listOf("b", "a"), repo.loadPage("INBOX", null).messages.map { it.subject })
        assertEquals(listOf("b", "a"), repo.cachedPage("INBOX")!!.messages.map { it.subject })
    }

    /**
     * A page that lands after its account signed out is not cached: the sign-out cleared the
     * cache, and whoever signs in next must not find the departing account's page in it. Asked
     * under the cache's lock, which the clear takes too.
     */
    @Test
    fun `a page that lands after the sign-out is not cached`() = runTest {
        server.deliver("a")
        lateinit var setup: TestSetup
        val interrupting = MailSessionFactory { credentials ->
            val session = server.factory().open(credentials)
            object : MailSession by session {
                override fun fetchPage(folder: String, beforeSeq: Int?, pageSize: Int): MailPage {
                    val page = session.fetchPage(folder, beforeSeq, pageSize)
                    setup.signOutAndSettle()
                    return page
                }
            }
        }
        setup = TestSetup(backgroundScope, interrupting)
        val repo = setup.signedIn()

        assertEquals(listOf("a"), repo.loadPage("INBOX", null).messages.map { it.subject })

        assertNull(setup.cache.loadFolder("INBOX"))
    }

    /**
     * `stillWanted` is asked with the session held, right before the command: a request that
     * queued behind another and is no longer wanted when its turn comes is never sent, and ends
     * as [MailError.Abandoned].
     */
    @Test
    fun `a request no longer wanted once the session comes free is not sent`() = runTest {
        server.deliver("a")
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val statuses = AtomicInteger()
        val sessions = MailSessionFactory { credentials ->
            val session = server.factory().open(credentials)
            object : MailSession by session {
                override fun status(folder: String): FolderStatus {
                    statuses.incrementAndGet()
                    return session.status(folder)
                }

                override fun fetchBody(folder: String, uid: Long): MailBody {
                    holding.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    return session.fetchBody(folder, uid)
                }
            }
        }
        val setup = TestSetup(backgroundScope, sessions)
        val repo = setup.signedIn()
        val uid = repo.loadPage("INBOX", null).messages.single().uid

        // One request holds the session (after a STATUS of its own).
        val body = launch(Dispatchers.IO) { repo.body("INBOX", uid) }
        assertTrue(holding.await(5, TimeUnit.SECONDS))
        val sentBefore = statuses.get()
        // The STATUS reaches the wait for the session while still wanted...
        val reached = CountDownLatch(1)
        setup.repository.holder.onWait = { reached.countDown() }
        val wanted = AtomicBoolean(true)
        val status = async(Dispatchers.IO) { runCatching { repo.status("INBOX") { wanted.get() } } }
        assertTrue(reached.await(5, TimeUnit.SECONDS))
        // ...and is no longer wanted by the time the session comes free.
        wanted.set(false)
        release.countDown()

        assertTrue(status.await().exceptionOrNull() is MailError.Abandoned)
        assertEquals(sentBefore, statuses.get())
        body.join()
    }

    /**
     * Asked before the connection is touched, not only before the command: probing or opening
     * it logs in with the credentials the caller read, so a request of an account that has left
     * -- with the next one signed in -- must not log in as the next one. A refusal then would be
     * the next account's, dropped as the old one's.
     */
    @Test
    fun `a request of a departed account opens no session for the next one`() = runTest {
        val opens = AtomicInteger()
        val sessions = MailSessionFactory { credentials ->
            opens.incrementAndGet()
            server.factory().open(credentials)
        }
        val setup = TestSetup(backgroundScope, sessions)
        val repo = setup.signedIn()
        repo.loadPage("INBOX", null)
        val before = setup.account.accountGeneration
        setup.signOutAndSettle()
        server.passwords["B10000002"] = "pw"
        setup.account.signIn("b10000002", "pw")
        val opened = opens.get()

        val outcome = runCatching { repo.status("INBOX") { setup.account.accountGeneration == before } }

        assertTrue(outcome.exceptionOrNull() is MailError.Abandoned)
        assertEquals(opened, opens.get())
    }

    /** The folder list a first request resolves is a request too, and asked the same question. */
    @Test
    fun `a departed account's inbox status resolves no folders for the next one`() = runTest {
        val opens = AtomicInteger()
        val sessions = MailSessionFactory { credentials ->
            opens.incrementAndGet()
            server.factory().open(credentials)
        }
        val setup = TestSetup(backgroundScope, sessions)
        setup.signedIn()
        val before = setup.account.accountGeneration
        setup.signOutAndSettle()
        server.passwords["B10000002"] = "pw"
        setup.account.signIn("b10000002", "pw")
        val opened = opens.get()

        val outcome = runCatching { setup.repository.inboxStatus { setup.account.accountGeneration == before } }

        assertTrue(outcome.exceptionOrNull() is MailError.Abandoned)
        assertEquals(opened, opens.get())
    }

    /**
     * Another student can sign in over this one without a sign-out -- the one account change the
     * sign-out collector never sees. A LIST already out when that happens comes back under the new
     * student's generation, and must not be what their own first request finds resolved.
     */
    @Test
    fun `a folder list that returns after another student signed in over this one is not kept as theirs`() = runTest {
        val setup = TestSetup(backgroundScope)
        setup.signedIn()
        val before = setup.account.accountGeneration
        server.onListFolders = {
            server.onListFolders = null
            server.passwords["B10000002"] = "pw"
            runBlocking { setup.account.signIn("b10000002", "pw") }
        }
        // The first student's LIST was sent, and comes back for nobody.
        setup.repository.folders { setup.account.accountGeneration == before }
        assertTrue(setup.account.accountGeneration != before)
        val lists = server.listCalls

        // The second student's first request asks the server, not what the first one's LIST resolved.
        setup.repository.folders()
        assertEquals(lists + 1, server.listCalls)
    }

    @Test
    fun `a dropped cached page is gone until the next first page writes a new one`() = runTest {
        server.deliver("a")
        val repo = TestSetup(backgroundScope).signedIn()
        repo.loadPage("INBOX", null)
        // Only a page of the generation found obsolete goes; this one is the server's current one.
        repo.dropCachedPage("INBOX", server.uidValidity + 1)
        assertNotNull(repo.cachedPage("INBOX"))
        repo.dropCachedPage("INBOX", server.uidValidity)
        assertNull(repo.cachedPage("INBOX"))
        // Dropping a folder that has no cached page is nothing to fail over.
        repo.dropCachedPage("INBOX", server.uidValidity)
        assertEquals(listOf("a"), repo.loadPage("INBOX", null).messages.map { it.subject })
        assertEquals(listOf("a"), repo.cachedPage("INBOX")!!.messages.map { it.subject })
    }

    @Test
    fun `dropping a cached page leaves the demo mailbox as it is, having no cache behind it`() = runTest {
        val repo = TestSetup(backgroundScope).signedIn("B10000099", "demo")
        // The demo page reports validity 1, so this names exactly the page a real cache would drop.
        repo.dropCachedPage("INBOX", 1)
        assertEquals(listOf("期中考時間公告"), repo.cachedPage("INBOX")!!.messages.map { it.subject })
        assertEquals(0, server.opens)
    }

    @Test
    fun `a mail's source is downloaded once and served from the cache on every later visit`() = runTest {
        server.deliver("a")
        val repo = TestSetup(backgroundScope).signedIn()
        val uid = repo.loadPage("INBOX", null).messages.single().uid
        val source = repo.rawSource("INBOX", uid)
        assertTrue(source.contains("Subject: a"))
        assertEquals(1, server.rawSourceFetches)
        assertEquals(source, repo.rawSource("INBOX", uid))
        assertEquals("revisiting a mail's source must not re-download it", 1, server.rawSourceFetches)
    }

    /**
     * The source is buffered whole and then decoded into a String, so a mail near the server's
     * own 50 MB SMTP SIZE limit would cost hundreds of megabytes before the cache's own ceiling
     * could decline to keep it -- and an OutOfMemoryError is an Error, so nothing along this
     * path catches it. The size the list already knows decides, before a byte is fetched.
     */
    @Test
    fun `a mail over the source ceiling is refused before anything is downloaded`() = runTest {
        val uid = server.deliver("huge", sizeBytes = MailLimits.SOURCE_BYTES + 1)
        val repo = TestSetup(backgroundScope).signedIn()
        repo.loadPage("INBOX", null)
        val error = runCatching { repo.rawSource("INBOX", uid) }.exceptionOrNull()
        assertTrue("expected TooLarge, got $error", error is MailError.TooLarge)
        assertEquals("nothing may be fetched once it is refused", 0, server.rawSourceFetches)
    }

    @Test
    fun `a mail exactly at the source ceiling still loads`() = runTest {
        val uid = server.deliver("big", sizeBytes = MailLimits.SOURCE_BYTES)
        val repo = TestSetup(backgroundScope).signedIn()
        repo.loadPage("INBOX", null)
        assertTrue(repo.rawSource("INBOX", uid).contains("Subject: big"))
    }

    @Test
    fun `bodies come from the cache the second time`() = runTest {
        val uid = server.deliver("a")
        val repo = TestSetup(backgroundScope).signedIn()
        repo.loadPage("INBOX", null)
        assertEquals("body of a", repo.body("INBOX", uid).plain)
        repo.release()
        server.folders.getValue("INBOX").clear()
        assertEquals("body of a", repo.body("INBOX", uid).plain)
    }

    @Test
    fun `marking read updates the cached page`() = runTest {
        val uid = server.deliver("a")
        val repo = TestSetup(backgroundScope).signedIn()
        repo.loadPage("INBOX", null)
        repo.setSeen("INBOX", uid, true)
        assertTrue(repo.cachedPage("INBOX")!!.messages.single().flags.seen)
    }

    @Test
    fun `delete moves to trash, and deleting in trash is permanent`() = runTest {
        val uid = server.deliver("a")
        val repo = TestSetup(backgroundScope).signedIn()
        repo.loadPage("INBOX", null)
        assertFalse(repo.deletesPermanently("INBOX"))
        repo.delete("INBOX", uid)
        assertEquals(listOf("a"), server.subjects("回收筒"))
        assertTrue(repo.cachedPage("INBOX")!!.messages.isEmpty())
        val trashUid = server.folders.getValue("回收筒").single().summary.uid
        assertTrue(repo.deletesPermanently("回收筒"))
        repo.delete("回收筒", trashUid)
        assertTrue(server.subjects("回收筒").isEmpty())
    }

    @Test
    fun `a move that could not expunge remembers the mail as ours`() = runTest {
        val uid = server.deliver("a")
        server.expungeOnMove = false
        val repo = TestSetup(backgroundScope).signedIn()
        repo.move("INBOX", uid, "回收筒")
        assertEquals(setOf(uid), state.ownedDeleted("INBOX", server.uidValidity))
    }

    /**
     * Two deletes overlapping in the same folder. The first is held between reading the
     * owned-\Deleted set and writing it back -- exactly where the second one's own read used to
     * slip in. Both UIDs have to survive: losing one leaves `ours` a permanent strict subset of
     * the server's \Deleted set, and the folder can never be expunged again.
     *
     * With the read and the write under one hold of the session mutex the second delete cannot
     * start at all while the first is paused, so the pause simply runs out its budget and the
     * two deletes serialize. Without it, the second finishes inside that window and its write
     * lands first, so the first delete's write overwrites it.
     */
    @Test
    fun `two overlapping deletes in one folder both stay in the owned set`() = runTest {
        val first = server.deliver("a")
        val second = server.deliver("b")
        server.expungeOnMove = false // nothing ever expunges, so each delete adds to the owned set
        val repo = TestSetup(backgroundScope).signedIn()

        val firstHoldsTheSession = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        state.onOwnedDeletedRead = { firstHoldsTheSession.countDown() }
        state.beforeSetOwnedDeleted = { uids ->
            if (uids == setOf(first)) secondFinished.await(1, TimeUnit.SECONDS)
        }
        // A real dispatcher, so the second delete keeps running while the first is paused.
        backgroundScope.launch(Dispatchers.IO) {
            firstHoldsTheSession.await(10, TimeUnit.SECONDS)
            runCatching { repo.move("INBOX", second, "回收筒") }
            secondFinished.countDown()
        }

        repo.move("INBOX", first, "回收筒")
        assertTrue(secondFinished.await(10, TimeUnit.SECONDS))
        assertEquals(setOf(first, second), state.ownedDeleted("INBOX", server.uidValidity))
    }

    @Test
    fun `a move that fails before reaching the server is not recorded as ours`() = runTest {
        val uid = server.deliver("a")
        val repo = TestSetup(backgroundScope).signedIn()
        repo.loadPage("INBOX", null)
        // Skips the held connection's liveness probe and the fresh-UIDVALIDITY
        // check (both harmless nulls) so the induced error lands on the move itself.
        server.queueCallErrors(null, null, MailError.Protocol("boom"))
        val result = runCatching { repo.move("INBOX", uid, "回收筒") }
        assertTrue(result.exceptionOrNull() is MailError.Protocol)
        assertEquals(emptySet<Long>(), state.ownedDeleted("INBOX", server.uidValidity))
        assertEquals(listOf("a"), server.subjects("INBOX"))
    }

    @Test
    fun `a move that flags deleted before failing is recorded as ours`() = runTest {
        val uid = server.deliver("a")
        val repo = TestSetup(backgroundScope).signedIn()
        repo.loadPage("INBOX", null)
        server.failAfterFlag = true
        val result = runCatching { repo.move("INBOX", uid, "回收筒") }
        assertTrue(result.exceptionOrNull() is MailError.Network)
        assertEquals(setOf(uid), state.ownedDeleted("INBOX", server.uidValidity))
    }

    @Test
    fun `a permanent delete that fails before reaching the server is not recorded as ours`() = runTest {
        val uid = server.deliver("a", folder = "回收筒")
        val repo = TestSetup(backgroundScope).signedIn()
        repo.loadPage("回收筒", null)
        repo.folders()
        server.queueCallErrors(null, null, MailError.Protocol("boom"))
        val result = runCatching { repo.delete("回收筒", uid) }
        assertTrue(result.exceptionOrNull() is MailError.Protocol)
        assertEquals(emptySet<Long>(), state.ownedDeleted("回收筒", server.uidValidity))
        assertEquals(listOf("a"), server.subjects("回收筒"))
    }

    @Test
    fun `a move refuses to act once the folder's UIDVALIDITY has moved on from the cache`() = runTest {
        val uid = server.deliver("a")
        val repo = TestSetup(backgroundScope).signedIn()
        repo.loadPage("INBOX", null)
        server.uidValidity = 2
        val result = runCatching { repo.move("INBOX", uid, "回收筒") }
        assertTrue(result.exceptionOrNull() is MailError.FolderChanged)
        assertEquals(null, repo.cachedPage("INBOX"))
        assertEquals(listOf("a"), server.subjects("INBOX"))
    }

    @Test
    fun `an uncached body is saved under the server's current UIDVALIDITY, not the cached page's`() = runTest {
        val uid = server.deliver("a")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        repo.loadPage("INBOX", null)
        server.uidValidity = 2
        assertEquals("body of a", repo.body("INBOX", uid).plain)
        // The cached *page* is still keyed at 1 (loadPage was never re-run), but
        // the body itself must have landed under the server's current value, 2.
        assertNotNull(setup.cache.loadBody("INBOX", uid, 2))
        assertEquals(null, setup.cache.loadBody("INBOX", uid, 1))
    }

    @Test
    fun `a cached body is served for the cached page's own validity without touching the server`() = runTest {
        val uid = server.deliver("a")
        val repo = TestSetup(backgroundScope).signedIn()
        repo.loadPage("INBOX", null)
        repo.body("INBOX", uid)
        val opensBefore = server.opens
        assertEquals("body of a", repo.body("INBOX", uid).plain)
        assertEquals(opensBefore, server.opens)
    }

    @Test
    fun `search falls back to loaded mail when the server can't`() = runTest {
        server.deliver("期中考公告"); server.deliver("other")
        val repo = TestSetup(backgroundScope).signedIn()
        repo.loadPage("INBOX", null)
        assertTrue(repo.search("INBOX", "期中") is SearchOutcome.Server)
        server.searchUnsupported = true
        val local = repo.search("INBOX", "期中")
        assertTrue(local is SearchOutcome.LoadedOnly)
        assertEquals(listOf("期中考公告"), local.messages.map { it.subject })
    }

    @Test
    fun `search returns the newest page of matches and keeps the rest to fetch`() = runTest {
        val uids = (1..MailRepository.PAGE_SIZE + 2).map { server.deliver("期中考 $it") }
        val repo = TestSetup(backgroundScope).signedIn()
        val outcome = repo.search("INBOX", "期中") as SearchOutcome.Server
        assertEquals(uids.takeLast(MailRepository.PAGE_SIZE).reversed(), outcome.messages.map { it.uid })
        assertEquals(uids.take(2).reversed(), outcome.remaining)
        assertEquals(server.uidValidity, outcome.uidValidity)
        val page = repo.searchPage("INBOX", outcome.remaining)
        assertEquals(uids.take(2).reversed(), page.messages.map { it.uid })
        assertEquals(server.uidValidity, page.uidValidity)
    }

    @Test
    fun `sending uses the display name and marks the original answered`() = runTest {
        val uid = server.deliver("question")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        state.displayName = "測試"
        assertEquals(MailAddress("測試", "b10000001@mail.ntust.edu.tw"), repo.selfAddress())
        repo.send(
            OutgoingMail(repo.selfAddress(), listOf(MailAddress(null, "a@x.tw")), emptyList(), emptyList(), "Re: question", "hi"),
            answered = "INBOX" to uid,
        )
        assertEquals(1, sent.size)
        assertTrue(server.folders.getValue("INBOX").single().summary.flags.answered)
        assertEquals(listOf("Re: question"), server.subjects("寄件備份匣"))
    }

    /**
     * Mail2000 caps concurrent connections and answers `NO [UNAVAILABLE] Too many connections`,
     * so the sent copy opening one of its own would be refused precisely when the user is on the
     * mail screen holding the other. It goes over the held connection instead.
     */
    @Test
    fun `the sent copy reuses the held connection instead of opening a second one`() = runTest {
        server.deliver("a")
        val repo = TestSetup(backgroundScope).signedIn()
        repo.loadPage("INBOX", null)
        val opensBefore = server.opens
        assertEquals(1, server.openSessions)

        val copy = repo.send(
            OutgoingMail(repo.selfAddress(), listOf(MailAddress(null, "a@x.tw")), emptyList(), emptyList(), "s", "hi"),
            answered = null,
        )
        assertEquals(SentCopy.Filed, copy)
        assertEquals(listOf("s"), server.subjects("寄件備份匣"))
        assertEquals("no second connection was opened", opensBefore, server.opens)
        assertEquals(1, server.openSessions)
    }

    @Test
    fun `saving a draft replaces the old one`() = runTest {
        val repo = TestSetup(backgroundScope).signedIn()
        val draft = OutgoingMail(repo.selfAddress(), emptyList(), emptyList(), emptyList(), "draft 1", "x")
        repo.saveDraft(draft, replacingUid = null)
        val first = server.folders.getValue("草稿匣").single().summary.uid
        repo.saveDraft(draft.copy(subject = "draft 2"), replacingUid = first)
        assertEquals(listOf("draft 2"), server.subjects("草稿匣"))
    }

    @Test
    fun `discardDraft permanently removes a sent draft`() = runTest {
        val repo = TestSetup(backgroundScope).signedIn()
        val draft = OutgoingMail(repo.selfAddress(), emptyList(), emptyList(), emptyList(), "draft", "x")
        repo.saveDraft(draft, replacingUid = null)
        val uid = server.folders.getValue("草稿匣").single().summary.uid
        repo.discardDraft(uid)
        assertTrue(server.subjects("草稿匣").isEmpty())
    }

    @Test
    fun `a mail read, moved or deleted is told to whoever keeps a list of its folder`() = runTest {
        val uid = server.deliver("a")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        val generation = setup.account.accountGeneration
        val changes = mutableListOf<MailChange>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { repo.changes.collect { changes += it } }
        repo.loadPage("INBOX", null)
        repo.setSeen("INBOX", uid, true)
        repo.delete("INBOX", uid)
        val trashUid = server.folders.getValue("回收筒").single().summary.uid
        repo.delete("回收筒", trashUid)
        runCurrent()
        assertEquals(
            listOf(
                MailChange.Seen("INBOX", uid, true, generation),
                MailChange.Removed("INBOX", uid, generation),
                MailChange.Changed("回收筒", generation),
                MailChange.Removed("回收筒", trashUid, generation),
            ),
            changes,
        )
    }

    @Test
    fun `a sent copy and a saved or discarded draft are told the same way`() = runTest {
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        val generation = setup.account.accountGeneration
        val changes = mutableListOf<MailChange>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { repo.changes.collect { changes += it } }
        repo.send(OutgoingMail(repo.selfAddress(), listOf(MailAddress(null, "a@x.tw")), emptyList(), emptyList(), "s", "hi"), answered = null)
        val draft = OutgoingMail(repo.selfAddress(), emptyList(), emptyList(), emptyList(), "draft", "x")
        repo.saveDraft(draft, replacingUid = null)
        val uid = server.folders.getValue("草稿匣").single().summary.uid
        repo.discardDraft(uid)
        runCurrent()
        assertEquals(
            listOf(
                MailChange.Changed("寄件備份匣", generation),
                MailChange.Changed("草稿匣", generation),
                MailChange.Removed("草稿匣", uid, generation),
            ),
            changes,
        )
    }

    @Test
    fun `a write that ends after another student signed in over this one is told as the first student's`() = runTest {
        val uid = server.deliver("a")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        val first = setup.account.accountGeneration
        val changes = mutableListOf<MailChange>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { repo.changes.collect { changes += it } }
        server.passwords["B10000002"] = "pw"
        // The other student signs in while the first's mark-read is on the server; a list loaded
        // for them since must not take the write as theirs.
        server.onSetSeen = {
            server.onSetSeen = null
            runBlocking { setup.account.signIn("b10000002", "pw") }
        }
        repo.setSeen("INBOX", uid, true)
        runCurrent()
        assertTrue(first != setup.account.accountGeneration)
        assertEquals(listOf(MailChange.Seen("INBOX", uid, true, first)), changes)
    }

    @Test
    fun `discardDraft is a no-op in demo mode`() = runTest {
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn("B10000099", "demo")
        val opensBefore = server.opens
        repo.discardDraft(1001)
        assertEquals(opensBefore, server.opens)
    }

    @Test
    fun `discardDraft returns quietly when there is no drafts folder`() = runTest {
        server.folders.remove("草稿匣")
        val repo = TestSetup(backgroundScope).signedIn()
        repo.discardDraft(42) // must not throw
    }

    @Test
    fun `discardDraft records the draft as ours when the expunge fails after flagging`() = runTest {
        val repo = TestSetup(backgroundScope).signedIn()
        val draft = OutgoingMail(repo.selfAddress(), emptyList(), emptyList(), emptyList(), "draft", "x")
        repo.saveDraft(draft, replacingUid = null)
        val uid = server.folders.getValue("草稿匣").single().summary.uid
        server.failAfterFlag = true
        val result = runCatching { repo.discardDraft(uid) }
        assertTrue(result.exceptionOrNull() is MailError.Network)
        assertEquals(setOf(uid), state.ownedDeleted("草稿匣", server.uidValidity))
    }

    @Test
    fun `the demo mailbox is served without any connection`() = runTest {
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn("B10000099", "demo")
        val page = repo.loadPage("INBOX", null)
        assertEquals(listOf("期中考時間公告"), page.messages.map { it.subject })
        assertNotNull(repo.body("INBOX", 1001).html)
        repo.send(OutgoingMail(repo.selfAddress(), listOf(MailAddress(null, "a@x.tw")), emptyList(), emptyList(), "s", "b"), null)
        assertEquals(0, server.opens)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `opening, replying to and forwarding a demo message resolves it through summary and body alone, with no page ever loaded first`() = runTest {
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn("B10000099", "demo")
        // Nothing has called loadPage/cachedPage yet -- this is the exact order compose prefill
        // uses (SchoolMailComposeViewModel.runPrefill: summary() then body() then selfAddress()),
        // for a message screen opened straight from a fresh sign-in.
        val summary = repo.summary("INBOX", 1001)
        assertEquals("期中考時間公告", summary?.subject)
        assertEquals("教務處", summary?.from?.name)
        val body = repo.body("INBOX", 1001)
        assertEquals("期中考", body.plain)
        assertEquals("<p>期中考</p>", body.html)
        assertEquals(MailAddress("示範同學", "b10000099@mail.ntust.edu.tw"), repo.selfAddress())
        assertEquals(0, server.opens)
    }

    @Test
    fun `the demo mailbox seeds Drafts and Sent from the fixture, not just INBOX`() = runTest {
        val repo = TestSetup(backgroundScope).signedIn("B10000099", "demo")
        assertEquals(listOf("詢問事項"), repo.cachedPage("草稿匣")!!.messages.map { it.subject })
        assertEquals(listOf("Re: 期中考時間公告"), repo.cachedPage("寄件備份匣")!!.messages.map { it.subject })
        assertEquals(0, server.opens)
    }

    @Test
    fun `opening the seeded demo draft resolves it through summary and body alone, with no page ever loaded first`() = runTest {
        val repo = TestSetup(backgroundScope).signedIn("B10000099", "demo")
        val summary = repo.summary("草稿匣", 1002)
        assertEquals("詢問事項", summary?.subject)
        assertEquals(listOf("office2@mail.ntust.edu.tw"), summary?.to?.map { it.address })
        assertEquals("詢問內容", repo.body("草稿匣", 1002).plain)
        assertEquals(0, server.opens)
    }

    @Test
    fun `saving a demo draft replaces the one being edited, and reopening it under its new uid returns the saved content`() = runTest {
        val repo = TestSetup(backgroundScope).signedIn("B10000099", "demo")
        val edited = OutgoingMail(repo.selfAddress(), listOf(MailAddress(null, "new@x.tw")), emptyList(), emptyList(), "edited subject", "edited body")
        repo.saveDraft(edited, replacingUid = 1002)

        val drafts = repo.cachedPage("草稿匣")!!.messages
        assertEquals(1, drafts.size) // the old draft (1002) is gone, replaced by the new save
        val newUid = drafts.single().uid
        assertEquals("edited subject", drafts.single().subject)
        assertEquals(listOf("new@x.tw"), drafts.single().to.map { it.address })

        assertEquals("edited subject", repo.summary("草稿匣", newUid)?.subject)
        assertEquals("edited body", repo.body("草稿匣", newUid).plain)
        assertEquals(0, server.opens)
    }

    @Test
    fun `discarding a demo draft removes it from the demo mailbox`() = runTest {
        val repo = TestSetup(backgroundScope).signedIn("B10000099", "demo")
        repo.discardDraft(1002)
        assertTrue(repo.cachedPage("草稿匣")!!.messages.isEmpty())
        assertEquals(0, server.opens)
    }

    @Test
    fun `sending in demo mode appends the outgoing mail to Sent and marks the original answered, without a socket or a real send`() = runTest {
        val repo = TestSetup(backgroundScope).signedIn("B10000099", "demo")
        repo.send(
            OutgoingMail(repo.selfAddress(), listOf(MailAddress(null, "a@x.tw")), emptyList(), emptyList(), "reply subject", "reply body"),
            answered = "INBOX" to 1001,
        )
        val sentFolder = repo.cachedPage("寄件備份匣")!!.messages
        assertEquals(listOf("reply subject", "Re: 期中考時間公告"), sentFolder.map { it.subject })
        assertTrue(repo.summary("INBOX", 1001)!!.flags.answered)
        assertEquals(0, server.opens)
        assertTrue(sent.isEmpty()) // the fake SMTP transport was never invoked
    }

    @Test
    fun `app-wide demo mode blocks the real account from opening any connection`() = runTest {
        val uid = server.deliver("a")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        demo.appDemoActive = true
        val opensBefore = server.opens
        repo.folders()
        repo.loadPage("INBOX", null)
        runCatching { repo.body("INBOX", uid) }
        repo.search("INBOX", "a")
        runCatching { repo.move("INBOX", uid, "回收筒") }
        repo.send(OutgoingMail(repo.selfAddress(), listOf(MailAddress(null, "a@x.tw")), emptyList(), emptyList(), "s", "b"), null)
        assertEquals(opensBefore, server.opens)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `calling with no signed-in account fails with a dedicated error, not AuthFailed`() = runTest {
        val setup = TestSetup(backgroundScope)
        val result = runCatching { setup.repository.loadPage("INBOX", null) }
        assertTrue(result.exceptionOrNull() is MailError.Protocol)
    }

    @Test
    fun `a rejected password stops the repository logging in again, while signing in again still works`() = runTest {
        // Spec §7.4: not one more LOGIN with a password the server has already rejected --
        // it is the portal and campus Wi-Fi password, and repeated failures can lock it.
        server.deliver("a")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        setup.account.onAuthFailure()
        val opensBefore = server.opens

        val read = runCatching { repo.loadPage("INBOX", null) }
        assertTrue(read.exceptionOrNull() is MailError.AuthFailed)
        val send = runCatching {
            repo.send(OutgoingMail(repo.selfAddress(), listOf(MailAddress(null, "a@x.tw")), emptyList(), emptyList(), "s", "b"), null)
        }
        assertTrue(send.exceptionOrNull() is MailError.AuthFailed)
        assertEquals(opensBefore, server.opens)
        assertTrue(sent.isEmpty())

        // Re-authentication goes through MailAccount.signIn -> MailSessionFactory.open, which the
        // flag must never block, and succeeding clears it.
        assertNull(setup.account.signIn("b10000001", "pw"))
        assertFalse(setup.account.authFailed.value)
        assertEquals(listOf("a"), repo.loadPage("INBOX", null).messages.map { it.subject })
    }

    /**
     * The same sign-out, reached without the test body ever yielding to the
     * scheduler first. `withContext(Dispatchers.IO)` skips suspending when the
     * IO block finishes before the caller gets as far as suspending, and on a
     * Linux CI runner both hops here did: the repository's `signedIn`
     * collector then first ran after the sign-out, dropped it as the initial
     * value, and the connection stayed open. `runBlocking` pins that ordering
     * instead of leaving it to the OS scheduler.
     */
    @Test
    fun `a sign-out before the scheduler has run anything still closes the connection`() = runTest {
        val setup = TestSetup(backgroundScope)
        val repo = runBlocking { setup.signedIn() }
        runBlocking { repo.folders() }
        assertEquals(1, server.openSessions)
        setup.account.signOut()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (server.openSessions != 0) delay(10)
            }
        }
        assertEquals(0, server.openSessions)
    }

    @Test
    fun `signing out closes the held connection`() = runTest {
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        repo.folders()
        assertEquals(1, server.openSessions)
        setup.account.signOut()
        // The sign-out collector's close hops to a real Dispatchers.IO (so it
        // never blocks the application scope's Dispatchers.Default), so
        // runCurrent() alone can't flush it -- bridge with a short real-time
        // poll instead of asserting immediately.
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (server.openSessions != 0) delay(10)
            }
        }
        assertEquals(0, server.openSessions)
    }

    @Test
    fun `acquire cancels a pending idle close so re-opening the page doesn't drop the connection`() = runTest {
        val repo = TestSetup(backgroundScope).signedIn()
        repo.folders()
        assertEquals(1, server.openSessions)
        repo.release()
        repo.acquire()
        advanceTimeBy(40_000); runCurrent()
        assertEquals(1, server.openSessions)
    }
}
