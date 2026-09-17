package org.ntust.app.tigerduck.ui.screen.mail

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.mail.ComposeMode
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
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailAttachment
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.ui.screen.mail.SchoolMailComposeViewModel.ComposeError
import java.io.ByteArrayInputStream

class SchoolMailComposeViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val repo = FakeSchoolMailRepository()
    private lateinit var account: MailAccount
    private lateinit var cache: MailCache
    private val labels = ComposePrefill.Labels({ d, s -> "On $d, $s wrote:" }, "-- Forwarded --", { "From: $it" }, { "Date: $it" }, { "Subject: $it" }, { "To: $it" })

    @Before
    fun setUp() {
        cache = MailCache(tmp.newFolder("cache"))
        account = MailAccount(InMemoryCredentialStore(), InMemoryMailStateStore(), FakeMailServer().factory(), cache,
            FakeDemoGate(), RecordingScheduler(), RecordingNotifier())
    }

    // The same TestDispatcher backs both Dispatchers.Main and the injected @IoDispatcher, so a
    // withContext(io) hop stays synchronous under the test the way SchoolMailMessageViewModelTest does it.
    private fun vm(mode: ComposeMode, folder: String = "", uid: Long = -1) =
        SchoolMailComposeViewModel(SavedStateHandle(mapOf("mode" to mode.name, "folder" to folder, "uid" to uid)), repo, account, cache, main.dispatcher)
            .also { it.prefill(labels) }

    @Test
    fun `reply prefills, sends with threading headers and marks the original answered`() {
        repo.add("INBOX", mailSummary(5, subject = "期中考"))
        repo.bodies[5] = MailBody(null, "line1", emptyList(), emptyMap())
        val vm = vm(ComposeMode.REPLY, "INBOX", 5)
        val s = vm.state.value
        assertEquals("教務處 <office@mail.ntust.edu.tw>", s.to)
        assertEquals("Re: 期中考", s.subject)
        assertFalse(s.dirty)

        vm.setBody("好的\n" + s.body)
        assertTrue(vm.state.value.dirty)
        vm.send()
        val (mail, answered) = repo.sent.single()
        assertEquals("INBOX" to 5L, answered)
        assertEquals("<m5@x>", mail.inReplyTo)
        assertEquals(repo.self, mail.from)
        assertEquals(listOf("office@mail.ntust.edu.tw"), mail.to.map { it.address })
        assertTrue(vm.state.value.done)
    }

    @Test
    fun `forward carries the original attachments, fetched when sending`() {
        repo.add("INBOX", mailSummary(5, hasAttachments = true))
        repo.bodies[5] = MailBody(null, "body", listOf(MailAttachment("2", "a.pdf", "application/pdf", 12, null)), emptyMap())
        val vm = vm(ComposeMode.FORWARD, "INBOX", 5)
        assertEquals(listOf("a.pdf"), vm.state.value.attachments.map { it.fileName })
        vm.setTo("x@y.tw")
        vm.send()
        assertEquals(listOf("a.pdf" to "attachment 2"), repo.sentAttachments.single())
        assertEquals(null, repo.sent.single().second)
        assertTrue(cache.attachmentsDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `send checks recipients and size first`() {
        val vm = vm(ComposeMode.NEW)
        vm.setTo("not an address")
        vm.send()
        assertEquals(ComposeError.InvalidRecipients(listOf("not an address")), vm.state.value.error)
        vm.setTo("")
        vm.send()
        assertEquals(ComposeError.NoRecipient, vm.state.value.error)
        vm.setTo("a@x.tw")
        vm.addAttachments(listOf(ComposeAttachment("big", "big.bin", "application/octet-stream", 60_000_000,
            ComposeAttachment.Source.Local { ByteArrayInputStream(ByteArray(0)) })))
        vm.send()
        assertEquals(ComposeError.TooLarge, vm.state.value.error)
        vm.removeAttachment("big")
        vm.addAttachments(listOf(ComposeAttachment("s", "s.txt", "text/plain", 2,
            ComposeAttachment.Source.Local { ByteArrayInputStream("hi".toByteArray()) })))
        vm.send()
        assertEquals(listOf("s.txt" to "hi"), repo.sentAttachments.single())
    }

    @Test
    fun `a failed send keeps everything on screen`() {
        repo.sendError = MailError.Network()
        val vm = vm(ComposeMode.NEW)
        vm.setTo("a@x.tw")
        vm.setBody("keep me")
        vm.send()
        assertTrue(vm.state.value.error is ComposeError.SendFailed)
        assertFalse(vm.state.value.done)
        assertEquals("keep me", vm.state.value.body)
    }

    @Test
    fun `editing a draft saves over it, and sending it discards the draft`() {
        repo.add("草稿匣", mailSummary(9, subject = "draft", to = listOf(MailAddress(null, "a@x.tw"))))
        repo.bodies[9] = MailBody(null, "draft body", emptyList(), emptyMap())
        val saving = vm(ComposeMode.DRAFT, "草稿匣", 9)
        assertEquals("a@x.tw", saving.state.value.to)
        assertEquals("draft body", saving.state.value.body)
        saving.saveDraft()
        assertEquals(9L, repo.drafts.single().second)
        assertTrue(saving.state.value.done)
        assertTrue(saving.state.value.savedDraft)

        val sending = vm(ComposeMode.DRAFT, "草稿匣", 9)
        sending.send()
        assertEquals(listOf(9L), repo.discardedDrafts)
        assertEquals(null, repo.sent.single().second)
    }

    @Test
    fun `a new mail is clean until something is typed`() {
        val vm = vm(ComposeMode.NEW)
        assertFalse(vm.state.value.dirty)
        vm.setSubject("x")
        assertTrue(vm.state.value.dirty)
        vm.discard()
        assertTrue(vm.state.value.done)
        assertTrue(repo.drafts.isEmpty())
    }
}
