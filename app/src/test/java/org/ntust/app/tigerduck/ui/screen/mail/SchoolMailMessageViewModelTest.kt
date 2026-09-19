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
import org.ntust.app.tigerduck.mail.imap.ResolvedFolders
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.mailSummary
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailAttachment
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.testApplicationScope
import org.ntust.app.tigerduck.mail.warning.MailWarning
import org.ntust.app.tigerduck.ui.screen.mail.SchoolMailMessageViewModel.AttachmentAction
import org.ntust.app.tigerduck.ui.screen.mail.SchoolMailMessageViewModel.Content
import org.ntust.app.tigerduck.ui.screen.mail.SchoolMailMessageViewModel.PendingAttachment
import org.ntust.app.tigerduck.ui.screen.mail.SchoolMailMessageViewModel.ViewMode
import java.io.ByteArrayOutputStream
import kotlin.system.measureTimeMillis
import org.ntust.app.tigerduck.mail.schoolMailSite

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
            FakeDemoGate(), schoolMailSite(), RecordingScheduler(), RecordingNotifier(), testApplicationScope())
    }

    // The same TestDispatcher backs both Dispatchers.Main and the injected @IoDispatcher, so a
    // withContext(io) hop stays synchronous under the test the way every other action already is.
    private fun vm(folder: String = "INBOX", uid: Long = 5) =
        SchoolMailMessageViewModel(
            SavedStateHandle(mapOf("folder" to folder, "uid" to uid)), repo, account, notifier, cache, schoolMailSite(), main.dispatcher,
        )

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
        assertEquals(listOf(Triple("INBOX", 5L, true)), repo.seenCalls)
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
    fun `the source loads straight away, however large the mail, and is asked for only once`() {
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody(null, "hello", emptyList(), emptyMap())
        val vm = vm()
        vm.load()
        vm.selectMode(ViewMode.SOURCE)
        assertEquals(repo.raw, vm.state.value.source)
        assertEquals(1, repo.rawSourceCalls)

        // Switching away and back reuses what is already in state; across visits the repository's
        // own cache covers it (see MailRepositoryTest). Nothing asks the user first any more.
        vm.selectMode(ViewMode.PLAIN)
        vm.selectMode(ViewMode.SOURCE)
        assertEquals(1, repo.rawSourceCalls)
    }

    @Test
    fun `a source that fails to load surfaces as an action error rather than a stuck spinner`() {
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody(null, "hello", emptyList(), emptyMap())
        repo.rawSourceError = MailError.Network()
        val vm = vm()
        vm.load()
        vm.selectMode(ViewMode.SOURCE)
        assertNull(vm.state.value.source)
        assertFalse(vm.state.value.sourceLoading)
        assertTrue(vm.state.value.actionError is MailError.Network)
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

    // --- the header-known-before-body-loads state -------------------------------------------

    @Test
    fun `the header's summary is available as LoadingBody while the body is still being fetched`() {
        val summary = mailSummary(5, from = MailAddress("Someone", "someone@ntust.edu.tw"))
        repo.add("INBOX", summary)
        repo.bodies[5] = MailBody(null, "hello", emptyList(), emptyMap())
        val vm = vm()
        var seenDuringBody: Content.LoadingBody? = null
        repo.onBody = { seenDuringBody = vm.state.value.content as? Content.LoadingBody }
        vm.load()
        assertEquals(summary, seenDuringBody?.summary)
        // ...and once the body arrives, it moves on to Ready rather than staying stuck there.
        assertTrue(vm.state.value.content is Content.Ready)
    }

    @Test
    fun `a body that fails to parse still passes through LoadingBody on its way to the source fallback`() {
        val summary = mailSummary(5)
        repo.add("INBOX", summary)
        repo.bodyError = MailError.Protocol("bad mime")
        val vm = vm()
        var seenDuringBody: Content.LoadingBody? = null
        repo.onBody = { seenDuringBody = vm.state.value.content as? Content.LoadingBody }
        vm.load()
        assertEquals(summary, seenDuringBody?.summary)
        assertTrue(vm.state.value.content is Content.Ready)
        assertTrue(vm.state.value.parseFailed)
    }

    @Test
    fun `a message that is gone lands in Failed instead of leaving a header with a spinner forever`() {
        // No repo.add(...): summary() returns null for this uid.
        val vm = vm()
        vm.load()
        assertTrue(vm.state.value.content is Content.Failed)
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
        assertEquals(listOf(Triple("INBOX", 5L, true)), repo.seenCalls)
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
    fun `delete asks first everywhere -- an ordinary confirmation outside the trash, a permanent one inside it`() {
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody(null, "x", emptyList(), emptyMap())
        val inbox = vm()
        inbox.load()
        inbox.delete()
        assertTrue(inbox.state.value.confirmDelete)
        assertFalse(inbox.state.value.confirmDeleteForever)
        assertTrue(repo.deleted.isEmpty())
        assertFalse(inbox.state.value.closed)
        inbox.confirmDelete(true)
        assertEquals(listOf("INBOX" to 5L), repo.deleted)
        assertFalse(inbox.state.value.confirmDelete)
        assertTrue(inbox.state.value.closed)

        repo.add("回收筒", mailSummary(9))
        repo.bodies[9] = MailBody(null, "x", emptyList(), emptyMap())
        val trash = vm("回收筒", 9)
        trash.load()
        trash.delete()
        assertTrue(trash.state.value.confirmDeleteForever)
        assertFalse(trash.state.value.confirmDelete)
        assertEquals(1, repo.deleted.size)
        trash.confirmDeleteForever(true)
        assertEquals("回收筒" to 9L, repo.deleted.last())
        assertTrue(trash.state.value.closed)
    }

    /**
     * An account whose trash folder is missing and could not be created still asks before it
     * destroys anything: the repository reports the delete as permanent, and the screen shows
     * the permanent-delete confirmation rather than deleting silently.
     */
    @Test
    fun `an account with no trash folder still gets the permanent-delete confirmation`() {
        repo.resolved = ResolvedFolders(
            SpecialFolder.entries.filter { it != SpecialFolder.TRASH }.associateWith { it.decodedName }, emptyList(),
        )
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody(null, "x", emptyList(), emptyMap())
        val vm = vm()
        vm.load()
        vm.delete()
        assertTrue(vm.state.value.confirmDeleteForever)
        assertFalse(vm.state.value.confirmDelete)
        assertTrue(repo.deleted.isEmpty())
        vm.confirmDeleteForever(true)
        assertEquals(listOf("INBOX" to 5L), repo.deleted)
    }

    @Test
    fun `declining the ordinary delete confirmation leaves the mail open and deletes nothing`() {
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody(null, "x", emptyList(), emptyMap())
        val vm = vm()
        vm.load()
        vm.delete()
        assertTrue(vm.state.value.confirmDelete)
        vm.confirmDelete(false)
        assertFalse(vm.state.value.confirmDelete)
        assertTrue(repo.deleted.isEmpty())
        assertFalse(vm.state.value.closed)
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
    fun `a write that fails after a stream was opened runs the cleanup callback`() {
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

    @Test
    fun `open() returning null never runs the cleanup callback`() {
        // ACTION_CREATE_DOCUMENT can hand back a Uri the user picked to overwrite an existing
        // file; if the provider then fails to open it (open() returns null here), nothing was
        // ever touched, so deleting would destroy a file the user never asked to lose.
        val att = MailAttachment("2", "a.pdf", "application/pdf", 10, null)
        repo.add("INBOX", mailSummary(5, hasAttachments = true))
        repo.bodies[5] = MailBody(null, "x", listOf(att), emptyMap())
        val vm = vm()
        vm.load()
        var cleanedUp = false
        vm.saveAttachment(att, open = { null }, onFailure = { cleanedUp = true })
        assertFalse(cleanedUp)
        assertTrue(vm.state.value.actionError is MailError.Protocol)
        assertEquals(0, vm.state.value.savedCount)
    }

    @Test
    fun `open() throwing never runs the cleanup callback`() {
        val att = MailAttachment("2", "a.pdf", "application/pdf", 10, null)
        repo.add("INBOX", mailSummary(5, hasAttachments = true))
        repo.bodies[5] = MailBody(null, "x", listOf(att), emptyMap())
        val vm = vm()
        vm.load()
        var cleanedUp = false
        vm.saveAttachment(att, open = { throw MailError.Protocol("provider unavailable") }, onFailure = { cleanedUp = true })
        assertFalse(cleanedUp)
        assertTrue(vm.state.value.actionError is MailError.Protocol)
        assertEquals(0, vm.state.value.savedCount)
    }

    // --- link target: addressed by index into the rewritten document's links -----------------

    @Test
    fun `linkTarget addresses a link by index, reading its own text and href`() {
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody(
            """<p><a href="HTTP://Evil.EXAMPLE">bank.example.com</a></p>""",
            null,
            emptyList(),
            emptyMap(),
        )
        val vm = vm()
        vm.load()

        val verdict = vm.linkTarget(0)!!.verdict
        assertTrue(verdict.mismatch)
        assertEquals("bank.example.com", verdict.shownHost)
        assertEquals("evil.example", verdict.host)
    }

    @Test
    fun `a decoy link sharing an href with an innocuous one is flagged only by its own index`() {
        // spec A.4.2: link 0 is innocuous (text matches where it goes); link 1 shares the same
        // href but claims to be ntust.edu.tw. Addressing by index means link 1's mismatch is
        // never hidden behind link 0's clean verdict, and link 0 is never wrongly flagged either
        // -- there is no href-based lookup left that a decoy could exploit.
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody(
            """<p><a href="http://evil.example">evil.example</a> <a href="http://evil.example">ntust.edu.tw</a></p>""",
            null,
            emptyList(),
            emptyMap(),
        )
        val vm = vm()
        vm.load()

        assertFalse(vm.linkTarget(0)!!.verdict.mismatch)
        assertTrue(vm.linkTarget(1)!!.verdict.mismatch)
        assertEquals("ntust.edu.tw", vm.linkTarget(1)!!.verdict.shownHost)
        assertEquals("evil.example", vm.linkTarget(1)!!.verdict.host)
    }

    @Test
    fun `duplicate hrefs with different texts each map to their own index`() {
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody(
            """<p><a href="https://ntust.edu.tw">first</a> <a href="https://ntust.edu.tw">second</a></p>""",
            null,
            emptyList(),
            emptyMap(),
        )
        val vm = vm()
        vm.load()

        assertFalse(vm.linkTarget(0)!!.verdict.mismatch)
        assertFalse(vm.linkTarget(1)!!.verdict.mismatch)
        assertEquals("ntust.edu.tw", vm.linkTarget(0)!!.verdict.host)
        assertEquals("ntust.edu.tw", vm.linkTarget(1)!!.verdict.host)
    }

    @Test
    fun `linkTarget on an out-of-range index is null instead of crashing`() {
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody("""<p><a href="https://ntust.edu.tw">ntust.edu.tw</a></p>""", null, emptyList(), emptyMap())
        val vm = vm()
        vm.load()

        assertNull(vm.linkTarget(5))
    }

    // --- the link list is the rewritten document's own ---------------------------------------

    @Test
    fun `an anchor the parser clones out of a dropped element is judged by its own text and href`() {
        // The Cleaner drops <marquee> but keeps the <p> inside it -- a tree the HTML parser never
        // builds -- so parsing the sanitized markup again clones the <a> around "ntust.edu.tw": the
        // WebView shows four anchors where the sanitizer counted two. Tapping the visible
        // "ntust.edu.tw" must judge that anchor (a.example), not the sanitizer's second link.
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody(
            """<p><a href="https://a.example">A<marquee><p>ntust.edu.tw</p></marquee>C</a></p><a href="https://evil.example"></a>""",
            null,
            emptyList(),
            emptyMap(),
        )
        val vm = vm()
        vm.load()

        assertEquals(2, ready(vm).html!!.links.size)
        assertEquals(4, ready(vm).document!!.links.size)
        val tapped = vm.linkTarget(1)!!.verdict
        assertEquals("a.example", tapped.host)
        assertEquals("ntust.edu.tw", tapped.shownHost)
        assertTrue(tapped.mismatch)
        assertEquals("evil.example", vm.linkTarget(3)!!.verdict.host)
    }

    // --- http(s) hrefs are judged, shown and opened the way a browser parses them -------------

    private fun loadLink(href: String, text: String): SchoolMailMessageViewModel {
        repo.add("INBOX", mailSummary(5))
        repo.bodies[5] = MailBody("""<p><a href="$href">$text</a></p>""", null, emptyList(), emptyMap())
        return vm().also { it.load() }
    }

    @Test
    fun `a backslash before an at sign ends the host the way a browser reads it`() {
        val target = loadLink("""https://evil.example\@ntust.edu.tw""", "ntust.edu.tw").linkTarget(0)!!
        assertEquals("evil.example", target.verdict.host)
        assertTrue(target.verdict.mismatch)
        // The string shown and opened is the one judged.
        assertEquals("https://evil.example/@ntust.edu.tw", target.href)
        assertTrue(target.canOpen)
    }

    @Test
    fun `a backslash before a school suffix does not pass the suffix check`() {
        val target = loadLink("""https://evil.example\.ntust.edu.tw""", "ntust.edu.tw").linkTarget(0)!!
        assertEquals("evil.example", target.verdict.host)
        assertTrue(target.verdict.mismatch)
        assertEquals("https://evil.example/.ntust.edu.tw", target.href)
        assertTrue(target.canOpen)
    }

    @Test
    fun `the last at sign ends the userinfo the way a browser reads it`() {
        val target = loadLink("https://x@a.ntust.edu.tw:pw@evil.example", "ntust.edu.tw").linkTarget(0)!!
        assertEquals("evil.example", target.verdict.host)
        assertTrue(target.verdict.mismatch)
        assertEquals("https://x%40a.ntust.edu.tw:pw@evil.example/", target.href)
        assertTrue(target.canOpen)
    }

    @Test
    fun `an http href a browser-like parser rejects is shown without an Open action`() {
        val target = loadLink("https://ntust.edu.tw:99999/", "ntust.edu.tw").linkTarget(0)!!
        assertFalse(target.canOpen)
        assertEquals("https://ntust.edu.tw:99999/", target.href)
        // It claims no host: the dialog falls back to showing the href itself.
        assertEquals("", target.verdict.host)
    }

    @Test
    fun `a mailto href is judged, shown and opened as written`() {
        val target = loadLink("mailto:Someone@Evil.example", "someone@ntust.edu.tw").linkTarget(0)!!
        assertEquals("mailto:Someone@Evil.example", target.href)
        assertEquals("Someone@Evil.example", target.verdict.host)
        assertTrue(target.verdict.mismatch)
        assertTrue(target.canOpen)
    }

    // --- normalizedHref: linear time (this stays only for the remote-image allowlist) -------

    @Test
    fun `normalizedHref stays linear-time on a line separator in a long fragment`() {
        // U+2028 is a line terminator to java.util.regex: without DOT_MATCHES_ALL `.` stops at it
        // and the engine backtracks through every optional group -- quadratic in the host length.
        // A plain space would not exercise that at all.
        val hostile = "https://" + "a".repeat(32_000) + "#\u2028x"
        val elapsed = measureTimeMillis { SchoolMailMessageViewModel.normalizedHref(hostile) }
        assertTrue("normalizedHref took ${elapsed}ms on a 32k-char hostile fragment", elapsed < 1000)
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
        vm.confirmDelete(true)
        assertTrue(vm.state.value.actionError is MailError.FolderChanged)
        assertFalse(vm.state.value.closed)
    }

    // --- normalizedHref: direct asserts for the image-URL allowlist (MailWebView) -----------

    @Test
    fun `normalizedHref matches Chromium's form for an uppercase host`() {
        assertEquals(
            SchoolMailMessageViewModel.normalizedHref("http://example.com/"),
            SchoolMailMessageViewModel.normalizedHref("HTTP://Example.COM"),
        )
    }

    @Test
    fun `normalizedHref matches Chromium's form for an empty path`() {
        assertEquals(
            SchoolMailMessageViewModel.normalizedHref("https://example.com/"),
            SchoolMailMessageViewModel.normalizedHref("https://example.com"),
        )
    }

    @Test
    fun `normalizedHref matches Chromium's percent-encoded form for a non-ASCII path`() {
        assertEquals(
            SchoolMailMessageViewModel.normalizedHref("https://example.com/caf%C3%A9"),
            SchoolMailMessageViewModel.normalizedHref("https://example.com/café"),
        )
    }

    @Test
    fun `normalizedHref matches Chromium's percent-encoded form for a space in the path`() {
        assertEquals(
            SchoolMailMessageViewModel.normalizedHref("https://example.com/a%20b"),
            SchoolMailMessageViewModel.normalizedHref("https://example.com/a b"),
        )
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
