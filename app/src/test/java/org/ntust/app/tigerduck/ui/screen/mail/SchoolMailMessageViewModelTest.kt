package org.ntust.app.tigerduck.ui.screen.mail

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.mail.RecordingNotifier
import org.ntust.app.tigerduck.mail.RecordingScheduler
import org.ntust.app.tigerduck.mail.mailSummary
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailAttachment
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.warning.MailWarning
import org.ntust.app.tigerduck.ui.screen.mail.SchoolMailMessageViewModel.AttachmentAction
import org.ntust.app.tigerduck.ui.screen.mail.SchoolMailMessageViewModel.Content
import org.ntust.app.tigerduck.ui.screen.mail.SchoolMailMessageViewModel.PendingAttachment
import org.ntust.app.tigerduck.ui.screen.mail.SchoolMailMessageViewModel.ViewMode
import java.io.ByteArrayOutputStream
import java.net.IDN

class SchoolMailMessageViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val repo = FakeSchoolMailRepository()
    private val notifier = RecordingNotifier()
    private lateinit var account: MailAccount
    private lateinit var cache: MailCache

    @Before
    fun setUp() {
        cache = MailCache(tmp.newFolder("cache"))
        account = MailAccount(InMemoryCredentialStore(), InMemoryMailStateStore(), FakeMailServer().factory(), cache,
            FakeDemoGate(), RecordingScheduler(), RecordingNotifier())
    }

    // The same TestDispatcher backs both Dispatchers.Main and the injected @IoDispatcher, so a
    // withContext(io) hop stays synchronous under the test the way every other action already is.
    private fun vm(folder: String = "INBOX", uid: Long = 5) =
        SchoolMailMessageViewModel(SavedStateHandle(mapOf("folder" to folder, "uid" to uid)), repo, account, notifier, cache, main.dispatcher)

    private fun ready(vm: SchoolMailMessageViewModel) = vm.state.value.content as Content.Ready

    @Test
    fun `an unread HTML mail opens formatted with warnings, is marked seen and its notification cleared`() {
        repo.add("INBOX", mailSummary(5, from = MailAddress(null, "x@evil.example")))
        repo.bodies[5] = MailBody("""<p>hi <img src="https://t.example/p.gif"></p>""", null, emptyList(), emptyMap())
        val vm = vm()
        vm.load()
        assertEquals(ViewMode.FORMATTED, vm.state.value.mode)
        assertEquals(1, ready(vm).html!!.blockedRemoteImages)
        assertEquals("hi", ready(vm).plain)
        assertTrue(ready(vm).warnings.any { it is MailWarning.ExternalSender })
        assertEquals(listOf(5L to true), repo.seenCalls)
        assertEquals(listOf(5L), notifier.cancelledUids)

        vm.loadRemoteImages()
        assertTrue(vm.state.value.remoteImagesAllowed)
        assertEquals(0, ready(vm).html!!.blockedRemoteImages)
    }

    @Test
    fun `a plain-text mail opens as text and a read mail is not marked again`() {
        repo.add("INBOX", mailSummary(5, seen = true))
        repo.bodies[5] = MailBody(null, "hello", emptyList(), emptyMap())
        val vm = vm()
        vm.load()
        assertEquals(ViewMode.PLAIN, vm.state.value.mode)
        assertNull(ready(vm).html)
        assertTrue(repo.seenCalls.isEmpty())
    }

    @Test
    fun `source loads at once when small and asks first when large`() {
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody(null, "hello", emptyList(), emptyMap())
        val vm = vm()
        vm.load()
        repo.size = 6L * 1024 * 1024
        vm.selectMode(ViewMode.SOURCE)
        assertEquals(6L * 1024 * 1024, vm.state.value.confirmLargeSource)
        assertNull(vm.state.value.source)
        vm.confirmLargeSource(true)
        assertEquals(repo.raw, vm.state.value.source)

        val small = vm()
        repo.size = 1_000
        small.load()
        small.selectMode(ViewMode.SOURCE)
        assertEquals(repo.raw, small.state.value.source)
    }

    @Test
    fun `declining a large source goes back to the readable view`() {
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody(null, "hello", emptyList(), emptyMap())
        repo.size = 6L * 1024 * 1024
        val vm = vm()
        vm.load()
        vm.selectMode(ViewMode.SOURCE)
        vm.confirmLargeSource(false)
        assertEquals(ViewMode.PLAIN, vm.state.value.mode)
        assertNull(vm.state.value.source)
    }

    @Test
    fun `a failed size check still asks before loading a large source instead of skipping the confirmation`() {
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody(null, "hello", emptyList(), emptyMap())
        repo.messageSizeError = MailError.Network()
        val vm = vm()
        vm.load()
        vm.selectMode(ViewMode.SOURCE)
        assertEquals(SchoolMailMessageViewModel.LARGE_SOURCE_BYTES, vm.state.value.confirmLargeSource)
        assertNull(vm.state.value.source)
    }

    @Test
    fun `a body that cannot be parsed falls back to the source`() {
        repo.add("INBOX", mailSummary(5))
        repo.bodyError = MailError.Protocol("bad mime")
        val vm = vm()
        vm.load()
        assertTrue(vm.state.value.parseFailed)
        assertEquals(ViewMode.SOURCE, vm.state.value.mode)
        assertEquals(repo.raw, vm.state.value.source)
    }

    // --- load() idempotency ---------------------------------------------------------------

    @Test
    fun `re-entering after loading remote images keeps them loaded instead of resetting`() {
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody("""<p><img src="https://t.example/p.gif"></p>""", null, emptyList(), emptyMap())
        val vm = vm()
        vm.load()
        vm.loadRemoteImages()
        assertTrue(vm.state.value.remoteImagesAllowed)
        assertEquals(0, ready(vm).html!!.blockedRemoteImages)

        // e.g. LaunchedEffect(Unit) { load() } rerunning after Reply-and-back or a config change.
        vm.load()
        assertTrue(vm.state.value.remoteImagesAllowed)
        assertEquals(0, ready(vm).html!!.blockedRemoteImages)
        assertEquals(ViewMode.FORMATTED, vm.state.value.mode)
        // Marked seen once, not flashed through Loading and marked again.
        assertEquals(listOf(5L to true), repo.seenCalls)
    }

    @Test
    fun `a retry after a failed load still reloads`() {
        repo.add("INBOX", mailSummary(5))
        repo.bodyError = MailError.Network()
        val vm = vm()
        vm.load()
        assertTrue(vm.state.value.content is Content.Failed)

        repo.bodyError = null
        repo.bodies[5] = MailBody(null, "hello", emptyList(), emptyMap())
        vm.load()
        assertTrue(vm.state.value.content is Content.Ready)
    }

    @Test
    fun `delete closes the mail, and in the trash it asks first`() {
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody(null, "x", emptyList(), emptyMap())
        val inbox = vm()
        inbox.load()
        inbox.delete()
        assertEquals(listOf("INBOX" to 5L), repo.deleted)
        assertTrue(inbox.state.value.closed)

        repo.add("回收筒", mailSummary(9))
        repo.bodies[9] = MailBody(null, "x", emptyList(), emptyMap())
        val trash = vm("回收筒", 9)
        trash.load()
        trash.delete()
        assertTrue(trash.state.value.confirmDeleteForever)
        assertEquals(1, repo.deleted.size)
        trash.confirmDeleteForever(true)
        assertEquals("回收筒" to 9L, repo.deleted.last())
        assertTrue(trash.state.value.closed)
    }

    @Test
    fun `move targets skip the current folder and moving closes the mail`() {
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody(null, "x", emptyList(), emptyMap())
        val vm = vm()
        vm.load()
        assertEquals(listOf("寄件備份匣", "草稿匣", "廣告信匣", "回收筒", "Moodle 課程討論區"), vm.moveTargets())
        vm.moveTo("回收筒")
        assertEquals(Triple("INBOX", 5L, "回收筒"), repo.moved.single())
        assertTrue(vm.state.value.closed)
    }

    // --- attachments: open ------------------------------------------------------------------

    @Test
    fun `risky attachments ask before opening, safe ones open at once`() {
        val risky = MailAttachment("2", "invoice.pdf.exe", "application/octet-stream", 10, null)
        val safe = MailAttachment("3", "notes.txt", "text/plain", 5, null)
        repo.add("INBOX", mailSummary(5, hasAttachments = true))
        repo.bodies[5] = MailBody(null, "see attached", listOf(risky, safe), emptyMap())
        val vm = vm()
        vm.load()

        vm.requestOpen(risky)
        assertEquals(PendingAttachment(risky, AttachmentAction.OPEN), vm.state.value.confirmAttachment)
        assertNull(vm.state.value.openRequest)
        vm.confirmAttachment(true)
        val file = vm.state.value.openRequest!!.file
        assertEquals("invoice.pdf.exe", file.name)
        assertEquals("attachment 2", file.readText())
        assertTrue(file.canonicalPath.startsWith(cache.attachmentsDir.canonicalPath))
        vm.consumeOpenRequest()

        vm.requestOpen(safe)
        assertNull(vm.state.value.confirmAttachment)
        assertEquals("notes.txt", vm.state.value.openRequest!!.file.name)
    }

    @Test
    fun `a failed download deletes the partial cache slot instead of leaving it behind`() {
        val att = MailAttachment("2", "a.pdf", "application/pdf", 10, null)
        repo.add("INBOX", mailSummary(5, hasAttachments = true))
        repo.bodies[5] = MailBody(null, "x", listOf(att), emptyMap())
        repo.writeAttachmentError = MailError.Network()
        val vm = vm()
        vm.load()
        vm.requestOpen(att)
        assertTrue(vm.state.value.actionError is MailError.Network)
        assertNull(vm.state.value.openRequest)
        assertTrue(vm.state.value.downloading.isEmpty())
        assertTrue(cache.attachmentsDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a second open request for an attachment already downloading is ignored`() {
        val att = MailAttachment("2", "a.pdf", "application/pdf", 10, null)
        repo.add("INBOX", mailSummary(5, hasAttachments = true))
        repo.bodies[5] = MailBody(null, "x", listOf(att), emptyMap())
        val vm = vm()
        vm.load()

        var writes = 0
        repo.onWriteAttachment = {
            writes++
            if (writes == 1) vm.requestOpen(att)
        }
        vm.requestOpen(att)
        assertEquals(1, writes)
        assertTrue(vm.state.value.downloading.isEmpty())
    }

    // --- attachments: save (spec lines 416/653 -- confirm before opening OR saving) ---------

    @Test
    fun `saving a risky attachment asks first, a safe one goes straight to the document picker`() {
        val risky = MailAttachment("2", "invoice.pdf.exe", "application/octet-stream", 10, null)
        val safe = MailAttachment("3", "notes.txt", "text/plain", 5, null)
        repo.add("INBOX", mailSummary(5, hasAttachments = true))
        repo.bodies[5] = MailBody(null, "see attached", listOf(risky, safe), emptyMap())
        val vm = vm()
        vm.load()

        vm.requestSave(risky)
        assertEquals(PendingAttachment(risky, AttachmentAction.SAVE), vm.state.value.confirmAttachment)
        assertNull(vm.state.value.saveRequest)
        vm.confirmAttachment(true)
        assertEquals(risky, vm.state.value.saveRequest)
        vm.consumeSaveRequest()
        assertNull(vm.state.value.saveRequest)

        vm.requestSave(safe)
        assertNull(vm.state.value.confirmAttachment)
        assertEquals(safe, vm.state.value.saveRequest)
    }

    @Test
    fun `declining the confirmation neither opens nor requests saving`() {
        val risky = MailAttachment("2", "invoice.pdf.exe", "application/octet-stream", 10, null)
        repo.add("INBOX", mailSummary(5, hasAttachments = true))
        repo.bodies[5] = MailBody(null, "x", listOf(risky), emptyMap())
        val vm = vm()
        vm.load()
        vm.requestSave(risky)
        vm.confirmAttachment(false)
        assertNull(vm.state.value.confirmAttachment)
        assertNull(vm.state.value.saveRequest)
        assertNull(vm.state.value.openRequest)
    }

    @Test
    fun `saving writes the attachment to the chosen document`() {
        val att = MailAttachment("2", "a.pdf", "application/pdf", 10, null)
        repo.add("INBOX", mailSummary(5, hasAttachments = true))
        repo.bodies[5] = MailBody(null, "x", listOf(att), emptyMap())
        val vm = vm()
        vm.load()
        val out = ByteArrayOutputStream()
        vm.saveAttachment(att, open = { out })
        assertEquals("attachment 2", out.toString())
        assertEquals(1, vm.state.value.savedCount)
    }

    @Test
    fun `a failed save runs the cleanup callback instead of leaving a partial document`() {
        val att = MailAttachment("2", "a.pdf", "application/pdf", 10, null)
        repo.add("INBOX", mailSummary(5, hasAttachments = true))
        repo.bodies[5] = MailBody(null, "x", listOf(att), emptyMap())
        repo.writeAttachmentError = MailError.Network()
        val vm = vm()
        vm.load()
        val out = ByteArrayOutputStream()
        var cleanedUp = false
        vm.saveAttachment(att, open = { out }, onFailure = { cleanedUp = true })
        assertTrue(cleanedUp)
        assertTrue(vm.state.value.actionError is MailError.Network)
        assertEquals(0, vm.state.value.savedCount)
        assertTrue(vm.state.value.downloading.isEmpty())
    }

    // --- link verdict: href normalization -----------------------------------------------

    @Test
    fun `link verdict matches the sanitized link's text even when the tapped href only differs by WebView normalization`() {
        // The sanitized HTML kept the href exactly as the mail wrote it: mixed case host, no
        // trailing slash for the empty path. WebView, on tap, hands back its own normalized
        // form (lowercase scheme+host, trailing "/"). Without normalized comparison the link's
        // text would never be found, `checkLink` would see an empty text, and the display-name
        // mismatch this link should raise would silently vanish.
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody(
            """<p><a href="HTTP://Evil.EXAMPLE">bank.example.com</a></p>""",
            null,
            emptyList(),
            emptyMap(),
        )
        val vm = vm()
        vm.load()

        val verdict = vm.linkVerdict("http://evil.example/")
        assertTrue(verdict.mismatch)
        assertEquals("bank.example.com", verdict.shownHost)
        assertEquals("evil.example", verdict.host)
    }

    @Test
    fun `link verdict also matches through percent-encoding and a default port`() {
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody(
            """<p><a href="https://example.com:443/caf%C3%A9">bank.example.com</a></p>""",
            null,
            emptyList(),
            emptyMap(),
        )
        val vm = vm()
        vm.load()

        // WebView drops the explicit default port and may leave the path decoded.
        val verdict = vm.linkVerdict("  https://example.com/café  ")
        assertTrue(verdict.mismatch)
        assertEquals("bank.example.com", verdict.shownHost)
    }

    @Test
    fun `link verdict matches an IDN host against the punycode form WebView hands back`() {
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody(
            """<p><a href="https://münchen.example">bank.example.com</a></p>""",
            null,
            emptyList(),
            emptyMap(),
        )
        val vm = vm()
        vm.load()

        val punycode = IDN.toASCII("münchen.example")
        val verdict = vm.linkVerdict("https://$punycode/")
        assertTrue(verdict.mismatch)
        assertEquals("bank.example.com", verdict.shownHost)
    }

    @Test
    fun `a decoy link sharing the same href does not hide a mismatch another link with it raises`() {
        // spec A.4.2: the first <a> with this href is innocuous (text matches where it goes);
        // the second is a decoy claiming to be ntust.edu.tw while sharing the same target.
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody(
            """<p><a href="http://evil.example">evil.example</a> <a href="http://evil.example">ntust.edu.tw</a></p>""",
            null,
            emptyList(),
            emptyMap(),
        )
        val vm = vm()
        vm.load()

        val verdict = vm.linkVerdict("http://evil.example/")
        assertTrue(verdict.mismatch)
        assertEquals("ntust.edu.tw", verdict.shownHost)
    }

    // --- FolderChanged on move/delete ----------------------------------------------------

    @Test
    fun `a folder-changed error while moving surfaces as an action error instead of closing the mail`() {
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody(null, "x", emptyList(), emptyMap())
        repo.moveError = MailError.FolderChanged()
        val vm = vm()
        vm.load()
        vm.moveTo("回收筒")
        assertTrue(vm.state.value.actionError is MailError.FolderChanged)
        assertFalse(vm.state.value.closed)
    }

    @Test
    fun `a folder-changed error while deleting surfaces as an action error instead of closing the mail`() {
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody(null, "x", emptyList(), emptyMap())
        repo.deleteError = MailError.FolderChanged()
        val vm = vm()
        vm.load()
        vm.delete()
        assertTrue(vm.state.value.actionError is MailError.FolderChanged)
        assertFalse(vm.state.value.closed)
    }

    // --- safeFileName ----------------------------------------------------------------------

    @Test
    fun `safeFileName never lets a name traverse out of the attachments directory`() {
        assertEquals("attachment", SchoolMailMessageViewModel.safeFileName(".."))
        assertEquals("attachment", SchoolMailMessageViewModel.safeFileName("."))
        val traversal = SchoolMailMessageViewModel.safeFileName("../../x")
        assertFalse(traversal.contains('/'))
        assertFalse(traversal.contains('\\'))
        assertTrue(traversal != "." && traversal != "..")
        assertEquals("a_b_c", SchoolMailMessageViewModel.safeFileName("a/b\\c"))
    }

    @Test
    fun `safeFileName truncates a long name but keeps its extension and never splits a surrogate pair`() {
        val longName = "a".repeat(200) + ".pdf"
        val result = SchoolMailMessageViewModel.safeFileName(longName)
        assertTrue(result.length <= 120)
        assertTrue(result.endsWith(".pdf"))

        // U+1F600 (an emoji outside the BMP) is a surrogate pair in UTF-16. The leading "x"
        // shifts every pair's boundary by one code unit relative to a bare run of pairs, so the
        // stem's truncation cut (120 - ".png".length = 116 code units in) lands exactly between
        // a pair's two halves -- precisely the case a naive take(116) would get wrong.
        val emojiName = "x" + "😀".repeat(70) + ".png"
        val truncatedEmoji = SchoolMailMessageViewModel.safeFileName(emojiName)
        assertTrue(truncatedEmoji.endsWith(".png"))
        val stem = truncatedEmoji.removeSuffix(".png")
        // A lone high surrogate at the very end (its low half was cut off) means a pair got
        // split; ending on a low surrogate is fine -- that IS a pair's completed second half.
        assertFalse(stem.isNotEmpty() && Character.isHighSurrogate(stem.last()))
    }
}
