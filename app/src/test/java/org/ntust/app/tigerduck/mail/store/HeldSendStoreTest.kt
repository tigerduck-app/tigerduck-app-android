package org.ntust.app.tigerduck.mail.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.mail.store.HeldSendStore.Fields
import org.ntust.app.tigerduck.mail.store.HeldSendStore.Keep
import java.io.File

class HeldSendStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val me = "b00015999@mail.ntust.edu.tw"
    private val root by lazy { File(tmp.root, "held") }

    /** The store as a process sees it; a new one on the same directory is the app after a restart. */
    private fun store() = HeldSendStore(root, clock = { 1_000L })

    private val fields = Fields(
        mode = "REPLY", sourceFolder = "INBOX", sourceUid = 42, sourceLoaded = true,
        inReplyTo = "<a@x>", references = "<r@x> <a@x>",
        to = listOf("\"Chen, Bob\" <bob@x.tw>"), cc = listOf("amy@x.tw"), bcc = emptyList(),
        subject = "Re: Lab", body = "See attached.",
    )

    private val picked = Keep("p1", "report.pdf", "application/pdf", 5, open = { "%PDF-".byteInputStream() })
    private val carried = Keep("orig-2", "slides.pdf", "application/pdf", 900, original = HeldSendStore.Original("INBOX", 42, "2"))

    @Test
    fun `a held mail comes back after the app restarts, with copies of the files picked`() {
        assertTrue(store().save("m1", me, fields, listOf(picked, carried)) { true })

        val back = store().orphan(me)!!

        assertEquals("m1", back.id)
        assertEquals(fields, back.fields)
        assertNull("held, its send not started", back.sendingSinceMs)
        assertEquals("%PDF-", back.attachments[0].copy!!.readText())
        assertEquals(listOf("report.pdf", "slides.pdf"), back.attachments.map { it.fileName })
        assertEquals(HeldSendStore.Original("INBOX", 42, "2"), back.attachments[1].original)
    }

    @Test
    fun `a picked file whose copy is gone comes back named as missing, the rest as they were`() {
        assertTrue(store().save("m1", me, fields, listOf(picked, carried)) { true })
        val copy = store().orphan(me)!!.attachments[0].copy!!
        copy.delete()

        val back = store().orphan(me)!!

        assertEquals(listOf("slides.pdf"), back.attachments.map { it.fileName })
        assertEquals(listOf("report.pdf"), back.missing)
    }

    @Test
    fun `one whose send had started comes back saying since when`() {
        val store = store()
        store.save("m1", me, fields, emptyList()) { true }

        store.markSending("m1")

        assertEquals(1_000L, store().orphan(me)?.sendingSinceMs)
    }

    @Test
    fun `a mail a composer of this process holds is not an orphan, until the process is gone`() {
        val store = store()
        store.save("m1", me, fields, emptyList()) { true }

        assertNull(store.orphan(me))
        assertNotNull(store().orphan(me))
    }

    @Test
    fun `a composer adopting a mail holds it for this process`() {
        store().save("m1", me, fields, emptyList()) { true }
        val store = store()

        assertEquals(fields, store.adopt("m1", me)?.fields)
        assertNull(store.orphan(me))
        assertNull("one composer at a time", store.adopt("m1", me))
        assertNull("not another student's to adopt", store().adopt("m1", "b00015998@mail.ntust.edu.tw"))
    }

    @Test
    fun `a sign-out empties only the departing student's held mail`() {
        val next = "b00015998@mail.ntust.edu.tw"
        val store = store()
        store.save("mine", me, fields, emptyList()) { true }
        store.save("next", next, fields, emptyList()) { true }

        store.clear(me)

        assertFalse(File(root, "mine").exists())
        assertEquals("next", store().orphan(next)?.id)
    }

    @Test
    fun `a sign-out's late cleanup leaves a mail a sign-in since has kept`() {
        val store = store()
        store.save("before", me, fields, emptyList(), generation = 1) { true }
        // The same student signed in again, and kept a mail, before the cleanup ran.
        store.save("since", me, fields, emptyList(), generation = 3) { true }
        // And one this process did not keep: from before a restart.
        File(root, "earlier").mkdirs()
        HeldSendStore(root).save("earlier", me, fields, emptyList(), generation = 9) { true }

        store.clear(me, below = 2)

        assertFalse(File(root, "before").exists())
        assertFalse(File(root, "earlier").exists())
        assertEquals("since", store().orphan(me)?.id)
    }

    @Test
    fun `marking a mail as sending says whether it was marked`() {
        val store = store()
        store.save("m1", me, fields, emptyList()) { true }

        assertTrue(store.markSending("m1"))
        File(root, "m1").deleteRecursively()
        assertFalse(store.markSending("m1"))
    }

    @Test
    fun `another student's held mail is never handed out, and goes when looked for`() {
        store().save("m1", me, fields, listOf(picked)) { true }

        assertNull(store().orphan("b00015998@mail.ntust.edu.tw"))
        assertNull(store().orphan(me))
        assertFalse(File(root, "m1").exists())
    }

    @Test
    fun `an unreadable or half-written mail is dropped, not handed out`() {
        File(root, "garbage").apply { mkdirs() }.resolve("record.json").writeText("{ not json")
        File(root, "partial").apply { mkdirs() }.resolve("a0").writeText("half a file")
        File(root, "old").apply { mkdirs() }.resolve("record.json").writeText("""{"version":99,"owner":"$me"}""")
        File(root, "empty").apply { mkdirs() }.resolve("record.json").writeText("""{"version":1}""")

        assertNull(store().orphan(me))
        assertEquals(emptyList<String>(), root.list().orEmpty().toList())
    }

    @Test
    fun `a sign-out empties it, and a save for an account that left is refused`() {
        val store = store()
        store.save("m1", me, fields, listOf(picked)) { true }

        store.clear(null)

        assertFalse(store.save("m2", me, fields, emptyList()) { false })
        assertNull(store().orphan(me))
        assertEquals(emptyList<String>(), root.list().orEmpty().toList())
    }

    @Test
    fun `a forgotten mail is no longer handed out, its copies kept for the composer until it lets go`() {
        val store = store()
        store.save("m1", me, fields, listOf(picked)) { true }
        val copy = store().orphan(me)!!.attachments[0].copy!!

        store.forget("m1")

        assertTrue("the composer still reads it", copy.exists())
        store.release("m1")
        assertFalse(copy.exists())
        assertNull(store().orphan(me))
    }

    @Test
    fun `a composer letting go of a mail still to resolve leaves it for after a restart`() {
        val store = store()
        store.save("m1", me, fields, emptyList()) { true }

        store.release("m1")

        assertEquals("m1", store().orphan(me)?.id)
    }

    @Test
    fun `saving again keeps the copies it still names and drops the others`() {
        val store = store()
        val other = Keep("p2", "draft.txt", "text/plain", 3, open = { "abc".byteInputStream() })
        store.save("m1", me, fields, listOf(picked, other)) { true }
        val kept = store().orphan(me)!!.attachments
        val stays = kept[0].copy!!

        // Saved again from the composer, whose first attachment reads the copy, the second removed.
        store.save("m1", me, fields.copy(subject = "Re: Lab (2)"), listOf(Keep.of(kept[0]))) { true }

        val back = store().orphan(me)!!
        assertEquals("Re: Lab (2)", back.fields.subject)
        assertEquals(listOf(stays), back.attachments.map { it.copy })
        assertEquals("%PDF-", stays.readText())
        assertFalse(kept[1].copy!!.exists())
    }

    @Test
    fun `the oldest of two comes back first`() {
        var now = 1L
        val store = HeldSendStore(root, clock = { now })
        store.save("new", me, fields, emptyList()) { true }
        now = 0L
        store.save("old", me, fields, emptyList()) { true }

        assertEquals("old", HeldSendStore(root).orphan(me)?.id)
    }
}
