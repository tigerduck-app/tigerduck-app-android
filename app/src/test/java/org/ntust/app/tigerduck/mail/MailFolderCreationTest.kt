@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

/**
 * A Mail2000 account is not guaranteed to have a sent, drafts or trash folder — it may never
 * have had one, and the student can delete one from webmail at any time. These cover the one
 * moment each is allowed to be created: inside the operation that is about to write to it.
 */
class MailFolderCreationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeMailServer()
    private val state = InMemoryMailStateStore()
    private val credentials = InMemoryCredentialStore()
    private val demo = FakeDemoGate()
    private val sent = mutableListOf<String>()
    private val transport = MailTransport { _, message -> sent += message.messageId }

    private inner class TestSetup(scope: CoroutineScope) {
        val cache = MailCache(tmp.root)
        val account = MailAccount(credentials, state, server.factory(), cache, demo, RecordingScheduler(), RecordingNotifier(), scope)
        val repository = MailRepository(
            account, server.factory(), cache, state,
            MailSender(MessageBuilder(), transport, server.factory(), pause = {}), MessageBuilder(), demo, scope,
        )
    }

    private suspend fun TestSetup.signedIn(studentId: String = "b10000001", password: String = "pw"): MailRepository {
        account.signIn(studentId, password)
        return repository
    }

    private fun MailRepository.outgoing(subject: String) =
        OutgoingMail(selfAddress(), listOf(MailAddress(null, "a@x.tw")), emptyList(), emptyList(), subject, "body")

    private fun MailRepository.draft(subject: String) =
        OutgoingMail(selfAddress(), emptyList(), emptyList(), emptyList(), subject, "x")

    // --- created on demand ---------------------------------------------------------------

    @Test
    fun `sending creates the sent folder when the account has none, and files the copy there`() = runTest {
        server.folders.remove("寄件備份匣")
        val repo = TestSetup(backgroundScope).signedIn()
        repo.send(repo.outgoing("hello"), answered = null)
        assertEquals(listOf("寄件備份匣"), server.createAttempts)
        assertEquals(listOf("hello"), server.subjects("寄件備份匣"))
        assertEquals(1, sent.size)
    }

    @Test
    fun `saving a draft creates the drafts folder, and replacing one reuses it`() = runTest {
        server.folders.remove("草稿匣")
        val repo = TestSetup(backgroundScope).signedIn()
        repo.saveDraft(repo.draft("draft 1"), replacingUid = null)
        assertEquals(listOf("草稿匣"), server.createAttempts)
        val first = server.folders.getValue("草稿匣").single().summary.uid
        repo.saveDraft(repo.draft("draft 2"), replacingUid = first)
        assertEquals(listOf("draft 2"), server.subjects("草稿匣"))
        assertEquals("the folder is created once, not per operation", listOf("草稿匣"), server.createAttempts)
    }

    @Test
    fun `deleting creates the trash folder when the account has none, and moves the mail there`() = runTest {
        server.folders.remove("回收筒")
        val uid = server.deliver("a")
        val repo = TestSetup(backgroundScope).signedIn()
        repo.loadPage("INBOX", null)
        assertNull("the account starts without one", repo.folders().nameOf(SpecialFolder.TRASH))
        assertFalse("deleting must mean 'move to trash' again", repo.deletesPermanently("INBOX"))
        repo.delete("INBOX", uid)
        assertEquals(listOf("a"), server.subjects("回收筒"))
        assertTrue(server.subjects("INBOX").isEmpty())
        assertEquals(listOf("回收筒"), server.createAttempts)
    }

    @Test
    fun `a newly created folder is usable by the very operation that created it`() = runTest {
        server.folders.remove("寄件備份匣")
        val repo = TestSetup(backgroundScope).signedIn()
        // Resolve first, so the cached resolution predates the CREATE: without the re-resolve
        // inside it, the send below would still file nothing.
        assertNull(repo.folders().nameOf(SpecialFolder.SENT))
        repo.send(repo.outgoing("hello"), answered = null)
        assertEquals("寄件備份匣", repo.folders().nameOf(SpecialFolder.SENT))
        assertEquals(listOf("hello"), server.subjects("寄件備份匣"))
    }

    /**
     * A CREATE the server refuses because the folder is already there — another client, or a
     * racing operation of ours — is a success: what the folder list says afterwards decides,
     * not what the CREATE answered.
     */
    @Test
    fun `a create refused because the folder already exists still resolves and carries on`() = runTest {
        server.folders.remove("回收筒")
        val uid = server.deliver("a")
        val repo = TestSetup(backgroundScope).signedIn()
        repo.loadPage("INBOX", null)
        assertNull(repo.folders().nameOf(SpecialFolder.TRASH))
        server.folders["回收筒"] = mutableListOf()
        server.refuseCreate += "回收筒"
        assertFalse(repo.deletesPermanently("INBOX"))
        repo.delete("INBOX", uid)
        assertEquals(listOf("a"), server.subjects("回收筒"))
    }

    // --- never created -------------------------------------------------------------------

    @Test
    fun `only sent, drafts and trash are ever created -- never junk, never INBOX`() = runTest {
        server.folders.keys.retainAll(setOf("INBOX"))
        val uid = server.deliver("a")
        val repo = TestSetup(backgroundScope).signedIn()
        repo.loadPage("INBOX", null)
        repo.saveDraft(repo.draft("d"), replacingUid = null)
        repo.send(repo.outgoing("s"), answered = null)
        repo.delete("INBOX", uid)
        assertEquals(setOf("草稿匣", "寄件備份匣", "回收筒"), server.createAttempts.toSet())
        assertFalse("the junk folder is the server classifier's, never ours", server.folders.containsKey("廣告信匣"))
        assertEquals(setOf("INBOX", "草稿匣", "寄件備份匣", "回收筒"), server.folders.keys)
    }

    @Test
    fun `signing in, resolving folders and reading mail create nothing`() = runTest {
        server.folders.keys.retainAll(setOf("INBOX"))
        val uid = server.deliver("a")
        val repo = TestSetup(backgroundScope).signedIn()
        repo.folders()
        repo.inboxStatus()
        repo.loadPage("INBOX", null)
        repo.body("INBOX", uid)
        repo.rawSource("INBOX", uid)
        repo.setSeen("INBOX", uid, true)
        repo.search("INBOX", "a")
        assertEquals(emptyList<String>(), server.createAttempts)
        assertEquals(setOf("INBOX"), server.folders.keys)
    }

    @Test
    fun `removing a draft never creates a drafts folder that could hold no draft to remove`() = runTest {
        server.folders.remove("草稿匣")
        val repo = TestSetup(backgroundScope).signedIn()
        repo.discardDraft(42) // must not throw
        assertEquals(emptyList<String>(), server.createAttempts)
    }

    @Test
    fun `the demo mailbox creates nothing and opens no connection`() = runTest {
        val repo = TestSetup(backgroundScope).signedIn("B10000099", "demo")
        repo.send(repo.outgoing("s"), answered = null)
        repo.saveDraft(repo.draft("d"), replacingUid = null)
        repo.delete("INBOX", 1001)
        repo.discardDraft(1002)
        assertEquals(emptyList<String>(), server.createAttempts)
        assertEquals(0, server.opens)
    }

    // --- a refused create falls back, never blocks ---------------------------------------

    @Test
    fun `a refused sent-folder create still sends the mail`() = runTest {
        server.folders.remove("寄件備份匣")
        server.refuseCreate += "寄件備份匣"
        val repo = TestSetup(backgroundScope).signedIn()
        repo.send(repo.outgoing("hello"), answered = null)
        assertEquals("sending is the point; filing the copy is not", 1, sent.size)
        assertFalse(server.folders.containsKey("寄件備份匣"))
    }

    @Test
    fun `a refused drafts create tells the user the draft was not kept`() = runTest {
        server.folders.remove("草稿匣")
        server.refuseCreate += "草稿匣"
        val repo = TestSetup(backgroundScope).signedIn()
        val result = runCatching { repo.saveDraft(repo.draft("d"), replacingUid = null) }
        assertTrue(result.exceptionOrNull() is MailError.Protocol)
        assertFalse(server.folders.containsKey("草稿匣"))
    }

    /**
     * The one that matters most: a refused trash CREATE must leave deleting exactly where it
     * was — the permanent-delete path, which the message screen only reaches through its own
     * confirmation. It must never quietly hard-delete mail the user asked to move to trash.
     */
    @Test
    fun `a refused trash create routes the delete through the permanent-delete confirmation`() = runTest {
        server.folders.remove("回收筒")
        server.refuseCreate += "回收筒"
        val uid = server.deliver("a")
        val repo = TestSetup(backgroundScope).signedIn()
        repo.loadPage("INBOX", null)
        assertTrue("the user must be asked the permanent-delete question", repo.deletesPermanently("INBOX"))
        assertTrue("nothing is deleted until that is answered", server.subjects("INBOX").isNotEmpty())
        repo.delete("INBOX", uid)
        assertTrue(server.subjects("INBOX").isEmpty())
        assertFalse(server.folders.containsKey("回收筒"))
    }
}
