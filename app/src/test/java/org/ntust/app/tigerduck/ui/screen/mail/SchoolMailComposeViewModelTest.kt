package org.ntust.app.tigerduck.ui.screen.mail

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.ntust.app.tigerduck.mail.smtp.SentCopy
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.testApplicationScope
import org.ntust.app.tigerduck.ui.screen.mail.SchoolMailComposeViewModel.ComposeError
import java.io.ByteArrayInputStream
import org.ntust.app.tigerduck.mail.schoolMailSite

class SchoolMailComposeViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val repo = FakeSchoolMailRepository()
    private lateinit var account: MailAccount
    private lateinit var cache: MailCache
    private val labels = ComposePrefill.Labels({ d, s -> "On $d, $s wrote:" }, "-- Forwarded --", { "From: $it" }, { "Date: $it" }, { "Subject: $it" }, { "To: $it" })
    private val noPicks = PickedAttachmentReader { null }

    @Before
    fun setUp() {
        cache = MailCache(tmp.newFolder("cache"))
        account = MailAccount(InMemoryCredentialStore(), InMemoryMailStateStore(), FakeMailServer().factory(), cache,
            FakeDemoGate(), schoolMailSite(), RecordingScheduler(), RecordingNotifier(), testApplicationScope())
    }

    // The same TestDispatcher backs both Dispatchers.Main and the injected @IoDispatcher, so a
    // withContext(io) hop stays synchronous under the test the way SchoolMailMessageViewModelTest does it.
    private fun handle(mode: ComposeMode, folder: String = "", uid: Long = -1) =
        SavedStateHandle(mapOf("mode" to mode.name, "folder" to folder, "uid" to uid))

    private fun vm(mode: ComposeMode, folder: String = "", uid: Long = -1) =
        SchoolMailComposeViewModel(handle(mode, folder, uid), repo, account, cache, noPicks, main.dispatcher)
            .also { it.prefill(labels) }

    @Test
    fun `quoting an HTML original waits on the IO dispatcher instead of parsing on the main thread`() {
        repo.add("INBOX", mailSummary(5, subject = "期中考"))
        repo.bodies[5] = MailBody("<p>line1</p>", null, emptyList(), emptyMap())
        val io = HeldDispatcher()
        val vm = SchoolMailComposeViewModel(handle(ComposeMode.REPLY, "INBOX", 5), repo, account, cache, noPicks, io)
        vm.prefill(labels)
        // The original is already fetched, and neither jsoup pass has run: the form is still
        // loading rather than sanitizing and flattening the sender's HTML on Main.
        assertTrue(vm.state.value.loading)
        assertEquals("", vm.state.value.body)

        io.drain()
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.body.contains("line1"))
    }

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
    fun `prefill runs once per instance -- a second call changes nothing`() {
        repo.add("INBOX", mailSummary(5, subject = "期中考"))
        repo.bodies[5] = MailBody(null, "line1", emptyList(), emptyMap())
        val vm = vm(ComposeMode.REPLY, "INBOX", 5) // the vm() factory already called prefill() once
        val toAfterFirst = vm.state.value.to
        vm.addAttachments(listOf(ComposeAttachment("p", "p.txt", "text/plain", 2,
            ComposeAttachment.Source.Local { ByteArrayInputStream("hi".toByteArray()) })))
        val attachmentsBefore = vm.state.value.attachments
        val dirtyBefore = vm.state.value.dirty

        vm.prefill(labels) // second call -- must be a no-op
        assertEquals(toAfterFirst, vm.state.value.to)
        assertEquals(attachmentsBefore, vm.state.value.attachments)
        assertEquals(dirtyBefore, vm.state.value.dirty)
    }

    @Test
    fun `retry after a failure keeps edited text and local picks, adds the carried originals, and leaves the form dirty`() {
        repo.add("INBOX", mailSummary(5, hasAttachments = true))
        repo.bodyError = MailError.Network()
        val vm = vm(ComposeMode.FORWARD, "INBOX", 5)
        assertTrue(vm.state.value.loadError != null)

        vm.setBody("我自己寫的內容")
        vm.addAttachments(listOf(ComposeAttachment("p", "p.txt", "text/plain", 2,
            ComposeAttachment.Source.Local { ByteArrayInputStream("hi".toByteArray()) })))

        repo.bodyError = null
        repo.bodies[5] = MailBody(null, "body", listOf(MailAttachment("2", "a.pdf", "application/pdf", 12, null)), emptyMap())
        vm.retryPrefill(labels)

        assertEquals(null, vm.state.value.loadError)
        assertEquals("我自己寫的內容", vm.state.value.body)
        assertEquals(setOf("p.txt", "a.pdf"), vm.state.value.attachments.map { it.fileName }.toSet())
        assertTrue(vm.state.value.dirty)
    }

    @Test
    fun `editing fields, changing attachments, and a send validation error all leave the load failure and Retry visible`() {
        repo.add("INBOX", mailSummary(5, subject = "期中考"))
        repo.bodyError = MailError.Network()
        val vm = vm(ComposeMode.REPLY, "INBOX", 5)
        assertTrue(vm.state.value.loadError != null)

        vm.setTo("typed@x.tw")
        assertTrue(vm.state.value.loadError != null)
        assertEquals("typed@x.tw", vm.state.value.to)

        vm.addAttachments(listOf(ComposeAttachment("p", "p.txt", "text/plain", 2,
            ComposeAttachment.Source.Local { ByteArrayInputStream("hi".toByteArray()) })))
        assertTrue(vm.state.value.loadError != null)
        vm.removeAttachment("p")
        assertTrue(vm.state.value.loadError != null)

        vm.setTo("not an address")
        vm.send()
        assertEquals(ComposeError.InvalidRecipients(listOf("not an address")), vm.state.value.error)
        assertTrue(vm.state.value.loadError != null)
    }

    @Test
    fun `a pick that can't be measured shows the attachment error instead of vanishing silently`() {
        val unmeasurable = PickedAttachmentReader { null }
        val vm = SchoolMailComposeViewModel(handle(ComposeMode.NEW), repo, account, cache, unmeasurable, main.dispatcher)
        vm.addPicked(listOf<Uri?>(null))
        assertEquals(ComposeError.TooLarge, vm.state.value.error)
        assertTrue(vm.state.value.attachments.isEmpty())
    }

    // --- the sent copy ---------------------------------------------------------------

    @Test
    fun `a filed sent copy raises no notice`() {
        val vm = vm(ComposeMode.NEW)
        vm.setTo("a@x.tw")
        vm.send()
        assertTrue(vm.state.value.done)
        assertFalse(vm.state.value.sentCopyMissing)
    }

    @Test
    fun `a sent copy that never reached the server is a notice, not a failed send`() {
        listOf(SentCopy.NotAttempted, SentCopy.Unknown, SentCopy.Failed(MailError.ServerBusy())).forEach { outcome ->
            repo.sentCopy = outcome
            val vm = vm(ComposeMode.NEW)
            vm.setTo("a@x.tw")
            vm.send()
            val s = vm.state.value
            assertTrue("$outcome still sent the mail", s.done)
            assertEquals("$outcome must never read as a failed send", null, s.error)
            assertTrue("$outcome must say the copy is missing", s.sentCopyMissing)
        }
        assertEquals(3, repo.sent.size)
    }

    @Test
    fun `a rejected password on the sent copy still reaches the account`() {
        // The APPEND logs in with the same stored password; nothing throws, because the mail
        // itself went out -- so only the outcome can carry the rejection to section 7.4.
        repo.sentCopy = SentCopy.Failed(MailError.AuthFailed())
        val vm = vm(ComposeMode.NEW)
        vm.setTo("a@x.tw")
        vm.send()
        assertTrue(account.authFailed.value)
        assertTrue("the send still succeeded", vm.state.value.done)
        assertEquals(null, vm.state.value.error)
        assertTrue(vm.state.value.sentCopyMissing)
    }

    @Test
    fun `an empty pick clears nothing`() {
        val vm = vm(ComposeMode.NEW)
        vm.setTo("a@x.tw")
        vm.addPicked(emptyList())
        assertEquals("a@x.tw", vm.state.value.to)
        assertEquals(null, vm.state.value.error)
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
    fun `a non-ASCII recipient is reported invalid and nothing is sent`() {
        val vm = vm(ComposeMode.NEW)
        vm.setTo("中文@x.tw")
        vm.send()
        assertEquals(ComposeError.InvalidRecipients(listOf("中文@x.tw")), vm.state.value.error)
        assertTrue(repo.sent.isEmpty())
    }

    @Test
    fun `a forwarded attachment's already-encoded size is not grown again`() {
        repo.add("INBOX", mailSummary(5, hasAttachments = true))
        repo.bodies[5] = MailBody(null, "body", listOf(MailAttachment("2", "big.pdf", "application/pdf", 49L * 1024 * 1024, null)), emptyMap())
        val vm = vm(ComposeMode.FORWARD, "INBOX", 5)
        vm.setTo("x@y.tw")
        vm.send()
        assertEquals(null, vm.state.value.error)
        assertTrue(vm.state.value.done)
    }

    @Test
    fun `a forwarded attachment still over budget without double counting is rejected`() {
        repo.add("INBOX", mailSummary(5, hasAttachments = true))
        repo.bodies[5] = MailBody(null, "body", listOf(MailAttachment("2", "big.pdf", "application/pdf", 51L * 1024 * 1024, null)), emptyMap())
        val vm = vm(ComposeMode.FORWARD, "INBOX", 5)
        vm.setTo("x@y.tw")
        vm.send()
        assertEquals(ComposeError.TooLarge, vm.state.value.error)
        assertTrue(repo.sent.isEmpty())
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
    fun `cancelling the view model scope mid-send still cleans up staged files`() {
        repo.add("INBOX", mailSummary(5, hasAttachments = true))
        repo.bodies[5] = MailBody(
            null, "body",
            listOf(
                MailAttachment("2", "a.pdf", "application/pdf", 12, null),
                MailAttachment("3", "b.pdf", "application/pdf", 12, null),
            ),
            emptyMap(),
        )
        val vm = vm(ComposeMode.FORWARD, "INBOX", 5)
        vm.setTo("x@y.tw")
        repo.onWriteAttachment = { vm.viewModelScope.cancel() }
        vm.send()
        assertTrue(cache.attachmentsDir.listFiles().orEmpty().none { it.name.startsWith("outgoing-") })
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
    fun `saveDraft reports an invalid recipient instead of silently dropping it`() {
        val vm = vm(ComposeMode.NEW)
        vm.setTo("not an address, ok@x.tw")
        vm.saveDraft()
        assertEquals(ComposeError.InvalidRecipients(listOf("not an address")), vm.state.value.error)
        assertTrue(repo.drafts.isEmpty())
    }

    @Test
    fun `saveDraft applies the same total-size check as send`() {
        val vm = vm(ComposeMode.NEW)
        vm.addAttachments(listOf(ComposeAttachment("big", "big.bin", "application/octet-stream", 60_000_000,
            ComposeAttachment.Source.Local { ByteArrayInputStream(ByteArray(0)) })))
        vm.saveDraft()
        assertEquals(ComposeError.TooLarge, vm.state.value.error)
        assertTrue(repo.drafts.isEmpty())
    }

    @Test
    fun `a FolderChanged discarding the sent draft still ends in done with no send failure`() {
        repo.add("草稿匣", mailSummary(9, subject = "draft", to = listOf(MailAddress(null, "a@x.tw"))))
        repo.bodies[9] = MailBody(null, "draft body", emptyList(), emptyMap())
        repo.discardDraftError = MailError.FolderChanged()
        val vm = vm(ComposeMode.DRAFT, "草稿匣", 9)
        vm.send()
        assertTrue(vm.state.value.done)
        assertEquals(null, vm.state.value.error)
        assertEquals(1, repo.sent.size)
    }

    @Test
    fun `cancellation while discarding the sent draft is not swallowed as a send failure`() {
        repo.add("草稿匣", mailSummary(9, subject = "draft", to = listOf(MailAddress(null, "a@x.tw"))))
        repo.bodies[9] = MailBody(null, "draft body", emptyList(), emptyMap())
        repo.discardDraftError = CancellationException("cancelled")
        val vm = vm(ComposeMode.DRAFT, "草稿匣", 9)
        vm.send()
        // The CancellationException unwinds past the final "done = true" update and is never
        // mapped through onError -- a cancelled coroutine is not "best effort".
        assertFalse(vm.state.value.done)
        assertEquals(null, vm.state.value.error)
    }

    @Test
    fun `a failed prefill shows the error with fields untouched, and retry re-attempts it`() {
        repo.add("INBOX", mailSummary(5, subject = "期中考"))
        repo.bodyError = MailError.Network()
        val vm = vm(ComposeMode.REPLY, "INBOX", 5)
        assertTrue(vm.state.value.loadError != null)
        assertFalse(vm.state.value.loading)
        assertEquals("", vm.state.value.to)

        repo.bodyError = null
        repo.bodies[5] = MailBody(null, "line1", emptyList(), emptyMap())
        vm.retryPrefill(labels)
        assertEquals(null, vm.state.value.loadError)
        assertEquals("教務處 <office@mail.ntust.edu.tw>", vm.state.value.to)
    }

    @Test
    fun `sending a reply whose source never loaded does not mark any original answered`() {
        repo.add("INBOX", mailSummary(5, subject = "期中考"))
        repo.bodyError = MailError.Network()
        val vm = vm(ComposeMode.REPLY, "INBOX", 5)
        assertTrue(vm.state.value.loadError != null)
        vm.setTo("x@y.tw")
        vm.send()
        assertEquals(null, repo.sent.single().second)
    }

    @Test
    fun `saving a draft whose source never loaded creates a new draft instead of replacing it`() {
        repo.add("草稿匣", mailSummary(9, subject = "draft", to = listOf(MailAddress(null, "a@x.tw"))))
        repo.bodyError = MailError.Network()
        val vm = vm(ComposeMode.DRAFT, "草稿匣", 9)
        assertTrue(vm.state.value.loadError != null)
        vm.setTo("x@y.tw")
        vm.saveDraft()
        assertEquals(null, repo.drafts.single().second)
    }

    @Test
    fun `sending from a draft whose source never loaded does not discard it`() {
        repo.add("草稿匣", mailSummary(9, subject = "draft", to = listOf(MailAddress(null, "a@x.tw"))))
        repo.bodyError = MailError.Network()
        val vm = vm(ComposeMode.DRAFT, "草稿匣", 9)
        assertTrue(vm.state.value.loadError != null)
        vm.setTo("x@y.tw")
        vm.send()
        assertTrue(repo.discardedDrafts.isEmpty())
    }

    @Test
    fun `send and saveDraft are refused while a picked attachment is still being measured`() {
        val pickerDispatcher = StandardTestDispatcher()
        val slowReader = PickedAttachmentReader {
            ComposeAttachment("p", "p.txt", "text/plain", 3, ComposeAttachment.Source.Local { ByteArrayInputStream("hi!".toByteArray()) })
        }
        val vm = SchoolMailComposeViewModel(handle(ComposeMode.NEW), repo, account, cache, slowReader, pickerDispatcher)
        vm.setTo("a@x.tw")
        vm.addPicked(listOf<Uri?>(null))
        assertTrue(vm.state.value.pendingPicks > 0)
        vm.send()
        assertTrue(repo.sent.isEmpty())
        vm.saveDraft()
        assertTrue(repo.drafts.isEmpty())

        pickerDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, vm.state.value.pendingPicks)
        assertEquals(listOf("p.txt"), vm.state.value.attachments.map { it.fileName })
        vm.send()
        assertEquals(1, repo.sent.size)
    }

    @Test
    fun `acknowledging an error leaves the inline message alone, and a repeated failure raises the popup again`() {
        val vm = vm(ComposeMode.NEW)
        vm.setTo("a@x.tw")
        vm.addAttachments(listOf(ComposeAttachment("big", "big.bin", "application/octet-stream", 60_000_000,
            ComposeAttachment.Source.Local { ByteArrayInputStream(ByteArray(0)) })))
        vm.send()
        assertEquals(ComposeError.TooLarge, vm.state.value.error)
        assertFalse(vm.state.value.errorAcknowledged)

        vm.acknowledgeError()
        assertTrue(vm.state.value.errorAcknowledged)
        // The popup is dismissed, but the inline message this is checked against must survive it.
        assertEquals(ComposeError.TooLarge, vm.state.value.error)

        // The exact same error shape recurs (still over budget) -- the earlier acknowledgement
        // must not swallow the popup for this new failure.
        vm.send()
        assertEquals(ComposeError.TooLarge, vm.state.value.error)
        assertFalse(vm.state.value.errorAcknowledged)
    }

    @Test
    fun `editing a field after an acknowledged error resets the acknowledgement along with the error`() {
        val vm = vm(ComposeMode.NEW)
        vm.send()
        assertEquals(ComposeError.NoRecipient, vm.state.value.error)
        vm.acknowledgeError()
        assertTrue(vm.state.value.errorAcknowledged)

        vm.setTo("a@x.tw")
        assertEquals(null, vm.state.value.error)
        assertFalse(vm.state.value.errorAcknowledged)
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
