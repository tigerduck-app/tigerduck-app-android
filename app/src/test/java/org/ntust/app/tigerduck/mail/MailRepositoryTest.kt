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
import org.junit.After
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
import org.ntust.app.tigerduck.mail.index.InMemoryMailSearchIndex
import org.ntust.app.tigerduck.mail.index.MailIndexProgress
import org.ntust.app.tigerduck.mail.index.MailIndexer
import org.ntust.app.tigerduck.mail.index.MailSearchIndex
import org.ntust.app.tigerduck.mail.index.RecordingIndexScheduler
import org.ntust.app.tigerduck.mail.model.FolderStatus
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.model.MailPage
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.mail.smtp.MailSender
import org.ntust.app.tigerduck.mail.smtp.MailTransport
import org.ntust.app.tigerduck.mail.smtp.SentCopy
import org.ntust.app.tigerduck.mail.store.MailCache
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class MailRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    // What the account launches, joined before TemporaryFolder deletes the cache a sign-out may
    // still be wiping there.
    private val appScope = testApplicationScope()

    @After
    fun stopTheAppScope() = appScope.cancelAndJoinBlocking()

    private val server = FakeMailServer()
    private val state = InMemoryMailStateStore()
    private val credentials = InMemoryCredentialStore()
    private val demo = FakeDemoGate()
    private val sent = mutableListOf<String>()
    /** Thrown by the SMTP send instead of sending, as a server refusing its login does. */
    @Volatile private var smtpRefusal: MailError? = null
    private val transport = MailTransport { _, message ->
        smtpRefusal?.let { throw it }
        sent += message.messageId
    }

    private suspend fun TestSetup.signedIn(studentId: String = "b10000001", password: String = "pw"): MailRepository {
        account.signIn(studentId, password)
        return repository
    }

    private inner class TestSetup(
        scope: kotlinx.coroutines.CoroutineScope,
        /** What the repository opens its sessions with; the account signs in on the server itself. */
        sessions: MailSessionFactory = server.factory(),
        /** What the account signs in with. */
        accountSessions: MailSessionFactory = server.factory(),
        /** The index the repository is handed, around [searchIndex]: a test acts inside its calls. */
        wrapIndex: (InMemoryMailSearchIndex) -> MailSearchIndex = { it },
    ) {
        val cache = MailCache(tmp.root)
        val searchIndex = InMemoryMailSearchIndex()
        val indexScheduler = RecordingIndexScheduler()
        val notifier = RecordingNotifier()
        // Its sign-out's cache wipe runs on the IO dispatcher, which the test's own scope does not
        // wait for once the test ends: [appScope] does, before TemporaryFolder deletes the cache.
        val account = MailAccount(credentials, state, accountSessions, cache, demo, schoolMailSite(), RecordingScheduler(), notifier, appScope)
        val repository = MailRepository(
            account, sessions, cache, state,
            MailSender(MessageBuilder(), transport, pause = {}), MessageBuilder(), demo, wrapIndex(searchIndex), indexScheduler, scope,
        )

        /**
         * Starts [action] on a thread of its own from inside a call that may hold the connection,
         * and waits for it to end -- or, while the caller holds the connection, for it to reach
         * the wait for it, which then ends only once the caller lets go. Throws nothing into the
         * caller, which is repository code: what went wrong is kept for [Aside.join].
         */
        fun runAside(action: suspend () -> Unit): Aside {
            val stopped = CountDownLatch(1)
            repository.holder.onWait = { if (repository.holder.held) stopped.countDown() }
            val aside = Aside()
            aside.thread = thread(isDaemon = true) {
                try {
                    runBlocking { action() }
                } catch (t: Throwable) {
                    aside.failure = t
                } finally {
                    stopped.countDown()
                }
            }
            // A long safety timeout only: the wait ends as soon as either happens.
            if (!stopped.await(30, TimeUnit.SECONDS)) aside.timedOut = true
            repository.holder.onWait = null
            return aside
        }

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

    /**
     * A move or delete is asked the same question, with the session held: one confirmed for a
     * student who left while it waited -- another signed in over them, whose mailbox hands out
     * the same UIDs -- moves and deletes none of the next one's mail.
     */
    @Test
    fun `a move or delete of a departed account touches nothing of the next one's`() = runTest {
        server.deliver("a")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        val uid = repo.loadPage("INBOX", null).messages.single().uid
        val trash = repo.folders().nameOf(SpecialFolder.TRASH)!!
        val before = setup.account.accountGeneration
        server.passwords["B10000002"] = "pw"
        setup.account.signIn("b10000002", "pw")

        val moved = runCatching { repo.move("INBOX", uid, trash) { setup.account.accountGeneration == before } }
        val deleted = runCatching { repo.delete("INBOX", uid) { setup.account.accountGeneration == before } }

        assertTrue(moved.exceptionOrNull() is MailError.Abandoned)
        assertTrue(deleted.exceptionOrNull() is MailError.Abandoned)
        assertEquals(listOf("a"), server.subjects("INBOX"))
        assertTrue(server.subjects(trash).isEmpty())
    }

    /**
     * So is readying a delete, which creates the trash it moves mail to when the account has none:
     * a delete of a student who left while it waited lists and creates nothing in the next one's
     * mailbox.
     */
    @Test
    fun `a departed account's delete creates no trash in the next one's mailbox`() = runTest {
        server.folders.remove("回收筒")
        server.deliver("a")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        val uid = repo.loadPage("INBOX", null).messages.single().uid
        val before = setup.account.accountGeneration
        server.passwords["B10000002"] = "pw"
        setup.account.signIn("b10000002", "pw")

        val asked = runCatching { repo.deletesPermanently("INBOX") { setup.account.accountGeneration == before } }
        val deleted = runCatching { repo.delete("INBOX", uid) { setup.account.accountGeneration == before } }

        assertTrue(asked.exceptionOrNull() is MailError.Abandoned)
        assertTrue(deleted.exceptionOrNull() is MailError.Abandoned)
        assertTrue("a trash was created in the next student's mailbox", server.createAttempts.isEmpty())
        assertEquals(listOf("a"), server.subjects("INBOX"))
    }

    /**
     * A folder recreated on the server after the caller read its UID, whose page has replaced the
     * cache since: the cache now agrees with the server, so only the UIDVALIDITY the caller read
     * the UID under can tell that the UID names another mail. Neither a move nor a delete -- in
     * the trash, a permanent one -- touches it.
     */
    @Test
    fun `a move or delete of a mail read before its folder was recreated touches the new folder's mail not at all`() = runTest {
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        val trash = repo.folders().nameOf(SpecialFolder.TRASH)!!
        val inboxUid = server.deliver("read before")
        val trashUid = server.deliver("deleted before", folder = trash)
        repo.loadPage("INBOX", null)
        repo.loadPage(trash, null)
        val read = server.uidValidity

        // Both folders are recreated, and the same UIDs given to other mail.
        server.uidValidity = read + 1
        server.folders["INBOX"] = mutableListOf()
        server.folders[trash] = mutableListOf()
        server.nextUid = inboxUid
        server.deliver("another")
        server.nextUid = trashUid
        server.deliver("another in the trash", folder = trash)
        repo.loadPage("INBOX", null)
        repo.loadPage(trash, null)

        val moved = runCatching { repo.move("INBOX", inboxUid, trash, uidValidity = read) }
        val deleted = runCatching { repo.delete(trash, trashUid, uidValidity = read) }

        assertTrue(moved.exceptionOrNull() is MailError.FolderChanged)
        assertTrue(deleted.exceptionOrNull() is MailError.FolderChanged)
        assertEquals(listOf("another"), server.subjects("INBOX"))
        assertEquals(listOf("another in the trash"), server.subjects(trash))
        // The cache was of the folder as it is, and stays.
        assertEquals(listOf("another"), repo.cachedPage("INBOX")!!.messages.map { it.subject })
    }

    /**
     * A session whose [MailSession.move] signs another student in over this one once it has sent
     * its COPY and STORE, as the sign-in sheet a rejected password shows allows while a write is
     * on the connection; [fail] has the move fail then, with its flag on the server.
     */
    private fun TestSetup.switchingDuringMove(fail: Boolean = false, to: String = "b10000002"): MailSessionFactory = MailSessionFactory { credentials ->
        val session = server.factory().open(credentials)
        object : MailSession by session {
            override fun move(folder: String, uid: Long, target: String, ownedDeleted: Set<Long>, uidValidity: Long?): Boolean {
                server.failAfterFlag = fail
                try {
                    return session.move(folder, uid, target, ownedDeleted, uidValidity)
                } finally {
                    server.failAfterFlag = false
                    server.passwords[to.uppercase()] = "pw"
                    runBlocking { account.signIn(to, "pw") }
                }
            }
        }
    }

    /**
     * A move that leaves its mail flagged rather than expunged records the UID as the app's to
     * expunge later -- in the state of the student it was made for. One that ends after another
     * student signed in over them records nothing in that one's state, which a later EXPUNGE in a
     * folder of the same name and UIDVALIDITY would otherwise take as the app's own.
     */
    @Test
    fun `a move that ends after another student signed in records no mail as theirs to expunge`() = runTest {
        server.expungeOnMove = false
        val uid = server.deliver("a")
        lateinit var setup: TestSetup
        setup = TestSetup(backgroundScope, MailSessionFactory { setup.switchingDuringMove().open(it) })
        val repo = setup.signedIn()
        repo.loadPage("INBOX", null)

        repo.move("INBOX", uid, "回收筒")

        assertEquals("b10000002", credentials.mailStudentId?.lowercase())
        assertTrue("the first student's flagged mail was recorded as the second's", state.ownedDeleted("INBOX", server.uidValidity).isEmpty())
    }

    /**
     * The same student signing in again keeps the set ([MailStateStore.clearSignIn]), and a move
     * on the connection meanwhile still adds to it: dropping the UID would leave a mail the app
     * flagged that no later EXPUNGE would ever take as its own.
     */
    @Test
    fun `a move that ends after the same student signed in again still records its flagged mail`() = runTest {
        server.expungeOnMove = false
        val uid = server.deliver("a")
        lateinit var setup: TestSetup
        setup = TestSetup(backgroundScope, MailSessionFactory { setup.switchingDuringMove(to = "b10000001").open(it) })
        val repo = setup.signedIn()
        repo.loadPage("INBOX", null)

        repo.move("INBOX", uid, "回收筒")

        assertEquals(setOf(uid), state.ownedDeleted("INBOX", server.uidValidity))
    }

    /**
     * A list's move asks whether it is still wanted on the account generation it was confirmed
     * under, which the same student signing in again moves. The flag check after the move fails
     * is the bookkeeping of that student's own set, which [MailStateStore.clearSignIn] keeps: it
     * must still record the flag that landed, or no later EXPUNGE of the folder takes that mail.
     */
    @Test
    fun `a list move that fails as the same student signs in again still records its flagged mail`() = runTest {
        val uid = server.deliver("a")
        lateinit var setup: TestSetup
        setup = TestSetup(backgroundScope, MailSessionFactory { setup.switchingDuringMove(fail = true, to = "b10000001").open(it) })
        val repo = setup.signedIn()
        repo.loadPage("INBOX", null)
        val before = setup.account.accountGeneration

        val moved = runCatching { repo.move("INBOX", uid, "回收筒") { setup.account.accountGeneration == before } }

        assertTrue(moved.exceptionOrNull() is MailError.Network)
        assertEquals("the flagged mail went unrecorded", setOf(uid), state.ownedDeleted("INBOX", server.uidValidity))
    }

    /**
     * Another student's sign-in resets the state and puts their credentials in place in one hold of
     * the account. A write that starts while that sign-in is still finishing pairs the first
     * student's credentials with the first student's state, never with the next one's: what it
     * records after it fails lands nowhere once the next one is in.
     */
    @Test
    fun `a write that starts as another student's sign-in finishes records nothing in their state`() = runTest {
        val uid = server.deliver("a")
        // The next student's sign-in holds as it closes its own session, everything asked.
        val closing = CountDownLatch(1)
        val letClose = CountDownLatch(1)
        val holdingClose = MailSessionFactory { c ->
            val session = server.factory().open(c)
            if (c.loginName != "B10000002") {
                session
            } else {
                object : MailSession by session {
                    override fun close() {
                        closing.countDown()
                        letClose.await(5, TimeUnit.SECONDS)
                        session.close()
                    }
                }
            }
        }
        // The write lets that sign-in finish while it is on the connection, then fails, its flag landed.
        val signedInNext = CountDownLatch(1)
        val writing = MailSessionFactory { c ->
            val session = server.factory().open(c)
            object : MailSession by session {
                override fun move(folder: String, uid: Long, target: String, ownedDeleted: Set<Long>, uidValidity: Long?): Boolean {
                    letClose.countDown()
                    assertTrue(signedInNext.await(5, TimeUnit.SECONDS))
                    server.failAfterFlag = true
                    try {
                        return session.move(folder, uid, target, ownedDeleted, uidValidity)
                    } finally {
                        server.failAfterFlag = false
                    }
                }
            }
        }
        val setup = TestSetup(backgroundScope, writing, accountSessions = holdingClose)
        val repo = setup.signedIn()
        repo.loadPage("INBOX", null)
        server.passwords["B10000002"] = "pw"
        val signIn = async(Dispatchers.IO) { setup.account.signIn("b10000002", "pw").also { signedInNext.countDown() } }
        assertTrue(closing.await(5, TimeUnit.SECONDS))

        val moved = runCatching { repo.move("INBOX", uid, "回收筒") }
        assertNull(signIn.await())

        assertTrue(moved.exceptionOrNull() is MailError.Network)
        assertTrue("the first student's flagged mail was recorded as the next one's", state.ownedDeleted("INBOX", server.uidValidity).isEmpty())
    }

    /**
     * The account a move is made as is read off the caller's thread, which for the list and the
     * mail screen is the main one: reading it decrypts the password from the credential store.
     */
    @Test
    fun `a move reads the account it is made as off the caller's thread`() = runTest {
        val uid = server.deliver("a")
        val repo = TestSetup(backgroundScope).signedIn()
        repo.loadPage("INBOX", null)
        val caller = Thread.currentThread()
        val readOn = java.util.concurrent.CopyOnWriteArrayList<Thread>()
        credentials.onPasswordRead = { readOn += Thread.currentThread() }

        repo.move("INBOX", uid, "回收筒")
        credentials.onPasswordRead = null

        assertTrue(readOn.isNotEmpty())
        assertTrue("the password was decrypted on the caller's thread", readOn.none { it == caller })
    }

    /** As above, with another student signing in: their state takes nothing of the first one's. */
    @Test
    fun `a list move that fails as another student signs in records nothing`() = runTest {
        val uid = server.deliver("a")
        lateinit var setup: TestSetup
        setup = TestSetup(backgroundScope, MailSessionFactory { setup.switchingDuringMove(fail = true).open(it) })
        val repo = setup.signedIn()
        repo.loadPage("INBOX", null)
        val before = setup.account.accountGeneration

        val moved = runCatching { repo.move("INBOX", uid, "回收筒") { setup.account.accountGeneration == before } }

        assertTrue(moved.exceptionOrNull() is MailError.Network)
        assertTrue(state.ownedDeleted("INBOX", server.uidValidity).isEmpty())
    }

    /**
     * A write that waited for the session while another student signed in over this one finds
     * the state reset for them: the set there is theirs, and must not decide what the write
     * expunges, so the write sends nothing.
     */
    @Test
    fun `a write whose student's state was reset while it waited for the session sends nothing`() = runTest {
        server.deliver("a")
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sessions = MailSessionFactory { credentials ->
            val session = server.factory().open(credentials)
            object : MailSession by session {
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
        // One request holds the session.
        val body = launch(Dispatchers.IO) { runCatching { repo.body("INBOX", uid) } }
        assertTrue(holding.await(5, TimeUnit.SECONDS))
        // The move reaches the wait for it, and another student signs in meanwhile.
        val reached = CountDownLatch(1)
        setup.repository.holder.onWait = { reached.countDown() }
        val move = async(Dispatchers.IO) { runCatching { repo.move("INBOX", uid, "回收筒") } }
        assertTrue(reached.await(5, TimeUnit.SECONDS))
        setup.repository.holder.onWait = null
        server.passwords["B10000002"] = "pw"
        setup.account.signIn("b10000002", "pw")
        release.countDown()

        assertTrue(move.await().exceptionOrNull() is MailError.Abandoned)
        assertEquals(listOf("a"), server.subjects("INBOX"))
        body.join()
    }

    /** As above, for the flag check a failed move makes to record a flag that landed regardless. */
    @Test
    fun `a move that fails after another student signed in records no mail as theirs to expunge`() = runTest {
        val uid = server.deliver("a")
        lateinit var setup: TestSetup
        setup = TestSetup(backgroundScope, MailSessionFactory { setup.switchingDuringMove(fail = true).open(it) })
        val repo = setup.signedIn()
        repo.loadPage("INBOX", null)

        val moved = runCatching { repo.move("INBOX", uid, "回收筒") }

        assertTrue(moved.exceptionOrNull() is MailError.Network)
        assertTrue("the first student's flagged mail was recorded as the second's", state.ownedDeleted("INBOX", server.uidValidity).isEmpty())
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
    fun `a search finds Chinese in a mail's subject, sender or text, which the server cannot search`() = runTest {
        val notice = server.deliver("Weekly", body = MailBody(null, "本週停課公告", emptyList(), emptyMap()))
        val html = server.deliver("Reminder", body = MailBody("<p>期末<b>公告</b></p>", null, emptyList(), emptyMap()))
        val board = server.deliver("Hello", from = MailAddress("公告欄", "board@example.com"))
        server.deliver("unrelated")
        val repo = TestSetup(backgroundScope).signedIn()

        val outcome = repo.search("INBOX", "公告") as SearchOutcome.Server

        assertEquals(listOf(board, html, notice), outcome.messages.map { it.uid })
        assertEquals(server.uidValidity, outcome.uidValidity)
    }

    @Test
    fun `the mail on the phone is what was downloaded for the student, in every folder, and none after a sign-out`() = runTest {
        server.deliver("received", from = MailAddress("Teacher", "teacher@example.com"))
        server.deliver("sent", folder = "寄件備份匣", from = MailAddress(null, "b10000001@mail.ntust.edu.tw"))
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        repo.updateSearchIndex()

        assertEquals(listOf("received", "sent"), repo.correspondence().map { it.subject }.sorted())

        setup.signOutAndSettle()
        assertEquals(emptyList<MailSummary>(), repo.correspondence())
    }

    @Test
    fun `a student signed in over another has none of the other's mail on the phone`() = runTest {
        server.deliver("received")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        repo.updateSearchIndex()

        server.passwords["B10000002"] = "pw"
        assertNull(setup.account.signIn("b10000002", "pw"))

        assertEquals(emptyList<MailSummary>(), repo.correspondence())
    }

    @Test
    fun `the demo mailbox's mail is the mail on the phone`() = runTest {
        val repo = TestSetup(backgroundScope).signedIn("B10000099", "demo")

        assertEquals(demo.box.messages.map { it.summary.subject }.sorted(), repo.correspondence().map { it.subject }.sorted())
    }

    @Test
    fun `a search returns the newest page of matches and keeps the rest to fetch`() = runTest {
        val uids = (1..MailRepository.PAGE_SIZE + 2).map { server.deliver("期中考 $it") }
        val repo = TestSetup(backgroundScope).signedIn()
        repo.updateSearchIndex()
        val outcome = repo.search("INBOX", "期中") as SearchOutcome.Server
        assertEquals(uids.takeLast(MailRepository.PAGE_SIZE).reversed(), outcome.messages.map { it.uid })
        assertEquals(uids.take(2).reversed(), outcome.remaining)
        assertEquals(server.uidValidity, outcome.uidValidity)
        val page = repo.searchPage("INBOX", outcome.remaining)
        assertEquals(uids.take(2).reversed(), page.messages.map { it.uid })
        assertEquals(server.uidValidity, page.uidValidity)
    }

    @Test
    fun `the first search finds among the newest mail at once, and asks for the rest in the background`() = runTest {
        val old = (1..MailRepository.SEARCH_CATCH_UP).map { server.deliver("期中考 $it") }
        val new = server.deliver("期中考 newest")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()

        val outcome = repo.search("INBOX", "期中") as SearchOutcome.Server

        assertEquals(new, outcome.messages.first().uid)
        assertFalse("the oldest waits for the background", old.first() in outcome.messages.map { it.uid })
        assertTrue(setup.indexScheduler.followUps > 0)
    }

    @Test
    fun `a search before the download got through says it may have missed mail, and one after does not`() = runTest {
        (1..MailRepository.SEARCH_CATCH_UP + 5).forEach { server.deliver("期中考 $it") }
        val repo = TestSetup(backgroundScope).signedIn()

        assertFalse((repo.search("INBOX", "期中") as SearchOutcome.Server).complete)
        assertFalse("the older backlog is still out", (repo.search("INBOX", "期中") as SearchOutcome.Server).complete)

        repo.updateSearchIndex()
        assertTrue((repo.search("INBOX", "期中") as SearchOutcome.Server).complete)
    }

    @Test
    fun `mail come in past what a search catches up on is downloaded by the background run it asks for`() = runTest {
        server.deliver("期中考 0")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        var now = 1_000_000L
        repo.clock = { now }
        repo.updateSearchIndex()
        val late = (1..MailRepository.SEARCH_CATCH_UP + 5).map { server.deliver("期中考 $it") }
        now += 1_000

        repo.search("INBOX", "期中")
        // The background run the search asked for, well within fifteen minutes of the whole one.
        assertTrue(setup.indexScheduler.requests + setup.indexScheduler.followUps > 0)
        repo.updateSearchIndex()

        assertTrue(setup.searchIndex.uids(setup.account.ownAddress!!.lowercase(), "INBOX").containsAll(late))
        assertTrue("found complete now", (repo.search("INBOX", "期中") as SearchOutcome.Server).complete)
    }

    @Test
    fun `a gap a search finds is not undone by a download that planned before the mail came`() = runTest {
        server.deliver("期中考 0")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        val now = 1_000_000L
        var late = emptyList<Long>()
        var searching = true
        repo.clock = {
            // Read as a download that planned every folder before the mail came notes that it got
            // through: the mail comes, and a search catches up on its newest ten, right then.
            if (searching) {
                searching = false
                late = (1..MailRepository.SEARCH_CATCH_UP + 5).map { server.deliver("期中考 $it") }
                runBlocking { repo.search("INBOX", "期中") }
            }
            now
        }
        repo.updateSearchIndex()
        assertFalse(searching)

        // The background run the search asked for.
        repo.updateSearchIndex()

        assertTrue(setup.searchIndex.uids(setup.account.ownAddress!!.lowercase(), "INBOX").containsAll(late))
    }

    /**
     * A clock that, the first time a pass reads it -- as it notes it got through, its folders all
     * planned and filled -- has mail come in past what a search catches up on, and runs that
     * search: a gap found while the pass is still open, whose own request for a run the pass
     * already under way stands for. Answers the mail that came.
     */
    private fun gapDuringThePass(repo: MailRepository): () -> List<Long> {
        var late = emptyList<Long>()
        var searching = true
        repo.clock = {
            if (searching) {
                searching = false
                late = (1..MailRepository.SEARCH_CATCH_UP + 5).map { server.deliver("期中考 $it") }
                runBlocking { repo.search("INBOX", "期中") }
            }
            1_000_000L
        }
        return { late.also { assertFalse("no search ran during the pass", searching) } }
    }

    @Test
    fun `a gap a search finds while the background run is under way is downloaded before that run ends`() = runTest {
        server.deliver("期中考 0")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        val late = gapDuringThePass(repo)

        repo.updateSearchIndex()

        assertTrue(setup.searchIndex.uids(setup.account.ownAddress!!.lowercase(), "INBOX").containsAll(late()))
    }

    @Test
    fun `a gap a search finds while the download in the app is under way is downloaded before it ends`() = runTest {
        server.deliver("期中考 0")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        val late = gapDuringThePass(repo)

        visit(repo)

        assertTrue(setup.searchIndex.uids(setup.account.ownAddress!!.lowercase(), "INBOX").containsAll(late()))
    }

    /**
     * The pass under way may be past its last look for gaps, or the background run past its last
     * pass and yet to be filed as done, which a request then joins and is lost with: a gap asks for
     * a run of its own after it.
     */
    @Test
    fun `a search that leaves mail out asks for a run after the one under way`() = runTest {
        server.deliver("期中考 0")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        repo.updateSearchIndex()
        (1..MailRepository.SEARCH_CATCH_UP + 5).forEach { server.deliver("期中考 $it") }

        repo.search("INBOX", "期中")

        assertEquals(1, setup.indexScheduler.followUps)
    }

    @Test
    fun `a pass with no gap found while it ran downloads once`() = runTest {
        server.deliver("期中考 0")
        val repo = TestSetup(backgroundScope).signedIn()

        repo.updateSearchIndex()

        assertEquals("planned once", server.folders.size, server.undeletedUidsCalls)
    }

    @Test
    fun `a first search of a folder its catch-up downloads whole misses nothing`() = runTest {
        repeat(3) { server.deliver("期中考 $it") }
        val repo = TestSetup(backgroundScope).signedIn()

        assertTrue((repo.search("INBOX", "期中") as SearchOutcome.Server).complete)
    }

    @Test
    fun `a search catches up on new mail alone, leaving the older backlog to the background`() = runTest {
        (1..3 * MailRepository.SEARCH_CATCH_UP).forEach { server.deliver("期中考 $it") }
        val repo = TestSetup(backgroundScope).signedIn()
        repo.search("INBOX", "期中")
        val fetched = server.bodyFetches

        repo.search("INBOX", "期中")

        assertEquals(fetched, server.bodyFetches)
    }

    @Test
    fun `mail come in since the index caught up is searched too`() = runTest {
        server.deliver("期中考")
        val repo = TestSetup(backgroundScope).signedIn()
        repo.updateSearchIndex()
        val late = server.deliver("期中考 補考")

        val outcome = repo.search("INBOX", "補考") as SearchOutcome.Server

        assertEquals(listOf(late), outcome.messages.map { it.uid })
    }

    @Test
    fun `matches found before the folder was recreated are not fetched as its new mail`() = runTest {
        server.deliver("期中考")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        repo.updateSearchIndex()
        setup.searchIndex.onSearch = {
            setup.searchIndex.onSearch = null
            // Recreated between the index's answer and the fetch of its matches, with other mail
            // under the same UID.
            server.folders.getValue("INBOX").clear()
            server.uidValidity++
            server.nextUid = 1
            server.deliver("other")
        }

        val outcome = repo.search("INBOX", "期中") as SearchOutcome.Server

        assertEquals(emptyList<String>(), outcome.messages.map { it.subject })
        assertEquals(emptyList<Long>(), outcome.remaining)
        assertEquals(server.uidValidity, outcome.uidValidity)
        assertFalse("none of the new folder's mail was searched", outcome.complete)
    }

    @Test
    fun `mail a search downloads while a download lists its folder stays downloaded`() = runTest {
        server.deliver("期中考 1")
        val armed = AtomicBoolean(true)
        var late = 0L
        var aside: Aside? = null
        lateinit var setup: TestSetup
        setup = TestSetup(backgroundScope) { index ->
            object : MailSearchIndex by index {
                override fun open(owner: String, folder: String, uidValidity: Long, stillCurrent: () -> Boolean): Boolean {
                    if (folder == "INBOX" && armed.getAndSet(false)) {
                        // Come in once the download has listed the folder, and downloaded by a
                        // search before the download looks at what the index has.
                        late = server.deliver("期中考 2")
                        aside = setup.runAside { setup.repository.search("INBOX", "期中") }
                    }
                    return index.open(owner, folder, uidValidity, stillCurrent)
                }
            }
        }
        val repo = setup.signedIn()

        repo.updateSearchIndex()
        aside!!.join()

        assertTrue(late in setup.searchIndex.uids(setup.account.ownAddress!!.lowercase(), "INBOX"))
    }

    @Test
    fun `the background download indexes every folder`() = runTest {
        server.deliver("收件 公告")
        server.deliver("寄出 公告", folder = "寄件備份匣")
        server.deliver("草稿 公告", folder = "草稿匣")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()

        repo.updateSearchIndex()
        val owner = setup.account.ownAddress!!.lowercase()

        listOf("INBOX", "寄件備份匣", "草稿匣").forEach { folder ->
            assertEquals(folder, 1, setup.searchIndex.search(owner, folder, server.uidValidity, "公告").size)
        }
    }

    @Test
    fun `the background download tells how far it has come, of one total for every folder`() = runTest {
        val inbox = MailIndexer.BATCH + 2
        repeat(inbox) { server.deliver("收件 $it") }
        server.deliver("寄出", folder = "寄件備份匣")
        val repo = TestSetup(backgroundScope).signedIn()
        val seen = mutableListOf<MailIndexProgress?>()
        server.onFetchBody = { seen += repo.searchIndexProgress.value }

        repo.updateSearchIndex()

        assertEquals(inbox + 1, seen.size)
        assertTrue(seen.all { it?.total == inbox + 1 })
        // A batch at a time: Inbox's two, then Sent's one.
        assertEquals(listOf(0, MailIndexer.BATCH, inbox), seen.map { it!!.done }.distinct())
        assertNull("gone once it is done", repo.searchIndexProgress.value)
    }

    @Test
    fun `the background download is under way while it counts what it lacks`() = runTest {
        server.deliver("收件")
        server.deliver("寄出", folder = "寄件備份匣")
        val repo = TestSetup(backgroundScope).signedIn()
        val seen = mutableListOf<MailIndexProgress?>()
        server.onUndeletedUids = { seen += repo.searchIndexProgress.value }

        repo.updateSearchIndex()

        assertTrue(seen.isNotEmpty())
        assertTrue("counting, not done: $seen", seen.all { it == MailIndexProgress.COUNTING })
        assertNull("gone once it is done", repo.searchIndexProgress.value)
    }

    /**
     * A download cut short, by the network say, leaves mail out of search: the status row says
     * how far it came, stopped, rather than OK, until a later download gets through (#587).
     */
    @Test
    fun `a download cut short says how far it came, stopped, until one gets through`() = runTest {
        val inbox = MailIndexer.BATCH + 2
        repeat(inbox) { server.deliver("收件 $it") }
        val repo = TestSetup(backgroundScope).signedIn()
        var fetched = 0
        server.onFetchBody = { if (++fetched == MailIndexer.BATCH + 1) server.failBodyFetch = true }

        runCatching { repo.updateSearchIndex() }

        val left = repo.searchIndexProgress.value
        assertNotNull("not OK while mail is missing", left)
        assertFalse("stopped, not under way", left!!.running)
        assertEquals(inbox, left.total)
        assertTrue("${left.done} of ${left.total}", left.done < inbox)

        server.failBodyFetch = false
        repo.updateSearchIndex()
        assertNull("gone once one gets through", repo.searchIndexProgress.value)
    }

    /**
     * A search found mail missing while the pass ran, and the run was let go of before the pass
     * that would fetch it: the row does not say OK, as that mail is still missing (#587).
     */
    @Test
    fun `a gap found as the last pass ends, with no pass after, leaves the row saying mail is missing`() = runTest {
        server.deliver("期中考 0")
        val repo = TestSetup(backgroundScope).signedIn()
        var wanted = true
        var searching = true
        repo.clock = {
            if (searching) {
                searching = false
                (1..MailRepository.SEARCH_CATCH_UP + 5).forEach { server.deliver("期中考 $it") }
                runBlocking { repo.search("INBOX", "期中") }
                wanted = false
            }
            1_000_000L
        }

        repo.updateSearchIndex { wanted }

        assertFalse("a search ran during the pass", searching)
        val left = repo.searchIndexProgress.value
        assertNotNull("not OK while mail is missing", left)
        assertFalse(left!!.running)
    }

    @Test
    fun `a search catching up shows no download under way`() = runTest {
        repeat(3) { server.deliver("期中考 $it") }
        val repo = TestSetup(backgroundScope).signedIn()
        val seen = mutableListOf<MailIndexProgress?>()
        server.onFetchBody = { seen += repo.searchIndexProgress.value }
        server.onUndeletedUids = { seen += repo.searchIndexProgress.value }

        repo.search("INBOX", "期中")

        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it == null })
    }

    @Test
    fun `an older page whose mail is downloaded comes from the phone, without the network`() = runTest {
        val uids = (1..120).map { server.deliver("信 $it") }
        val repo = TestSetup(backgroundScope).signedIn()
        repo.updateSearchIndex()
        val newest = repo.loadPage("INBOX", null)
        val fetched = server.pageFetches.size

        val older = repo.loadPage("INBOX", newest.nextBeforeSeq)

        assertEquals(fetched, server.pageFetches.size)
        assertEquals(uids.dropLast(MailRepository.PAGE_SIZE).takeLast(MailRepository.PAGE_SIZE).reversed(), older.messages.map { it.uid })
        assertEquals(newest.uidValidity, older.uidValidity)
        assertEquals(21, older.nextBeforeSeq)
        val oldest = repo.loadPage("INBOX", older.nextBeforeSeq)
        assertEquals(fetched, server.pageFetches.size)
        assertEquals(uids.take(20).reversed(), oldest.messages.map { it.uid })
        assertNull("the bottom of the folder", oldest.nextBeforeSeq)
    }

    @Test
    fun `a search reads and writes the downloaded mail off the thread that asked`() = runTest {
        repeat(3) { server.deliver("期中考 $it") }
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        setup.searchIndex.callers.clear()

        repo.search("INBOX", "期中")

        assertTrue(setup.searchIndex.callers.isNotEmpty())
        assertFalse("SQLite on the main thread", Thread.currentThread() in setup.searchIndex.callers)
    }

    @Test
    fun `the download in the app reads and writes the downloaded mail off the scope's thread`() = runTest {
        server.deliver("期中考")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        setup.searchIndex.callers.clear()

        visit(repo)

        assertTrue(setup.searchIndex.callers.isNotEmpty())
        assertFalse(Thread.currentThread() in setup.searchIndex.callers)
    }

    @Test
    fun `an older page from the phone reads the account off the thread that asked`() = runTest {
        (1..60).forEach { server.deliver("信 $it") }
        val repo = TestSetup(backgroundScope).signedIn()
        repo.updateSearchIndex()
        val newest = repo.loadPage("INBOX", null)
        credentials.idReaders.clear()

        repo.loadPage("INBOX", newest.nextBeforeSeq)

        assertFalse("the encrypted store on the main thread", Thread.currentThread() in credentials.idReaders)
    }

    @Test
    fun `an older page not downloaded yet comes from the server`() = runTest {
        (1..120).forEach { server.deliver("信 $it") }
        val repo = TestSetup(backgroundScope).signedIn()
        val newest = repo.loadPage("INBOX", null)
        val fetched = server.pageFetches.size

        repo.loadPage("INBOX", newest.nextBeforeSeq)

        assertEquals(fetched + 1, server.pageFetches.size)
    }

    @Test
    fun `a mail marked read reads as read on the phone's copy`() = runTest {
        val uids = (1..60).map { server.deliver("信 $it") }
        val repo = TestSetup(backgroundScope).signedIn()
        repo.updateSearchIndex()
        val newest = repo.loadPage("INBOX", null)

        repo.setSeen("INBOX", uids.first(), true)
        val fetched = server.pageFetches.size

        val older = repo.loadPage("INBOX", newest.nextBeforeSeq)
        assertEquals("from the phone", fetched, server.pageFetches.size)
        assertTrue(older.messages.single { it.uid == uids.first() }.flags.seen)
    }

    @Test
    fun `a mail read on another device reads as read once the download runs again`() = runTest {
        val uids = (1..60).map { server.deliver("信 $it") }
        val repo = TestSetup(backgroundScope).signedIn()
        var now = 1_000_000L
        repo.clock = { now }
        repo.updateSearchIndex()
        val stored = server.folders.getValue("INBOX").single { it.summary.uid == uids.first() }
        stored.summary = stored.summary.copy(flags = stored.summary.flags.copy(seen = true))

        now += MailRepository.INDEX_RESYNC_MS
        repo.updateSearchIndex()
        val newest = repo.loadPage("INBOX", null)
        val fetched = server.pageFetches.size
        val older = repo.loadPage("INBOX", newest.nextBeforeSeq)

        assertEquals("from the phone", fetched, server.pageFetches.size)
        assertTrue(older.messages.single { it.uid == uids.first() }.flags.seen)
    }

    @Test
    fun `the mail screen in use downloads in the app, without waiting on the background run`() = runTest {
        server.deliver("期中考")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        val owner = setup.account.ownAddress!!.lowercase()

        repo.acquire()
        runCurrent()
        withContext(Dispatchers.Default) {
            withTimeout(30_000) { while (setup.searchIndex.uids(owner, "INBOX").isEmpty()) delay(10) }
        }

        assertEquals(1, setup.searchIndex.uids(owner, "INBOX").size)
    }

    /** Opens the mail screen, as the page appearing does, and waits for its download in the app to end. */
    private suspend fun kotlinx.coroutines.test.TestScope.visit(repo: MailRepository) {
        repo.acquire()
        runCurrent()
        withContext(Dispatchers.Default) { withTimeout(30_000) { while (repo.downloadingInApp) delay(10) } }
        repo.release()
    }

    @Test
    fun `coming back to the mail page soon after a whole download does not download again`() = runTest {
        server.deliver("期中考")
        val repo = TestSetup(backgroundScope).signedIn()
        var now = 1_000_000L
        repo.clock = { now }
        visit(repo)
        val listed = server.undeletedUidsCalls

        now += MailRepository.INDEX_RESYNC_MS - 1
        visit(repo)

        assertEquals(listed, server.undeletedUidsCalls)
    }

    /** A pull to refresh downloads at once, the quarter hour after a whole download or not (#586). */
    @Test
    fun `a pull downloads mail for search at once, soon after a whole download too`() = runTest {
        server.deliver("期中考")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        repo.clock = { 1_000_000L }
        visit(repo)
        val listed = server.undeletedUidsCalls
        val late = server.deliver("期中考 補")

        repo.downloadNow()
        runCurrent()
        withContext(Dispatchers.Default) { withTimeout(30_000) { while (repo.downloadingInApp) delay(10) } }

        assertTrue(server.undeletedUidsCalls > listed)
        assertTrue(late in setup.searchIndex.uids(setup.account.ownAddress!!.lowercase(), "INBOX"))
    }

    /** A pull while a download runs: that one planned before the pull, so another pass follows it. */
    @Test
    fun `a pull while a download runs is served by a pass after it`() = runTest {
        server.deliver("期中考")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        repo.clock = { 1_000_000L }
        repo.updateSearchIndex()
        val onePass = server.undeletedUidsCalls
        // Due again, as after the downloaded mail was deleted.
        repo.deleteSearchIndex()
        var pulled = false
        server.onUndeletedUids = {
            if (!pulled) {
                pulled = true
                repo.downloadNow()
            }
        }

        val before = server.undeletedUidsCalls
        repo.updateSearchIndex()

        assertTrue(pulled)
        assertEquals("two passes", 2 * onePass, server.undeletedUidsCalls - before)
    }

    @Test
    fun `coming back once the interval has passed downloads again`() = runTest {
        server.deliver("期中考")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        var now = 1_000_000L
        repo.clock = { now }
        visit(repo)
        val listed = server.undeletedUidsCalls
        val requested = setup.indexScheduler.requests

        now += MailRepository.INDEX_RESYNC_MS
        visit(repo)

        assertTrue(server.undeletedUidsCalls > listed)
        assertTrue(setup.indexScheduler.requests > requested)
    }

    @Test
    fun `the background download soon after a whole one does not download again`() = runTest {
        server.deliver("期中考")
        val repo = TestSetup(backgroundScope).signedIn()
        var now = 1_000_000L
        repo.clock = { now }
        visit(repo)
        val listed = server.undeletedUidsCalls

        // The worker the visit asked for, which waited out the download in the app.
        now += MailRepository.INDEX_RESYNC_MS - 1
        repo.updateSearchIndex()

        assertEquals(listed, server.undeletedUidsCalls)
    }

    @Test
    fun `a visit whose download waited out a whole one does not download again`() = runTest {
        server.deliver("期中考")
        val repo = TestSetup(backgroundScope).signedIn()
        val now = 1_000_000L
        repo.clock = { now }
        visit(repo)
        val listed = server.undeletedUidsCalls

        // Due as the visit asks; once its download has the lock, another has just got through.
        var asked = false
        repo.clock = { if (asked) now else now + MailRepository.INDEX_RESYNC_MS.also { asked = true } }
        visit(repo)

        assertTrue(asked)
        assertEquals(listed, server.undeletedUidsCalls)
    }

    @Test
    fun `a flag refresh cut short by a recreated folder leaves the next visit to download again`() = runTest {
        server.deliver("期中考")
        val armed = AtomicBoolean(false)
        val sessions = MailSessionFactory { credentials ->
            val session = server.factory().open(credentials)
            object : MailSession by session {
                override fun undeletedUids(folder: String): List<Long> {
                    // Recreated once Inbox is planned: its flags would be asked of other mail.
                    if (folder != "INBOX" && armed.getAndSet(false)) server.uidValidity++
                    return session.undeletedUids(folder)
                }
            }
        }
        val setup = TestSetup(backgroundScope, sessions)
        val repo = setup.signedIn()
        var now = 1_000_000L
        repo.clock = { now }
        repo.updateSearchIndex()
        now += MailRepository.INDEX_RESYNC_MS
        armed.set(true)
        // Nothing left to fetch anywhere: only the flags, which the recreated Inbox cuts short.
        repo.updateSearchIndex()
        val listed = server.undeletedUidsCalls

        now += 1_000
        visit(repo)

        assertTrue("downloaded again, however soon", server.undeletedUidsCalls > listed)
    }

    @Test
    fun `a download cut short goes on at the next visit, however soon`() = runTest {
        server.deliver("期中考")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        var now = 1_000_000L
        repo.clock = { now }
        repo.folders()
        // The liveness probe through, then the folder's UID list fails.
        server.queueCallErrors(null, MailError.Network())
        visit(repo)
        val owner = setup.account.ownAddress!!.lowercase()
        assertTrue("cut short", setup.searchIndex.uids(owner, "INBOX").isEmpty())

        now += 1_000
        visit(repo)

        assertEquals(1, setup.searchIndex.uids(owner, "INBOX").size)
    }

    @Test
    fun `deleting the mail kept on the phone empties it, stops its download, and the next visit downloads it again`() = runTest {
        server.deliver("期中考")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        var now = 1_000_000L
        repo.clock = { now }
        visit(repo)
        val owner = setup.account.ownAddress!!.lowercase()
        assertTrue(repo.searchIndexBytes() > 0)
        val cancels = setup.indexScheduler.cancels

        repo.deleteSearchIndex()

        assertEquals(0L, repo.searchIndexBytes())
        assertTrue(setup.searchIndex.uids(owner, "INBOX").isEmpty())
        assertTrue(setup.indexScheduler.cancels > cancels)
        now += 1_000
        visit(repo)
        assertEquals("downloaded again, however soon", 1, setup.searchIndex.uids(owner, "INBOX").size)
    }

    @Test
    fun `a password rejected while downloading for search stops every login after it`() = runTest {
        server.deliver("期中考")
        val attempts = AtomicInteger()
        val sessions = MailSessionFactory { credentials ->
            attempts.incrementAndGet()
            server.factory().open(credentials)
        }
        val setup = TestSetup(backgroundScope, sessions)
        val repo = setup.signedIn()
        // Changed on the web: the server now rejects the stored password.
        server.passwords["B10000001"] = "changed"

        assertTrue(runCatching { repo.updateSearchIndex() }.exceptionOrNull() is MailError.AuthFailed)

        assertTrue("spec §7.4: the rejection reaches the account", setup.account.authFailed.value)
        val tried = attempts.get()
        val requested = setup.indexScheduler.requests
        runCatching { repo.updateSearchIndex() }
        visit(repo)
        assertEquals("no LOGIN with the rejected password again", tried, attempts.get())
        assertEquals("nor a background download asked for", requested, setup.indexScheduler.requests)
    }

    @Test
    fun `a request queued behind a rejected login does not log in again`() = runTest {
        val attempts = AtomicInteger()
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sessions = MailSessionFactory { credentials ->
            if (attempts.incrementAndGet() == 1) {
                opening.countDown()
                assertTrue(release.await(30, TimeUnit.SECONDS))
            }
            server.factory().open(credentials)
        }
        val setup = TestSetup(backgroundScope, sessions)
        val repo = setup.signedIn()
        server.passwords["B10000001"] = "changed"
        val first = async(Dispatchers.IO) { runCatching { repo.status("INBOX") } }
        assertTrue(opening.await(30, TimeUnit.SECONDS))
        // Another request reaches the wait for the connection while the first one's login is out.
        val reached = CountDownLatch(1)
        setup.repository.holder.onWait = { reached.countDown() }
        val second = async(Dispatchers.IO) { runCatching { repo.status("INBOX") } }
        assertTrue(reached.await(5, TimeUnit.SECONDS))
        release.countDown()

        assertTrue(first.await().exceptionOrNull() is MailError.AuthFailed)
        assertTrue(second.await().exceptionOrNull() is MailError.AuthFailed)
        assertEquals("spec §7.4: one LOGIN with the rejected password", 1, attempts.get())
        assertTrue(setup.account.authFailed.value)
    }

    @Test
    fun `a send that finds the password rejected sends nothing over SMTP`() = runTest {
        val repo = TestSetup(backgroundScope).signedIn()
        server.passwords["B10000001"] = "changed"
        val mail = OutgoingMail(repo.selfAddress(), listOf(MailAddress(null, "a@x.tw")), emptyList(), emptyList(), "s", "b")

        // The sent folder is looked up first, over IMAP, whose login the server refuses.
        assertTrue(runCatching { repo.send(mail, null) }.exceptionOrNull() is MailError.AuthFailed)

        assertTrue("spec §7.4: no SMTP login with the rejected password", sent.isEmpty())
    }

    @Test
    fun `an SMTP login refused for the student signed in since the send began is theirs`() = runTest {
        server.passwords["B10000002"] = "pw"
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        val mail = OutgoingMail(repo.selfAddress(), listOf(MailAddress(null, "a@x.tw")), emptyList(), emptyList(), "s", "b")
        // Another student signs in as the send looks the sent folder up; the SMTP login then
        // goes out as them, and the server refuses it.
        server.onListFolders = {
            server.onListFolders = null
            runBlocking { setup.account.signIn("b10000002", "pw") }
        }
        smtpRefusal = MailError.AuthFailed()

        assertTrue(runCatching { repo.send(mail, null) }.exceptionOrNull() is MailError.AuthFailed)

        assertTrue("spec §7.4: their rejected password reaches the account", setup.account.authFailed.value)
    }

    @Test
    fun `a rejection of a login sent before signing in again with the same password does not stop the new sign-in`() = runTest {
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val sessions = MailSessionFactory { credentials ->
            if (first.getAndSet(false)) {
                opening.countDown()
                assertTrue(release.await(30, TimeUnit.SECONDS))
                // The answer to a login of the account as it was before signing out.
                throw MailError.AuthFailed()
            }
            server.factory().open(credentials)
        }
        val setup = TestSetup(backgroundScope, sessions)
        val repo = setup.signedIn()
        val request = async(Dispatchers.IO) { runCatching { repo.status("INBOX") } }
        assertTrue(opening.await(30, TimeUnit.SECONDS))

        setup.signOutAndSettle()
        setup.account.signIn("b10000001", "pw")
        release.countDown()

        assertTrue(request.await().exceptionOrNull() is MailError.AuthFailed)
        assertFalse(setup.account.authFailed.value)
    }

    @Test
    fun `a password rejected while downloading in the app reaches the account`() = runTest {
        server.deliver("期中考")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        server.passwords["B10000001"] = "changed"

        visit(repo)

        assertTrue(setup.account.authFailed.value)
    }

    @Test
    fun `a rejection of an account that left while it downloaded does not stop the next one`() = runTest {
        server.passwords["B10000002"] = "pw"
        lateinit var setup: TestSetup
        val sessions = MailSessionFactory { credentials ->
            // The other student signs in while the first one's login is out, which then fails.
            if (credentials.loginName == "B10000001") {
                runBlocking { setup.account.signIn("b10000002", "pw") }
                throw MailError.AuthFailed()
            }
            server.factory().open(credentials)
        }
        setup = TestSetup(backgroundScope, sessions)
        val repo = setup.signedIn()

        runCatching { repo.updateSearchIndex() }

        assertFalse(setup.account.authFailed.value)
    }

    @Test
    fun `a search whose password is rejected asks for no background download`() = runTest {
        server.deliver("期中考")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        server.passwords["B10000001"] = "changed"

        assertTrue(runCatching { repo.search("INBOX", "期中") }.exceptionOrNull() is MailError.AuthFailed)

        assertEquals(0, setup.indexScheduler.requests)
        assertEquals(0, setup.indexScheduler.followUps)
    }

    /**
     * Sessions whose first listing of a folder's UIDs waits for [release]: a download out on the
     * server, which [holding] says it has reached.
     */
    private inner class HeldListing {
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)

        /** The listing went on without [release]: the test no longer tests what it says. */
        @Volatile var timedOut = false

        val factory = MailSessionFactory { credentials ->
            val session = server.factory().open(credentials)
            object : MailSession by session {
                override fun undeletedUids(folder: String): List<Long> {
                    if (holding.count > 0L) {
                        holding.countDown()
                        if (!release.await(30, TimeUnit.SECONDS)) timedOut = true
                    }
                    return session.undeletedUids(folder)
                }
            }
        }
    }

    /** Every UID downloaded for [owner], whatever the folder. */
    private fun TestSetup.indexedMail(owner: String): List<Long> = server.folders.keys.flatMap { searchIndex.uids(owner, it) }

    @Test
    fun `deleting the downloaded mail stops the download in the app, however many visits there were`() = runTest {
        server.deliver("期中考")
        val held = HeldListing()
        val setup = TestSetup(backgroundScope, held.factory)
        val repo = setup.signedIn()
        val owner = setup.account.ownAddress!!.lowercase()
        repo.acquire()
        runCurrent()
        // Waited for off the test's thread, which the download in the app goes on on.
        withContext(Dispatchers.Default) { assertTrue(held.holding.await(30, TimeUnit.SECONDS)) }
        // Back on the mail screen while the first visit's download is still out.
        repo.acquire()
        runCurrent()

        repo.deleteSearchIndex()
        held.release.countDown()
        withContext(Dispatchers.Default) { withTimeout(30_000) { while (repo.downloadingInApp) delay(10) } }

        assertEquals(emptyList<Long>(), setup.indexedMail(owner))
        assertFalse("the held listing timed out", held.timedOut)
    }

    @Test
    fun `a search that ends after the downloaded mail was deleted asks for no run to download it again`() = runTest {
        server.deliver("期中考")
        val held = HeldListing()
        val setup = TestSetup(backgroundScope, held.factory)
        val repo = setup.signedIn()
        val search = async(Dispatchers.IO) { runCatching { repo.search("INBOX", "期中") } }
        assertTrue(held.holding.await(30, TimeUnit.SECONDS))

        repo.deleteSearchIndex()
        held.release.countDown()
        search.await()

        assertEquals(0, setup.indexScheduler.requests)
        assertEquals(0, setup.indexScheduler.followUps)
        assertFalse("the held listing timed out", held.timedOut)
    }

    @Test
    fun `deleting the downloaded mail stops its runs off the thread that asked`() = runTest {
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()

        repo.deleteSearchIndex()

        assertTrue(setup.indexScheduler.cancelThreads.isNotEmpty())
        assertFalse("waiting there on a search's question to WorkManager", Thread.currentThread() in setup.indexScheduler.cancelThreads)
    }

    @Test
    fun `a run a search asks for as the student signs out is cancelled with the sign-out`() = runTest {
        server.deliver("期中考 0")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        repo.updateSearchIndex()
        (1..MailRepository.SEARCH_CATCH_UP + 5).forEach { server.deliver("期中考 $it") }
        val reached = CountDownLatch(1)
        var timedOut = false
        setup.indexScheduler.onRequestAgain = {
            setup.indexScheduler.onRequestAgain = null
            // The student signs out while WorkManager is being asked, and the sign-out's cleanup
            // gets as far as it can: its cancel made, or held until the search has asked.
            setup.repository.onSchedulingWait = { reached.countDown() }
            setup.account.signOut()
            if (!reached.await(30, TimeUnit.SECONDS)) timedOut = true
        }
        val search = async(Dispatchers.IO) { runCatching { repo.search("INBOX", "期中") } }
        search.await()
        // The cleanup's cancel, whenever it comes.
        withContext(Dispatchers.Default) {
            withTimeout(30_000) { while ("cancel" !in setup.indexScheduler.events) delay(10) }
        }

        assertFalse("the sign-out's cleanup got nowhere", timedOut)
        assertEquals("the sign-out's cancel is the last word", listOf("followUp", "cancel"), setup.indexScheduler.events.toList())
    }

    @Test
    fun `a visit's download not started yet when the mail is deleted downloads nothing`() = runTest {
        server.deliver("期中考")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        val owner = setup.account.ownAddress!!.lowercase()
        repo.acquire()

        // Deleted on the test's thread, which the visit's download waits for to start.
        runBlocking { repo.deleteSearchIndex() }
        runCurrent()
        withContext(Dispatchers.Default) { withTimeout(30_000) { while (repo.downloadingInApp) delay(10) } }

        assertEquals(emptyList<Long>(), setup.indexedMail(owner))
    }

    @Test
    fun `a background download that waited out the deletion downloads nothing`() = runTest {
        server.deliver("期中考")
        val held = HeldListing()
        val setup = TestSetup(backgroundScope, held.factory)
        val repo = setup.signedIn()
        val owner = setup.account.ownAddress!!.lowercase()
        repo.acquire()
        runCurrent()
        withContext(Dispatchers.Default) { assertTrue(held.holding.await(30, TimeUnit.SECONDS)) }
        // The worker the visit asked for, waiting for the lock the visit's download holds.
        val worker = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { repo.updateSearchIndex() }

        repo.deleteSearchIndex()
        held.release.countDown()
        worker.join()
        withContext(Dispatchers.Default) { withTimeout(30_000) { while (repo.downloadingInApp) delay(10) } }

        assertEquals(emptyList<Long>(), setup.indexedMail(owner))
        assertFalse("the held listing timed out", held.timedOut)
    }

    @Test
    fun `deleting the downloaded mail while the background download is out leaves nothing of it`() = runTest {
        server.deliver("期中考")
        val held = HeldListing()
        val setup = TestSetup(backgroundScope, held.factory)
        val repo = setup.signedIn()
        val owner = setup.account.ownAddress!!.lowercase()
        val download = launch(Dispatchers.IO) { runCatching { repo.updateSearchIndex() } }
        assertTrue(held.holding.await(30, TimeUnit.SECONDS))

        // WorkManager stops the worker only some time after it is told to.
        repo.deleteSearchIndex()
        held.release.countDown()
        download.join()

        assertEquals(emptyList<Long>(), setup.indexedMail(owner))
        assertFalse("the held listing timed out", held.timedOut)
    }

    @Test
    fun `deleting the downloaded mail while a search catches up leaves nothing of it`() = runTest {
        server.deliver("期中考")
        val held = HeldListing()
        val setup = TestSetup(backgroundScope, held.factory)
        val repo = setup.signedIn()
        val owner = setup.account.ownAddress!!.lowercase()
        val search = async(Dispatchers.IO) { runCatching { repo.search("INBOX", "期中") } }
        assertTrue(held.holding.await(30, TimeUnit.SECONDS))

        repo.deleteSearchIndex()
        held.release.countDown()
        search.await()

        assertEquals(emptyList<Long>(), setup.indexedMail(owner))
        assertFalse("the held listing timed out", held.timedOut)
    }

    @Test
    fun `a download that gets through just as its mail is deleted leaves the next visit to download it again`() = runTest {
        server.deliver("期中考")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        var now = 1_000_000L
        var deleting = true
        repo.clock = {
            // Read as the download notes that it got through: the mail is deleted right then.
            if (deleting) {
                deleting = false
                runBlocking { repo.deleteSearchIndex() }
            }
            now
        }
        repo.updateSearchIndex()
        assertFalse(deleting)

        now += 1_000
        visit(repo)

        assertEquals(1, setup.searchIndex.uids(setup.account.ownAddress!!.lowercase(), "INBOX").size)
    }

    @Test
    fun `mail gone from the server leaves the index`() = runTest {
        val gone = server.deliver("期中考 A")
        val kept = server.deliver("期中考 B")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        var now = 1_000_000L
        repo.clock = { now }
        repo.updateSearchIndex()
        server.folders.getValue("INBOX").removeAll { it.summary.uid == gone }

        now += MailRepository.INDEX_RESYNC_MS
        repo.updateSearchIndex()

        assertEquals(setOf(kept), setup.searchIndex.uids(setup.account.ownAddress!!.lowercase(), "INBOX"))
    }

    @Test
    fun `a sign-out empties the index and stops its download`() = runTest {
        server.deliver("期中考")
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        repo.updateSearchIndex()
        val owner = setup.account.ownAddress!!.lowercase()

        setup.signOutAndSettle()
        runCurrent()
        // The repository empties it on the IO pool once it hears of the sign-out.
        withContext(Dispatchers.Default) {
            withTimeout(30_000) { while (setup.searchIndex.uids(owner, "INBOX").isNotEmpty()) delay(10) }
        }

        assertEquals(emptySet<Long>(), setup.searchIndex.uids(owner, "INBOX"))
        assertTrue(setup.indexScheduler.cancels > 0)
    }

    /**
     * Runs [change] -- an account leaving, the next one signing in and downloading -- all before
     * the repository's cleanup for the one that left gets to run: that cleanup is dispatched on the
     * test's own thread, which [change] keeps busy. Then lets the cleanup run, and waits for it.
     */
    private fun kotlinx.coroutines.test.TestScope.holdingTheCleanup(setup: TestSetup, change: suspend () -> Unit) {
        runBlocking { change() }
        val cleaned = CountDownLatch(1)
        setup.searchIndex.onCleanup = { cleaned.countDown() }
        runCurrent()
        assertTrue("the cleanup never ran", cleaned.await(30, TimeUnit.SECONDS))
        setup.searchIndex.onCleanup = null
    }

    @Test
    fun `a late cleanup for the student who left leaves the next one's downloads`() = runTest {
        server.deliver("期中考")
        server.passwords["B10000002"] = "pw"
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        repo.updateSearchIndex()

        holdingTheCleanup(setup) {
            setup.account.signIn("b10000002", "pw")
            repo.updateSearchIndex()
            assertEquals("downloaded before the cleanup", 1, setup.searchIndex.uids(setup.account.ownAddress!!.lowercase(), "INBOX").size)
        }

        assertEquals(1, setup.searchIndex.uids(setup.account.ownAddress!!.lowercase(), "INBOX").size)
    }

    @Test
    fun `a late cleanup empties the mail of a student who left and came back, every departure counted`() = runTest {
        server.deliver("期中考")
        server.passwords["B10000002"] = "pw"
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        repo.updateSearchIndex()
        val first = setup.account.ownAddress!!.lowercase()

        holdingTheCleanup(setup) {
            setup.account.signIn("b10000002", "pw")
            setup.account.signIn("b10000001", "pw")
        }

        assertEquals("what the first student had downloaded before leaving", emptySet<Long>(), setup.searchIndex.uids(first, "INBOX"))
    }

    @Test
    fun `a late cleanup for a sign-out leaves the downloads of whoever signed in next`() = runTest {
        server.deliver("期中考")
        server.passwords["B10000002"] = "pw"
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        repo.updateSearchIndex()

        holdingTheCleanup(setup) {
            setup.account.signOut()
            setup.account.signIn("b10000002", "pw")
            repo.updateSearchIndex()
            assertEquals("downloaded before the cleanup", 1, setup.searchIndex.uids(setup.account.ownAddress!!.lowercase(), "INBOX").size)
        }

        assertEquals(1, setup.searchIndex.uids(setup.account.ownAddress!!.lowercase(), "INBOX").size)
    }

    @Test
    fun `another student signing in finds nothing of the first one's in the index`() = runTest {
        server.deliver("期中考")
        server.passwords["B10000002"] = "pw2"
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        repo.updateSearchIndex()
        val first = setup.account.ownAddress!!.lowercase()

        setup.account.signIn("b10000002", "pw2")
        runCurrent()
        withContext(Dispatchers.Default) {
            withTimeout(30_000) { while (setup.searchIndex.uids(first, "INBOX").isNotEmpty()) delay(10) }
        }

        assertEquals(emptySet<Long>(), setup.searchIndex.uids(first, "INBOX"))
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

    /** What [TestSetup.runAside] started, for the test to wait out once it is done. */
    private class Aside {
        @Volatile var thread: Thread? = null
        @Volatile var failure: Throwable? = null
        @Volatile var timedOut = false

        /** Waits for the action to end, and fails the test for anything that went wrong in it. */
        fun join() {
            val started = checkNotNull(thread)
            started.join(30_000)
            check(!started.isAlive) { "the action aside never ended" }
            check(!timedOut) { "the action aside got nowhere" }
            failure?.let { throw AssertionError("the action aside failed", it) }
        }
    }

    /**
     * Seeds [owner]'s index with [folder]'s [uid] as the server has it: the other student's copy of
     * that mail, downloaded since they signed in.
     */
    private fun TestSetup.seedIndex(owner: String, folder: String, uid: Long) {
        val summary = server.folders.getValue(folder).single { it.summary.uid == uid }.summary
        assertTrue(searchIndex.open(owner, folder, server.uidValidity) { true })
        assertTrue(searchIndex.add(owner, folder, server.uidValidity, listOf(MailSearchIndex.Entry(uid, "", summary.subject, "", summary))) { true })
    }

    /**
     * Signs [studentId] in over the one signed in, from inside a write of the first one's on the
     * server, and waits out the clear of the index that sign-in runs; then gives the new student
     * their own copy of [folder]'s [uid]. Answers their index's owner.
     */
    private fun TestSetup.signInOverDuringWrite(studentId: String, folder: String, uid: Long): String {
        val cleared = CountDownLatch(1)
        searchIndex.onClear = { cleared.countDown() }
        runBlocking { account.signIn(studentId, "pw") }
        assertTrue("the sign-in's clear never ran", cleared.await(30, TimeUnit.SECONDS))
        searchIndex.onClear = null
        val owner = account.ownAddress!!.lowercase()
        seedIndex(owner, folder, uid)
        return owner
    }

    @Test
    fun `a mark-read that ends after another student signed in leaves their downloaded mail as it was`() = runTest {
        val uid = server.deliver("a")
        server.passwords["B10000002"] = "pw"
        val setup = TestSetup(backgroundScope)
        val repo = setup.signedIn()
        var other: String? = null
        server.onSetSeen = {
            server.onSetSeen = null
            other = setup.signInOverDuringWrite("b10000002", "INBOX", uid)
        }

        repo.setSeen("INBOX", uid, true)
        runCurrent()

        val theirs = setup.searchIndex.summaries(other!!, "INBOX", server.uidValidity, listOf(uid)).getValue(uid)
        assertFalse("the first student's mark-read is not theirs", theirs.flags.seen)
    }

    @Test
    fun `a move that ends after another student signed in leaves their downloaded mail in place`() = runTest {
        val uid = server.deliver("a")
        server.passwords["B10000002"] = "pw"
        lateinit var setup: TestSetup
        var other: String? = null
        val interrupting = MailSessionFactory { credentials ->
            val session = server.factory().open(credentials)
            object : MailSession by session {
                override fun move(folder: String, uid: Long, target: String, ownedDeleted: Set<Long>, uidValidity: Long?): Boolean {
                    val expunged = session.move(folder, uid, target, ownedDeleted, uidValidity)
                    if (other == null) {
                        // The other student's mail has the UID the moved one had, as a mailbox
                        // of theirs would.
                        server.nextUid = uid
                        server.deliver("theirs")
                        other = setup.signInOverDuringWrite("b10000002", "INBOX", uid)
                    }
                    return expunged
                }
            }
        }
        setup = TestSetup(backgroundScope, interrupting)
        val repo = setup.signedIn()

        repo.move("INBOX", uid, "回收筒")
        runCurrent()

        assertEquals(setOf(uid), setup.searchIndex.uids(other!!, "INBOX"))
    }

    /**
     * A setup whose index, once [armed], recreates the inbox at the first look at a folder's
     * UIDVALIDITY or removal from it -- one write of the user's being on the server by then --
     * with other mail under the UID [uid] had, and downloads it again on [aside].
     */
    private inner class RecreatedUnderAWrite(scope: kotlinx.coroutines.CoroutineScope, private val uid: Long) {
        val armed = AtomicBoolean(false)
        val now = AtomicLong(1_000_000L)
        var aside: Aside? = null

        private fun recreate() {
            if (!armed.getAndSet(false)) return
            server.folders.getValue("INBOX").clear()
            server.uidValidity++
            server.nextUid = uid
            server.deliver("新信")
            now.addAndGet(MailRepository.INDEX_RESYNC_MS)
            aside = setup.runAside { setup.repository.updateSearchIndex() }
        }

        val setup: TestSetup = TestSetup(scope) { index ->
            object : MailSearchIndex by index {
                override fun uidValidity(owner: String, folder: String): Long? {
                    recreate()
                    return index.uidValidity(owner, folder)
                }

                override fun remove(owner: String, folder: String, uidValidity: Long, uids: Collection<Long>, stillCurrent: () -> Boolean) {
                    recreate()
                    index.remove(owner, folder, uidValidity, uids, stillCurrent)
                }
            }
        }

        /** The inbox's mail under [uid] as the phone's copy has it now, once [aside] is done. */
        fun downloaded(): MailSummary? {
            assertNotNull("the inbox was never recreated", aside)
            aside!!.join()
            return setup.searchIndex.summaries(setup.account.ownAddress!!.lowercase(), "INBOX", server.uidValidity, listOf(uid))[uid]
        }
    }

    @Test
    fun `a mark-read reaches the downloaded mail it was made on, not that of a folder recreated since`() = runTest {
        val uid = server.deliver("舊信")
        val recreated = RecreatedUnderAWrite(backgroundScope, uid)
        val repo = recreated.setup.signedIn()
        repo.clock = { recreated.now.get() }
        repo.updateSearchIndex()

        recreated.armed.set(true)
        repo.setSeen("INBOX", uid, true)

        val theirs = recreated.downloaded()!!
        assertEquals("新信", theirs.subject)
        assertFalse(theirs.flags.seen)
    }

    @Test
    fun `a move takes the downloaded mail it was made on, not that of a folder recreated since`() = runTest {
        val uid = server.deliver("舊信")
        val recreated = RecreatedUnderAWrite(backgroundScope, uid)
        val repo = recreated.setup.signedIn()
        repo.clock = { recreated.now.get() }
        repo.updateSearchIndex()

        recreated.armed.set(true)
        repo.move("INBOX", uid, "回收筒")

        assertEquals("新信", recreated.downloaded()?.subject)
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
