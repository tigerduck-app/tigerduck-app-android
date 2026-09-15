@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.mail.compose.MessageBuilder
import org.ntust.app.tigerduck.mail.compose.OutgoingMail
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.smtp.MailSender
import org.ntust.app.tigerduck.mail.smtp.MailTransport
import org.ntust.app.tigerduck.mail.store.MailCache

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

    private inner class TestSetup(scope: kotlinx.coroutines.CoroutineScope) {
        val cache = MailCache(tmp.root)
        val account = MailAccount(credentials, state, server.factory(), cache, demo, RecordingScheduler(), RecordingNotifier())
        val repository = MailRepository(
            account, server.factory(), cache, state,
            MailSender(MessageBuilder(), transport, server.factory(), pause = {}), MessageBuilder(), demo, scope,
        )
    }

    @Test
    fun `first page is cached and folders are resolved once`() = runTest {
        server.deliver("a"); server.deliver("b")
        val repo = TestSetup(backgroundScope).signedIn()
        assertEquals("回收筒", repo.folders().nameOf(SpecialFolder.TRASH))
        assertEquals(listOf("b", "a"), repo.loadPage("INBOX", null).messages.map { it.subject })
        assertEquals(listOf("b", "a"), repo.cachedPage("INBOX")!!.messages.map { it.subject })
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
            withTimeout(2_000) {
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
