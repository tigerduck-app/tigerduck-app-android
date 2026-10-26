package org.ntust.app.tigerduck.mail.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.ntust.app.tigerduck.mail.cancelAndJoinBlocking
import org.ntust.app.tigerduck.mail.signInDuringRefusal
import org.ntust.app.tigerduck.mail.RecordingNotifier
import org.ntust.app.tigerduck.mail.RecordingScheduler
import org.ntust.app.tigerduck.mail.schoolMailSite
import org.ntust.app.tigerduck.mail.imap.MailSession
import org.ntust.app.tigerduck.mail.imap.MailSessionFactory
import org.ntust.app.tigerduck.mail.model.FolderStatus
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.mail.notify.MailMutePreset
import org.ntust.app.tigerduck.mail.notify.MailMuteRule
import org.ntust.app.tigerduck.mail.notify.MailMuteSettings
import org.ntust.app.tigerduck.mail.notify.MailQuietHours
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.testApplicationScope
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MailCheckerTest {
    @get:Rule val tmp = TemporaryFolder()

    // What the account and the notices launch, joined before TemporaryFolder deletes the cache
    // a sign-out may still be wiping there.
    private val appScope = testApplicationScope()

    @After
    fun stopTheAppScope() = appScope.cancelAndJoinBlocking()

    private val server = FakeMailServer()
    private val state = InMemoryMailStateStore()
    private val notifier = RecordingNotifier()
    private val scheduler = RecordingScheduler()
    private var now = 1_000_000L
    private val credentialStore = InMemoryCredentialStore()
    private val account by lazy {
        MailAccount(credentialStore, state, server.factory(), MailCache(tmp.root), FakeDemoGate(), schoolMailSite(), scheduler, notifier, appScope)
    }

    private fun checker(
        factory: MailSessionFactory = server.factory(),
        cache: MailCache = MailCache(tmp.root),
    ) = MailChecker(account, state, factory, notifier, cache) { now }

    /** Signs out as Settings does, and waits for the cleanup sign-out then runs on its own. */
    private fun signOutAndSettle() {
        val cleaned = CountDownLatch(1)
        notifier.onCancelAll = { cleaned.countDown() }
        account.signOut()
        assertTrue("sign-out's cleanup never ran", cleaned.await(5, TimeUnit.SECONDS))
        notifier.onCancelAll = null
    }

    /** The server, with [beforeStatus], [beforeFetch] and [beforeBody] run as the check asks for each. */
    private fun interrupting(
        beforeStatus: () -> Unit = {},
        beforeFetch: () -> Unit = {},
        beforeBody: () -> Unit = {},
    ) =
        MailSessionFactory { credentials ->
            val session = server.factory().open(credentials)
            object : MailSession by session {
                override fun fetchBody(folder: String, uid: Long): MailBody {
                    beforeBody()
                    return session.fetchBody(folder, uid)
                }

                override fun status(folder: String): FolderStatus {
                    beforeStatus()
                    return session.status(folder)
                }

                override fun fetchSince(folder: String, fromUid: Long): List<MailSummary> {
                    beforeFetch()
                    return session.fetchSince(folder, fromUid)
                }
            }
        }

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

    /** [text], a Taipei wall-clock time such as `2026-10-21T23:30`. */
    private fun taipei(text: String): Instant = LocalDateTime.parse(text).atZone(ZoneId.of("Asia/Taipei")).toInstant()

    private suspend fun signInStudent() {
        server.passwords["B00015999"] = "pw"
        assertNull(account.signIn("b00015999", "pw"))
    }

    @Test
    fun `muted mail is neither posted nor counted, and never notifies later`() = runTest {
        signInStudent()
        state.muteSettings = MailMuteSettings(
            presets = setOf(MailMutePreset.BULLETIN),
            rules = listOf(MailMuteRule("r", MailMuteRule.Field.SUBJECT, MailMuteRule.Operator.CONTAINS, "weekly digest")),
        )
        checker().check(CheckSource.ALARM)
        server.deliver("Lecture notice", from = MailAddress("NTUST", "bulletin@mail.ntust.edu.tw"))
        val last = server.deliver("Club weekly digest")
        server.deliver("Exam room")

        assertEquals(CheckOutcome.NewMail(1), checker().check(CheckSource.ALARM))
        assertEquals(listOf("Exam room"), notifier.posted.single().map { it.subject })
        assertTrue(state.inboxSeenUidNext > last)

        // Switched off again, the mail already passed over stays passed over.
        state.muteSettings = MailMuteSettings()
        assertEquals(CheckOutcome.NoChange, checker().check(CheckSource.WORKER))
        assertEquals(1, notifier.posted.size)
    }

    @Test
    fun `mail that arrived in quiet hours does not notify when a check runs after them`() = runTest {
        signInStudent()
        state.muteSettings = MailMuteSettings(quietHours = MailQuietHours(enabled = true))
        checker().check(CheckSource.ALARM)
        server.deliver("Arrived at night", receivedAt = taipei("2026-10-22T01:00"))
        server.deliver("Arrived in the morning", receivedAt = taipei("2026-10-22T07:30"))
        now = taipei("2026-10-22T08:00").toEpochMilli()

        assertEquals(CheckOutcome.NewMail(1), checker().check(CheckSource.ALARM))
        assertEquals(listOf("Arrived in the morning"), notifier.posted.single().map { it.subject })
    }

    @Test
    fun `mail with no arrival time is judged by the time of the check`() = runTest {
        signInStudent()
        state.muteSettings = MailMuteSettings(quietHours = MailQuietHours(enabled = true))
        checker().check(CheckSource.ALARM)
        server.deliver("No date")
        now = taipei("2026-10-22T02:00").toEpochMilli()

        assertEquals(CheckOutcome.NewMail(0), checker().check(CheckSource.ALARM))
        assertTrue(notifier.posted.isEmpty())
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
        c.noteSeenByPage(FolderStatus(server.uidValidity, server.nextUid, 1, 1), account.accountGeneration)
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
            c.noteSeenByPage(FolderStatus(server.uidValidity, uid + 10, 1, 0), account.accountGeneration)
        }

        assertEquals(CheckOutcome.NewMail(1), c.check(CheckSource.ALARM))
        assertEquals(markerBefore, markerWhenNotified)
        // Overwriting the poll's marker would re-notify mail the list has already shown.
        assertEquals(uid + 10, state.inboxSeenUidNext)
    }

    /**
     * The list adopted a new UIDVALIDITY while this check was notifying. Its marker is the new
     * generation's, which this check's UIDs say nothing about: raised to them, it would hide the
     * new generation's mail below them for good (tigerduck-app/tigerduck-app#484).
     */
    @Test
    fun `a check leaves the marker of a UIDVALIDITY the list adopted during its notify`() = runTest {
        account.signIn("b10000001", "pw")
        server.deliver("old one")
        server.deliver("new one")
        val newValidity = server.uidValidity + 1
        val c = checker()
        notifier.onPostNewMail = { c.noteSeenByPage(FolderStatus(newValidity, 2, 1, 0), account.accountGeneration) }

        assertEquals(CheckOutcome.NewMail(2), c.check(CheckSource.ALARM))
        assertEquals(newValidity, state.inboxUidValidity)
        assertEquals(2L, state.inboxSeenUidNext)
    }

    /**
     * The state, the cache and the notifications are whoever is signed in now's. A check whose
     * account signs out while it fetches must not put the departing student's sender and subject
     * on the lock screen after sign-out cleared them, nor file their mail
     * (tigerduck-app/tigerduck-app-android#294).
     */
    @Test
    fun `a check whose account signs out while it fetches posts, files and marks nothing`() = runTest {
        val cache = MailCache(tmp.root)
        account.signIn("b10000001", "pw")
        val uid = server.deliver("the departing student's mail")
        val c = checker(interrupting(beforeFetch = ::signOutAndSettle), cache)

        val outcome = c.check(CheckSource.ALARM)

        assertTrue(notifier.posted.isEmpty())
        assertNull(cache.loadBody("INBOX", uid, server.uidValidity))
        assertEquals(0L, state.inboxSeenUidNext)
        assertEquals(CheckOutcome.NotSignedIn, outcome)
    }

    /** A sign-out during the notify ran its cleanup before the check's notification landed. */
    @Test
    fun `a check whose account signs out as it notifies takes the notification back`() = runTest {
        val cache = MailCache(tmp.root)
        account.signIn("b10000001", "pw")
        val uid = server.deliver("the departing student's mail")
        val c = checker(cache = cache)
        notifier.onPostNewMail = { signOutAndSettle() }

        val outcome = c.check(CheckSource.ALARM)

        // By id: the system does not list what the check just posted yet.
        assertEquals(notifier.posted, notifier.cancelledPosted)
        assertEquals(1, notifier.posted.single().size)
        assertNull(cache.loadBody("INBOX", uid, server.uidValidity))
        assertEquals(0L, state.inboxSeenUidNext)
        assertEquals(CheckOutcome.NotSignedIn, outcome)
    }

    /** The bodies come last, after the notify, and sign-out empties the cache meanwhile. */
    @Test
    fun `a check whose account signs out while it fetches bodies files none`() = runTest {
        val cache = MailCache(tmp.root)
        account.signIn("b10000001", "pw")
        val first = server.deliver("first")
        val second = server.deliver("second")
        var signedOut = false
        val c = checker(
            interrupting(beforeBody = { if (!signedOut) { signedOut = true; signOutAndSettle() } }),
            cache,
        )

        c.check(CheckSource.ALARM)

        assertNull(cache.loadBody("INBOX", first, server.uidValidity))
        assertNull(cache.loadBody("INBOX", second, server.uidValidity))
        // It stopped at the first, rather than going on to fetch the other.
        assertEquals(1, server.bodyFetches)
    }

    @Test
    fun `a check whose account signs out before its baseline writes none`() = runTest {
        account.signIn("b10000001", "pw")
        val c = checker(interrupting(beforeStatus = ::signOutAndSettle))

        val outcome = c.check(CheckSource.ALARM)

        assertEquals(0L, state.inboxUidValidity)
        assertEquals(0L, state.inboxSeenUidNext)
        assertEquals(CheckOutcome.NotSignedIn, outcome)
    }

    /** Reported, the departing account's rejection would stop the next student's checks. */
    @Test
    fun `a rejected password of an account that has signed out leaves the next one's checks on`() = runTest {
        account.signIn("b10000001", "pw")
        server.passwords["B10000002"] = "pw2"
        val c = checker(MailSessionFactory { _ ->
            signOutAndSettle()
            runBlocking { account.signIn("b10000002", "pw2") }
            throw MailError.AuthFailed()
        })

        val outcome = c.check(CheckSource.ALARM)

        assertFalse(state.authFailed)
        assertFalse(account.authFailed.value)
        assertEquals(0, notifier.authFailures)
        assertEquals(CheckOutcome.NotSignedIn, outcome)
    }

    /** Marked in one step with a sign-in's move of the generation, as the list marks it. */
    @Test
    fun `a rejection marked as the student signs in again leaves the new sign-in on`() = runTest {
        account.signIn("b10000001", "pw")
        val c = checker(MailSessionFactory { _ -> throw MailError.AuthFailed() })
        val signIn = signInDuringRefusal(account, state, "b10000001", "pw")

        c.check(CheckSource.ALARM)
        signIn.join()

        assertFalse(account.authFailed.value)
    }

    /**
     * The same student signing out and back in, with the password the server now takes, is a new
     * sign-in: the check of the sign-in before reports nothing of its rejected password into it.
     */
    @Test
    fun `a rejected password of the sign-in before leaves the same student's new sign-in on`() = runTest {
        account.signIn("b10000001", "pw")
        val c = checker(MailSessionFactory { _ ->
            signOutAndSettle()
            server.passwords["B10000001"] = "new"
            runBlocking { assertNull(account.signIn("b10000001", "new")) }
            throw MailError.AuthFailed()
        })

        val outcome = c.check(CheckSource.ALARM)

        assertFalse(state.authFailed)
        assertFalse(account.authFailed.value)
        assertEquals(0, notifier.authFailures)
        assertEquals(CheckOutcome.NotSignedIn, outcome)
    }

    /**
     * The list's poll adopts a new UIDVALIDITY right after the check read the old one, on its way
     * to raising the marker: the check must not raise the new generation's marker to its own UIDs.
     */
    @Test
    fun `a new UIDVALIDITY the list stores just as the check advances keeps its own marker`() = runTest {
        account.signIn("b10000001", "pw")
        server.deliver("old one")
        server.deliver("new one")
        val newValidity = server.uidValidity + 1
        val c = checker()
        var poll: Thread? = null
        notifier.onPostNewMail = {
            // The next read is the advance's.
            state.onUidValidityRead = {
                state.onUidValidityRead = null
                val polled = CountDownLatch(1)
                poll = thread { c.noteSeenByPage(FolderStatus(newValidity, 2, 1, 0), account.accountGeneration); polled.countDown() }
                // Given the time to finish first if it could; held behind the advance, it cannot.
                polled.await(300, TimeUnit.MILLISECONDS)
            }
        }

        assertEquals(CheckOutcome.NewMail(2), c.check(CheckSource.ALARM))
        poll!!.join(5_000)

        assertEquals(newValidity, state.inboxUidValidity)
        assertEquals(2L, state.inboxSeenUidNext)
    }

    /**
     * A check that starts while a sign-out is part way through reads the account and its
     * generation together: the departing account with the generation after it would count as
     * current, and the check would post that student's mail after the sign-out.
     */
    @Test
    fun `a check started mid-sign-out holds the account with its own generation or nothing`() = runTest {
        account.signIn("b10000001", "pw")
        server.deliver("the departing student's mail")
        val c = checker()
        var outcome: CheckOutcome? = null
        var checking: Thread? = null
        credentialStore.beforeClear = {
            credentialStore.beforeClear = null
            val done = CountDownLatch(1)
            checking = thread {
                outcome = runBlocking { c.check(CheckSource.ALARM) }
                done.countDown()
            }
            // Given the time to finish first if it could; held behind the sign-out, it cannot.
            done.await(300, TimeUnit.MILLISECONDS)
        }

        signOutAndSettle()
        checking!!.join(5_000)

        assertTrue(notifier.posted.isEmpty())
        assertEquals(CheckOutcome.NotSignedIn, outcome)
    }

    @Test
    fun `every check is recorded for the diagnostics screen, newest first`() = runTest {
        account.signIn("b10000001", "pw")
        checker().check(CheckSource.ALARM)
        now += 1
        checker().check(CheckSource.WORKER)
        assertEquals(listOf("${now}|WORKER|NoChange", "${now - 1}|ALARM|NoChange"), state.diagnostics)
    }

    /** What the School Mail page's "Last synced" row dates: a check that reached the inbox. */
    @Test
    fun `a check that reaches the inbox dates it, whatever it finds there`() = runTest {
        account.signIn("b10000001", "pw")
        assertEquals(CheckOutcome.NoChange, checker().check(CheckSource.ALARM))
        assertEquals(now, state.fetchedAt("INBOX"))

        now += 1_000
        server.deliver("new one")
        assertEquals(CheckOutcome.NewMail(1), checker().check(CheckSource.WORKER))
        assertEquals(now, state.fetchedAt("INBOX"))

        now += 1_000
        server.uidValidity = 99
        assertTrue(checker().check(CheckSource.ALARM) is CheckOutcome.Baseline)
        assertEquals(now, state.fetchedAt("INBOX"))
    }

    @Test
    fun `a check that fails or never reaches the inbox leaves its date`() = runTest {
        account.signIn("b10000001", "pw")
        checker().check(CheckSource.ALARM)
        val fetched = now
        assertEquals(fetched, state.fetchedAt("INBOX"))
        now += 1_000

        server.openError = MailError.Network()
        assertTrue(checker().check(CheckSource.WORKER) is CheckOutcome.Failed)
        server.openError = null
        server.passwords["B10000001"] = "changed"
        assertTrue(checker().check(CheckSource.WORKER) is CheckOutcome.Failed)
        assertEquals(CheckOutcome.Disabled, checker().check(CheckSource.ALARM))

        assertEquals(fetched, state.fetchedAt("INBOX"))
    }

    @Test
    fun `the demo mailbox is never dated, having no server to answer`() = runTest {
        account.signIn("B10000099", "demo")
        assertEquals(CheckOutcome.Demo, checker().check(CheckSource.ALARM))
        checker().noteFetchedByPage("INBOX", account.accountGeneration)
        assertEquals(0L, state.fetchedAt("INBOX"))
    }

    @Test
    fun `the date goes with the account at sign-out`() = runTest {
        account.signIn("b10000001", "pw")
        checker().check(CheckSource.ALARM)
        assertEquals(now, state.fetchedAt("INBOX"))

        account.signOut()

        assertEquals(0L, state.fetchedAt("INBOX"))
    }

    /** A check or a page fetch that outlives its account must not date the next one's mailbox. */
    @Test
    fun `a check whose account signs out before it ends leaves no date behind`() = runTest {
        val cache = MailCache(tmp.root)
        account.signIn("b10000001", "pw")
        server.deliver("first")
        var signedOut = false
        val c = checker(
            interrupting(beforeBody = { if (!signedOut) { signedOut = true; signOutAndSettle() } }),
            cache,
        )

        c.check(CheckSource.ALARM)

        assertTrue(signedOut)
        assertEquals(0L, state.fetchedAt("INBOX"))
    }

    @Test
    fun `a page fetch that lands after the sign-out leaves no date behind, whichever folder it read`() = runTest {
        account.signIn("b10000001", "pw")
        val c = checker()
        // What the page read before it fetched, as the page does.
        val before = account.accountGeneration
        signOutAndSettle()

        assertFalse(c.noteFetchedByPage("INBOX", before))
        assertFalse(c.noteFetchedByPage("寄件備份匣", before))

        assertEquals(0L, state.fetchedAt("INBOX"))
        assertEquals(0L, state.fetchedAt("寄件備份匣"))
    }

    /**
     * The page's own fetch, landing after its account left and the same student -- or another --
     * signed in again: it was the sign-in before's, and dates nothing of the new one's.
     */
    @Test
    fun `a page fetch for the sign-in before dates nothing, even with the same student signed in again`() = runTest {
        account.signIn("b10000001", "pw")
        val c = checker()
        val before = account.accountGeneration
        signOutAndSettle()
        account.signIn("b10000001", "pw")

        assertFalse(c.noteFetchedByPage("INBOX", before))
        assertFalse(c.noteFetchedByPage("寄件備份匣", before))
        assertEquals(0L, state.fetchedAt("INBOX"))
        assertEquals(0L, state.fetchedAt("寄件備份匣"))

        assertTrue(c.noteFetchedByPage("INBOX", account.accountGeneration))
        assertEquals(now, state.fetchedAt("INBOX"))
    }

    /** Likewise the inbox status the page read: it says nothing about the next sign-in's inbox. */
    @Test
    fun `a page status for the sign-in before moves no marker`() = runTest {
        account.signIn("b10000001", "pw")
        val c = checker()
        val before = account.accountGeneration
        signOutAndSettle()
        account.signIn("b10000001", "pw")
        val marker = state.inboxSeenUidNext
        assertTrue(marker > 0)

        c.noteSeenByPage(FolderStatus(server.uidValidity, marker + 50, 1, 1), before)

        assertEquals(marker, state.inboxSeenUidNext)
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
        server.bodyFetches = 0

        assertEquals(CheckOutcome.NewMail(1), checker(cache = cache).check(CheckSource.ALARM))
        // The fetch really was attempted and really did fail. Without this the test passes just
        // as well with prefetchBodies deleted, which is not what it claims to be about.
        assertTrue(server.bodyFetches > 0)
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
