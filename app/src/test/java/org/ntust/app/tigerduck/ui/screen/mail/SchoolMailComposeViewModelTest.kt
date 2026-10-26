@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.ui.screen.mail

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.ntust.app.tigerduck.mail.ComposeMode
import org.ntust.app.tigerduck.mail.FakeDemoGate
import org.ntust.app.tigerduck.mail.FakeMailServer
import org.ntust.app.tigerduck.mail.FakeSchoolMailRepository
import org.ntust.app.tigerduck.mail.InMemoryCredentialStore
import org.ntust.app.tigerduck.mail.InMemoryMailStateStore
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.SentCopyNotice
import org.ntust.app.tigerduck.mail.SentCopyNotices
import org.ntust.app.tigerduck.mail.SentCopyOrigin
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.mail.RecordingNotifier
import org.ntust.app.tigerduck.mail.RecordingScheduler
import org.ntust.app.tigerduck.mail.cancelAndJoinBlocking
import org.ntust.app.tigerduck.mail.mailSummary
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailAttachment
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.smtp.SentCopy
import org.ntust.app.tigerduck.mail.store.HeldSendStore
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.testApplicationScope
import org.ntust.app.tigerduck.ui.screen.mail.SchoolMailComposeViewModel.ComposeError
import org.ntust.app.tigerduck.ui.screen.mail.SchoolMailComposeViewModel.RecipientSlot
import java.io.ByteArrayInputStream
import java.io.File
import org.ntust.app.tigerduck.mail.schoolMailSite

/** The To field as it hands [value] over once it has been typed and the field left: one bubble per recipient. */
private fun SchoolMailComposeViewModel.setTo(value: String) =
    updateRecipients(RecipientSlot.TO) { RecipientField.of(value) }

class SchoolMailComposeViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    // What the account and the notices launch, joined before TemporaryFolder deletes the cache
    // a sign-out may still be wiping there.
    private val appScope = testApplicationScope()

    @After
    fun stopTheAppScope() = appScope.cancelAndJoinBlocking()

    private val repo = FakeSchoolMailRepository()
    private lateinit var account: MailAccount
    private lateinit var cache: MailCache
    private lateinit var notices: SentCopyNotices
    private lateinit var heldSends: HeldSendStore
    private val labels = ComposePrefill.Labels({ d, s -> "On $d, $s wrote:" }, "-- Forwarded --", { "From: $it" }, { "Date: $it" }, { "Subject: $it" }, { "To: $it" })
    private val noPicks = PickedAttachmentReader { null }

    @Before
    fun setUp() {
        cache = MailCache(tmp.newFolder("cache"))
        account = MailAccount(InMemoryCredentialStore(), InMemoryMailStateStore(), FakeMailServer().factory(), cache,
            FakeDemoGate(), schoolMailSite(), RecordingScheduler(), RecordingNotifier(), appScope)
        notices = SentCopyNotices(account, appScope)
        heldSends = HeldSendStore(heldRoot)
    }

    private val heldRoot by lazy { File(tmp.root, "held") }

    // The same TestDispatcher backs both Dispatchers.Main and the injected @IoDispatcher, so a
    // withContext(io) hop stays synchronous under the test the way SchoolMailMessageViewModelTest does it.
    private fun handle(mode: ComposeMode, folder: String = "", uid: Long = -1, mailto: String = "") =
        SavedStateHandle(mapOf("mode" to mode.name, "folder" to folder, "uid" to uid, "mailto" to mailto))

    // With no hold before a send, as every test but the hold's own expects; see [heldVm].
    private fun vm(mode: ComposeMode, folder: String = "", uid: Long = -1, mailto: String = "") =
        SchoolMailComposeViewModel(handle(mode, folder, uid, mailto), repo, account, cache, noPicks, notices, main.dispatcher, heldSends)
            .also { it.sendHoldMs = 0; it.prefill(labels) }

    /** A new mail to one recipient, held before it goes out as on the phone. */
    private fun heldVm() = SchoolMailComposeViewModel(handle(ComposeMode.NEW), repo, account, cache, noPicks, notices, main.dispatcher, heldSends)
        .also { it.prefill(labels); it.setTo("amy@mail.ntust.edu.tw"); it.setSubject("Hi") }

    @Test
    fun `quoting an HTML original waits on the IO dispatcher instead of parsing on the main thread`() {
        repo.add("INBOX", mailSummary(5, subject = "期中考"))
        repo.bodies[5] = MailBody("<p>line1</p>", null, emptyList(), emptyMap())
        val io = HeldDispatcher()
        val vm = SchoolMailComposeViewModel(handle(ComposeMode.REPLY, "INBOX", 5), repo, account, cache, noPicks, notices, io, heldSends).also { it.sendHoldMs = 0 }
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
    fun `a recipient field suggests the people in the mail on the phone, and a pick becomes a bubble`() {
        val teacher = MailAddress("王大明", "wang@example.com")
        repo.correspondence = listOf(mailSummary(1, from = teacher))
        val vm = vm(ComposeMode.NEW)

        vm.updateRecipients(RecipientSlot.TO) { it.typed(emptyList(), "大明") }
        assertEquals(listOf(teacher), vm.state.value.suggestions(RecipientSlot.TO))
        assertEquals("only the field typed in", emptyList<MailAddress>(), vm.state.value.suggestions(RecipientSlot.CC))

        vm.updateRecipients(RecipientSlot.TO) { it.pick(teacher) }
        assertEquals(listOf("王大明 <wang@example.com>"), vm.state.value.to.entries)
        assertEquals("", vm.state.value.to.draft)
        assertEquals(emptyList<MailAddress>(), vm.state.value.suggestions(RecipientSlot.TO))
        // On the mail already, so not suggested in another field either.
        vm.updateRecipients(RecipientSlot.CC) { it.typed(emptyList(), "wang") }
        assertEquals(emptyList<MailAddress>(), vm.state.value.suggestions(RecipientSlot.CC))
    }

    @Test
    fun `an address typed in full still suggests its contact, with the name the mail gave`() {
        val teacher = MailAddress("王大明", "wang@example.com")
        repo.correspondence = listOf(mailSummary(1, from = teacher))
        val vm = vm(ComposeMode.NEW)

        vm.updateRecipients(RecipientSlot.TO) { it.typed(emptyList(), "wang@example.com") }

        assertEquals(listOf(teacher), vm.state.value.suggestions(RecipientSlot.TO))
    }

    @Test
    fun `the suggestions go with a sign-out`() {
        repo.correspondence = listOf(mailSummary(1, from = MailAddress("王大明", "wang@example.com")))
        val vm = vm(ComposeMode.NEW)
        vm.updateRecipients(RecipientSlot.TO) { it.typed(emptyList(), "wang") }
        assertEquals(1, vm.state.value.suggestions(RecipientSlot.TO).size)

        account.signOut()

        assertEquals(emptyList<MailAddress>(), vm.state.value.suggestions(RecipientSlot.TO))
    }

    @Test
    fun `contacts read for a student another one signs in over are not suggested`() {
        val server = FakeMailServer().apply { passwords["B10000002"] = "pw" }
        val account = MailAccount(InMemoryCredentialStore(), InMemoryMailStateStore(), server.factory(), cache,
            FakeDemoGate(), schoolMailSite(), RecordingScheduler(), RecordingNotifier(), appScope)
        assertNull(runBlocking { account.signIn("b10000001", "pw") })
        repo.correspondence = listOf(mailSummary(1, from = MailAddress("王大明", "wang@example.com")))
        val io = HeldDispatcher()
        val vm = SchoolMailComposeViewModel(handle(ComposeMode.NEW), repo, account, cache, noPicks, notices, io, heldSends).also { it.sendHoldMs = 0 }
        // The load gets its answer once the next student has left this one behind, and before their
        // sign-in moves the account on.
        account.onLockWait = { io.drain() }
        assertNull(runBlocking { account.signIn("b10000002", "pw") })
        account.onLockWait = null

        vm.updateRecipients(RecipientSlot.TO) { it.typed(emptyList(), "wang") }
        assertEquals(emptyList<MailAddress>(), vm.state.value.suggestions(RecipientSlot.TO))
    }

    @Test
    fun `a sent mail is held for five seconds before it goes out`() {
        val vm = heldVm()

        vm.send()

        assertTrue(vm.state.value.holding)
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailComposeViewModel.SEND_HOLD_MS - 1)
        assertTrue("nothing goes out during the hold", repo.sent.isEmpty())
        main.dispatcher.scheduler.advanceTimeBy(1)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(listOf("amy@mail.ntust.edu.tw"), repo.sent.single().first.to.map { it.address })
        assertFalse(vm.state.value.holding)
        assertTrue(vm.state.value.done)
    }

    @Test
    fun `a longer hold asked for, as accessibility settings ask, keeps the mail that long`() {
        val vm = heldVm()

        vm.send(holdMs = 20_000)
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailComposeViewModel.SEND_HOLD_MS)
        main.dispatcher.scheduler.runCurrent()

        assertTrue(vm.state.value.holding)
        assertTrue(repo.sent.isEmpty())
        main.dispatcher.scheduler.advanceTimeBy(20_000)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(1, repo.sent.size)
    }

    @Test
    fun `undo during the hold sends nothing and leaves the mail in the editor`() {
        val vm = heldVm()
        vm.send()

        vm.undoSend()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailComposeViewModel.SEND_HOLD_MS * 2)
        main.dispatcher.scheduler.runCurrent()

        assertTrue(repo.sent.isEmpty())
        val state = vm.state.value
        assertFalse(state.holding)
        assertFalse(state.sending)
        assertFalse(state.done)
        assertTrue("the screen says the mail is back", state.undone)
        assertEquals(listOf("amy@mail.ntust.edu.tw"), state.to.entries)
        assertEquals("Hi", state.subject)
        vm.acknowledgeUndone()
        assertFalse(vm.state.value.undone)
        assertTrue("it can be sent again", vm.requestSend())
    }

    @Test
    fun `leaving the screen during the hold sends the mail at once`() {
        val vm = heldVm()
        vm.send()

        vm.sendHeldNow()

        assertEquals(1, repo.sent.size)
        assertTrue(vm.state.value.done)
        // The hold's end comes and goes with nothing left to send.
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailComposeViewModel.SEND_HOLD_MS * 2)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(1, repo.sent.size)
    }

    @Test
    fun `a mail held while its account signs out does not go out`() {
        val vm = heldVm()
        vm.send()

        account.signOut()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailComposeViewModel.SEND_HOLD_MS)
        main.dispatcher.scheduler.runCurrent()

        assertTrue(repo.sent.isEmpty())
        assertFalse(vm.state.value.sending)
    }

    @Test
    fun `during the hold nothing else is sent or saved, and undo after it has gone does nothing`() {
        val vm = heldVm()
        vm.send()

        assertFalse(vm.requestSend())
        vm.send()
        vm.saveDraft()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailComposeViewModel.SEND_HOLD_MS)
        main.dispatcher.scheduler.runCurrent()
        vm.undoSend()

        assertEquals(1, repo.sent.size)
        assertTrue(repo.drafts.isEmpty())
        assertFalse(vm.state.value.undone)
    }

    @Test
    fun `a new message opened for someone starts addressed to them and is not dirty`() {
        val vm = SchoolMailComposeViewModel(
            SavedStateHandle(mapOf("mode" to "NEW", "folder" to "", "uid" to -1L, "to" to "amy@mail.ntust.edu.tw")),
            repo, account, cache, noPicks, notices, main.dispatcher, heldSends,
        ).also { it.prefill(labels) }

        assertEquals(listOf("amy@mail.ntust.edu.tw"), vm.state.value.to.entries)
        assertFalse("backing out untouched asks nothing about a draft", vm.state.value.dirty)
        vm.setBody("Hello")
        assertTrue(vm.state.value.dirty)
    }

    @Test
    fun `reply prefills, sends with threading headers and marks the original answered`() {
        repo.add("INBOX", mailSummary(5, subject = "期中考"))
        repo.bodies[5] = MailBody(null, "line1", emptyList(), emptyMap())
        val vm = vm(ComposeMode.REPLY, "INBOX", 5)
        val s = vm.state.value
        assertEquals("教務處 <office@mail.ntust.edu.tw>", s.to.text)
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
        assertEquals("typed@x.tw", vm.state.value.to.text)

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
        val vm = SchoolMailComposeViewModel(handle(ComposeMode.NEW), repo, account, cache, unmeasurable, notices, main.dispatcher, heldSends).also { it.sendHoldMs = 0 }
        vm.addPicked(listOf<Uri?>(null))
        assertEquals(ComposeError.TooLarge, vm.state.value.error)
        assertTrue(vm.state.value.attachments.isEmpty())
    }

    // --- a mailto link -----------------------------------------------------------------

    @Test
    fun `a mailto link starts a new mail from its fields, and leaving it untouched asks nothing`() {
        val vm = SchoolMailComposeViewModel(
            handle(ComposeMode.NEW, mailto = "mailto:office@mail.ntust.edu.tw?cc=b@x.tw&subject=%E8%AB%8B%E5%95%8F&body=Hi"),
            repo, account, cache, noPicks, notices, main.dispatcher, heldSends,
        ).also { it.sendHoldMs = 0 }
        vm.prefill(labels)
        val s = vm.state.value
        assertEquals(listOf("office@mail.ntust.edu.tw"), s.to.entries)
        assertEquals(listOf("b@x.tw"), s.cc.entries)
        assertTrue(s.showCcBcc)
        assertEquals("請問", s.subject)
        assertEquals("Hi", s.body)
        assertFalse("nothing typed yet", s.dirty)
        vm.send()
        assertEquals(listOf("office@mail.ntust.edu.tw"), repo.sent.single().first.to.map { it.address })
    }

    // --- the sent copy ---------------------------------------------------------------

    @Test
    fun `a filed sent copy raises no notice`() {
        listOf(SentCopy.Filed, SentCopy.ServerFiledItself).forEach { outcome ->
            repo.sentCopy = outcome
            val vm = vm(ComposeMode.NEW)
            vm.setTo("a@x.tw")
            vm.send()
            assertTrue(vm.state.value.done)
            assertNull("$outcome", notices.posted?.notice)
        }
    }

    @Test
    fun `a sent copy that never reached the server is a notice with its reason, not a failed send`() {
        val busy = MailError.ServerBusy()
        listOf(
            SentCopy.NotAttempted to SentCopyNotice.NoFolder,
            SentCopy.Unknown to SentCopyNotice.Unknown,
            SentCopy.Failed(busy) to SentCopyNotice.Failed(busy),
        ).forEach { (outcome, notice) ->
            notices.dismiss()
            repo.sentCopy = outcome
            val vm = vm(ComposeMode.NEW)
            vm.setTo("a@x.tw")
            vm.send()
            val s = vm.state.value
            assertTrue("$outcome still sent the mail", s.done)
            assertEquals("$outcome must never read as a failed send", null, s.error)
            assertEquals("$outcome must say why the copy is missing", notice, notices.posted?.notice)
        }
        assertEquals(3, repo.sent.size)
    }

    @Test
    fun `a sent-copy notice names the screen the mail goes back to`() {
        repo.sentCopy = SentCopy.NotAttempted
        repo.add("INBOX", mailSummary(5, subject = "期中考"))
        repo.bodies[5] = MailBody(null, "line1", emptyList(), emptyMap())
        fun originAfterSending(vm: SchoolMailComposeViewModel): SentCopyOrigin? {
            notices.dismiss()
            if (vm.state.value.to.entries.isEmpty()) vm.setTo("a@x.tw")
            vm.send()
            return notices.posted?.origin
        }
        assertEquals(SentCopyOrigin.List, originAfterSending(vm(ComposeMode.NEW)))
        assertEquals(SentCopyOrigin.Message("INBOX", 5), originAfterSending(vm(ComposeMode.REPLY, "INBOX", 5)))
        assertEquals(SentCopyOrigin.Message("INBOX", 5), originAfterSending(vm(ComposeMode.FORWARD, "INBOX", 5)))
        // A mailto: link tapped in a mail goes back to that mail.
        assertEquals(
            SentCopyOrigin.Message("INBOX", 5),
            originAfterSending(vm(ComposeMode.NEW, "INBOX", 5, mailto = "mailto:office@mail.ntust.edu.tw")),
        )
    }

    @Test
    fun `a send the account left during reports nothing to the next one`() {
        repo.sentCopy = SentCopy.NotAttempted
        // Unconfined, so the sign-out's cleanup has run by the time the send comes back.
        val notices = SentCopyNotices(account, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val vm = SchoolMailComposeViewModel(handle(ComposeMode.NEW), repo, account, cache, noPicks, notices, main.dispatcher, heldSends).also { it.sendHoldMs = 0 }
        vm.prefill(labels)
        vm.setTo("a@x.tw")
        repo.onSend = { account.signOut() }
        vm.send()
        assertTrue(vm.state.value.done)
        assertNull(notices.posted)
    }

    // As on the message screen: a rejection that comes back once its account has left is not
    // the next sign-in's (spec §7.4).

    @Test
    fun `an original refused after its account left leaves the next sign-in alone`() {
        repo.add("INBOX", mailSummary(5, subject = "期中考"))
        repo.onBody = { account.signOut() }
        repo.bodyError = MailError.AuthFailed()

        vm(ComposeMode.REPLY, "INBOX", 5)

        assertFalse(account.authFailed.value)
    }

    @Test
    fun `a send refused after its account left leaves the next sign-in alone`() {
        repo.onSend = { account.signOut() }
        repo.sendError = MailError.AuthFailed()
        val vm = vm(ComposeMode.NEW)
        vm.setTo("a@x.tw")

        vm.send()

        assertFalse(account.authFailed.value)
    }

    @Test
    fun `a sent copy refused after its account left leaves the next sign-in alone`() {
        repo.onSend = { account.signOut() }
        repo.sentCopy = SentCopy.Failed(MailError.AuthFailed())
        val vm = vm(ComposeMode.NEW)
        vm.setTo("a@x.tw")

        vm.send()

        assertFalse(account.authFailed.value)
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
        assertTrue(notices.posted?.notice is SentCopyNotice.Failed)
    }

    @Test
    fun `an empty pick clears nothing`() {
        val vm = vm(ComposeMode.NEW)
        vm.setTo("a@x.tw")
        vm.addPicked(emptyList())
        assertEquals("a@x.tw", vm.state.value.to.text)
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
        assertEquals("a@x.tw", saving.state.value.to.text)
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
        assertEquals("", vm.state.value.to.text)

        repo.bodyError = null
        repo.bodies[5] = MailBody(null, "line1", emptyList(), emptyMap())
        vm.retryPrefill(labels)
        assertEquals(null, vm.state.value.loadError)
        assertEquals("教務處 <office@mail.ntust.edu.tw>", vm.state.value.to.text)
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
        val vm = SchoolMailComposeViewModel(handle(ComposeMode.NEW), repo, account, cache, slowReader, notices, pickerDispatcher, heldSends).also { it.sendHoldMs = 0 }
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
        // The send keeps the mail on the phone first, on the same dispatcher.
        pickerDispatcher.scheduler.advanceUntilIdle()
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

    /** Tapping a bubble to look at it takes it back into the text in its own place: nothing to save. */
    @Test
    fun `opening a recipient to edit it and leaving it again is not an edit`() {
        val to = listOf(MailAddress(null, "a@x.tw"), MailAddress(null, "b@x.tw"), MailAddress(null, "c@x.tw"))
        repo.add("草稿匣", mailSummary(9, subject = "draft", to = to))
        repo.bodies[9] = MailBody(null, "draft body", emptyList(), emptyMap())
        val vm = vm(ComposeMode.DRAFT, "草稿匣", 9)

        val opened = vm.updateRecipients(RecipientSlot.TO) { it.edit(it.tokens.first().id) }
        assertEquals("a@x.tw", opened.draft)
        assertEquals("a@x.tw, b@x.tw, c@x.tw", vm.state.value.to.text)
        assertFalse(vm.state.value.dirty)

        vm.updateRecipients(RecipientSlot.TO) { it.finishDraft() }
        assertEquals(listOf("a@x.tw", "b@x.tw", "c@x.tw"), vm.state.value.to.tokens.map { it.text })
        assertFalse(vm.state.value.dirty)
        vm.send()
        assertEquals(listOf("a@x.tw", "b@x.tw", "c@x.tw"), repo.sent.single().first.to.map { it.address })
    }

    /** Each bubble is read on its own, so one with its quote left open is refused alone. */
    @Test
    fun `a recipient left unfinished does not swallow the ones after it`() {
        val vm = vm(ComposeMode.NEW)
        vm.updateRecipients(RecipientSlot.TO) { it.typed(listOf("\"Chen"), "").typed(listOf("bob@x.tw"), "") }
        vm.send()
        assertEquals(ComposeError.InvalidRecipients(listOf("\"Chen")), vm.state.value.error)
    }

    // --- a held mail kept on the phone (tigerduck-app#555) ---------------------------

    private val owner = "b10000001@mail.ntust.edu.tw"

    private fun signedIn() = assertNull(runBlocking { account.signIn("b10000001", "pw") })

    /** The store as the app sees it after a restart: nothing of it in use. */
    private fun afterRestart() = HeldSendStore(heldRoot)

    /** The composer the app opens after a restart for kept mail [id], as MainActivity routes to it. */
    private fun reopened(id: String) = SchoolMailComposeViewModel(
        SavedStateHandle(mapOf("mode" to "NEW", "folder" to "", "uid" to -1L, "mailto" to "", "held" to id)),
        repo, account, cache, noPicks, notices, main.dispatcher, afterRestart(),
    ).also { it.prefill(labels) }

    private val keptFields = HeldSendStore.Fields(
        "NEW", "", -1, false, null, null, listOf("amy@mail.ntust.edu.tw"), emptyList(), emptyList(), "Hi", "Hello",
    )

    private fun runOutTheHold() {
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailComposeViewModel.SEND_HOLD_MS)
        main.dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `a held mail is kept on the phone, marked sending as it goes, and gone once it has gone out`() {
        signedIn()
        var whileSending: Long? = null
        repo.onSend = { whileSending = afterRestart().orphan(owner)?.sendingSinceMs }
        val vm = heldVm()

        vm.send()

        val kept = afterRestart().orphan(owner)
        assertEquals(listOf("amy@mail.ntust.edu.tw"), kept?.fields?.to)
        assertNull("held, its send not started", kept?.sendingSinceMs)
        runOutTheHold()
        assertNotNull("marked before it went to the server", whileSending)
        assertEquals(1, repo.sent.size)
        assertNull(afterRestart().orphan(owner))
    }

    @Test
    fun `undo deletes the kept mail`() {
        signedIn()
        val vm = heldVm()
        vm.send()

        vm.undoSend()

        assertNull(afterRestart().orphan(owner))
    }

    @Test
    fun `a mail held when the system ended the app opens again in the composer, with the file picked`() {
        signedIn()
        val vm = heldVm()
        vm.addAttachments(listOf(ComposeAttachment("p1", "notes.txt", "text/plain", 9, ComposeAttachment.Source.Local { "lab notes".byteInputStream() })))

        vm.send()
        val id = afterRestart().orphan(owner)!!.id

        // The system ends the app during the hold, and the composer opens again for it.
        val back = reopened(id).state.value

        assertEquals(listOf("amy@mail.ntust.edu.tw"), back.to.entries)
        assertEquals("Hi", back.subject)
        val file = back.attachments.single()
        assertEquals("notes.txt", file.fileName)
        assertEquals("lab notes", (file.source as ComposeAttachment.Source.Local).open().reader().readText())
        assertNull("nothing went out, so nothing to warn of", back.error)
        assertTrue("leaving asks about a draft", back.dirty)
        assertFalse(back.sending)
        assertTrue(repo.sent.isEmpty())
    }

    @Test
    fun `one whose send had started opens with the warning that it may have gone out, and sends nothing itself`() {
        signedIn()
        heldSends.save("m1", owner, keptFields, emptyList()) { true }
        heldSends.markSending("m1")

        val vm = reopened("m1")

        val error = vm.state.value.error as ComposeError.SendFailed
        assertTrue(error.error is MailError.SendUnconfirmed)
        assertFalse("raised, not just written", vm.state.value.errorAcknowledged)
        main.dispatcher.scheduler.advanceTimeBy(60_000)
        main.dispatcher.scheduler.runCurrent()
        assertTrue(repo.sent.isEmpty())
    }

    @Test
    fun `a send that failed for sure is no longer kept, and one that may have gone out still is`() {
        signedIn()
        val vm = vm(ComposeMode.NEW)
        vm.setTo("amy@mail.ntust.edu.tw")

        repo.sendError = MailError.Network()
        vm.send()
        assertNull(afterRestart().orphan(owner))

        repo.sendError = MailError.SendUnconfirmed()
        vm.send()
        assertNotNull(afterRestart().orphan(owner)?.sendingSinceMs)
    }

    @Test
    fun `a reopened mail saved as a draft, or discarded, is no longer kept`() {
        signedIn()
        heldSends.save("m1", owner, keptFields, emptyList()) { true }
        heldSends.save("m2", owner, keptFields, emptyList()) { true }

        reopened("m1").saveDraft()
        reopened("m2").discard()

        assertEquals(1, repo.drafts.size)
        assertNull(afterRestart().orphan(owner))
    }

    @Test
    fun `a kept mail that cannot be read opens an empty composer`() {
        signedIn()
        File(heldRoot, "m1").apply { mkdirs() }.resolve("record.json").writeText("{ not json")

        val state = reopened("m1").state.value

        assertEquals(emptyList<String>(), state.to.entries)
        assertNull(state.error)
        assertFalse(state.loading)
    }

    @Test
    fun `nothing is kept for a mail account no one is signed in to`() {
        val vm = heldVm()

        vm.send()

        assertTrue(heldRoot.list().orEmpty().isEmpty())
    }

    @Test
    fun `a composer the system rebuilt from saved state opens afresh, leaving the kept mail to the one the app opens`() {
        signedIn()
        heldSends.save("m1", owner, keptFields, emptyList()) { true }
        // Its saved state, as the system hands it back after ending the app.
        val saved = SavedStateHandle(mapOf("mode" to "NEW", "folder" to "", "uid" to -1L, "held" to "m1", "composerOpened" to true))

        val rebuilt = SchoolMailComposeViewModel(saved, repo, account, cache, noPicks, notices, main.dispatcher, afterRestart())
            .also { it.prefill(labels) }

        assertEquals(emptyList<String>(), rebuilt.state.value.to.entries)
        assertFalse("nothing to save, so it gives way at once", rebuilt.state.value.dirty)
        assertEquals("m1", afterRestart().orphan(owner)?.id)
    }

    @Test
    fun `a kept mail read as another student signs in is not shown`() {
        signedIn()
        heldSends.save("m1", owner, keptFields, emptyList()) { true }
        val io = HeldDispatcher()
        val vm = SchoolMailComposeViewModel(
            SavedStateHandle(mapOf("mode" to "NEW", "folder" to "", "uid" to -1L, "mailto" to "", "held" to "m1")),
            repo, account, cache, noPicks, notices, io, afterRestart(),
        ).also { it.prefill(labels) }

        account.signOut()
        io.drain()

        assertEquals(emptyList<String>(), vm.state.value.to.entries)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `a composer closed while it reads a kept mail leaves it for the next one to open`() {
        signedIn()
        heldSends.save("m1", owner, keptFields, emptyList()) { true }
        val store = afterRestart()
        val io = HeldDispatcher()
        val owners = ViewModelStore()
        val vm = ViewModelProvider(owners, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SchoolMailComposeViewModel(
                SavedStateHandle(mapOf("mode" to "NEW", "folder" to "", "uid" to -1L, "mailto" to "", "held" to "m1")),
                repo, account, cache, noPicks, notices, io, store,
            ) as T
        })[SchoolMailComposeViewModel::class.java]
        vm.prefill(labels)

        owners.clear()
        io.drain()

        assertEquals("m1", store.orphan(owner)?.id)
    }

    @Test
    fun `a failed send's mail is forgotten before the form can be sent again`() {
        signedIn()
        val io = EitherEndDispatcher()
        val vm = SchoolMailComposeViewModel(handle(ComposeMode.NEW), repo, account, cache, noPicks, notices, io, heldSends)
            .also { it.sendHoldMs = 0; it.prefill(labels); it.setTo("amy@mail.ntust.edu.tw") }
        repo.sendError = MailError.Network()
        vm.send()
        // Until the form is usable again; whatever IO of the failed send is left then waits.
        while (vm.state.value.sending) io.runOldest()

        // Sent again at once, and held this time. Its keeping runs ahead of what the first attempt
        // left, as a pool of threads may run them; nothing of the first attempt may take it.
        repo.sendError = null
        vm.sendHoldMs = SchoolMailComposeViewModel.SEND_HOLD_MS
        vm.send()
        io.runNewest()
        io.drain()

        assertNotNull(afterRestart().orphan(owner))
    }

    @Test
    fun `a reopened mail names the files it could not keep, and shows the rest`() {
        signedIn()
        val picked = HeldSendStore.Keep("p1", "notes.txt", "text/plain", 9, open = { "lab notes".byteInputStream() })
        val carried = HeldSendStore.Keep("orig-2", "slides.pdf", "application/pdf", 900, original = HeldSendStore.Original("INBOX", 42, "2"))
        heldSends.save("m1", owner, keptFields, listOf(picked, carried)) { true }
        afterRestart().orphan(owner)!!.attachments[0].copy!!.delete()

        val state = reopened("m1").state.value

        assertEquals(listOf("slides.pdf"), state.attachments.map { it.fileName })
        assertEquals(listOf("notes.txt"), state.lostAttachments)
        assertNull("not a failure of the form's", state.error)
    }
}

/** Holds what it is handed for a test to run oldest or newest first, as a pool of threads may. */
private class EitherEndDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
    private val queued = ArrayDeque<Runnable>()

    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
        queued.addLast(block)
    }

    fun runOldest() = queued.removeFirst().run()

    fun runNewest() = queued.removeLast().run()

    fun drain() {
        while (queued.isNotEmpty()) runOldest()
    }
}