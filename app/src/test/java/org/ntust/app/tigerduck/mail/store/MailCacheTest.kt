package org.ntust.app.tigerduck.mail.store

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.data.model.mail.MailAddressDto
import org.ntust.app.tigerduck.mail.model.InlineImage
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailAttachment
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailPage
import org.ntust.app.tigerduck.mail.model.MailSummary
import java.io.File
import java.time.Instant

class MailCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun summary(uid: Long) = MailSummary(
        uid, MailAddress("教務處", "office@mail.ntust.edu.tw"), emptyList(), listOf(MailAddress(null, "me@x.tw")), emptyList(),
        "期中考", Instant.ofEpochMilli(1_000), null, MailFlags.NONE.copy(seen = true), 42, true, "<m$uid@x>", null, null,
    )

    private fun body(size: Int = 10) = MailBody(
        html = "<p>${"x".repeat(size)}</p>", plain = "x",
        attachments = listOf(MailAttachment("2", "報告.pdf", "application/pdf", 9, null)),
        inlineImages = mapOf("logo@x" to InlineImage("image/png", byteArrayOf(1, 2, 3))),
    )

    @Test
    fun `folder pages round trip under CJK folder names`() {
        val cache = MailCache(tmp.root)
        cache.saveFolder("寄件備份匣", MailPage(7, 3, listOf(summary(3), summary(2)), nextBeforeSeq = 1))
        val loaded = cache.loadFolder("寄件備份匣")!!
        assertEquals(7L, loaded.uidValidity)
        assertEquals(1, loaded.nextBeforeSeq)
        assertEquals(listOf(summary(3), summary(2)), loaded.messages!!.map { it.toModel() })
        assertNull(cache.loadFolder("INBOX"))
    }

    @Test
    fun `bodies round trip and are keyed by uid validity`() {
        val cache = MailCache(tmp.root)
        cache.saveBody("INBOX", 5, 7, body())
        val loaded = cache.loadBody("INBOX", 5, 7)!!
        assertEquals("x", loaded.plain)
        assertEquals("報告.pdf", loaded.attachments.single().fileName)
        assertArrayEquals(byteArrayOf(1, 2, 3), loaded.inlineImages.getValue("logo@x").bytes)
        assertNull("a new UIDVALIDITY invalidates it", cache.loadBody("INBOX", 5, 8))
        assertNull(cache.loadBody("INBOX", 5, 7))
    }

    @Test
    fun `a sender kept only for its name survives the cache`() {
        val daemon = MailAddress("Mail Deliver System", "")
        val cache = MailCache(tmp.root)
        cache.saveFolder("INBOX", MailPage(1, 1, listOf(summary(1).copy(from = daemon)), nextBeforeSeq = null))
        val from = cache.loadFolder("INBOX")!!.messages!!.single().toModel().from
        assertEquals("reloading from cache must not turn the sender back into '(no sender)'", daemon, from)
        assertFalse(from!!.isRoutable)
        // An entry with neither a name nor an address is still worth nothing and is dropped.
        assertNull(MailAddressDto(null, null).toModel())
        assertNull(MailAddressDto("  ", " ").toModel())
    }

    @Test
    fun `a bounce's null reverse-path survives the cache, and pages written without it read back null`() {
        val cache = MailCache(tmp.root)
        cache.saveFolder("INBOX", MailPage(1, 1, listOf(summary(1).copy(returnPath = "<>")), nextBeforeSeq = null))
        assertEquals("<>", cache.loadFolder("INBOX")!!.messages!!.single().toModel().returnPath)
        // A page cached before the field existed simply has no such key: Gson leaves it null, and
        // the mail is then judged exactly as it was before.
        cache.saveFolder("INBOX", MailPage(1, 1, listOf(summary(1)), nextBeforeSeq = null))
        assertNull(cache.loadFolder("INBOX")!!.messages!!.single().toModel().returnPath)
    }

    @Test
    fun `wrong versions and corrupt files are deleted, never thrown`() {
        val cache = MailCache(tmp.root)
        cache.saveFolder("INBOX", MailPage(1, 0, emptyList(), null))
        val file = File(tmp.root, "folders").listFiles()!!.single()
        file.writeText("{ not json")
        assertNull(cache.loadFolder("INBOX"))
        assertFalse(file.exists())

        cache.saveFolder("INBOX", MailPage(1, 0, emptyList(), null))
        val again = File(tmp.root, "folders").listFiles()!!.single()
        again.writeText(again.readText().replace("\"version\":${MailCache.VERSION}", "\"version\":999"))
        assertNull(cache.loadFolder("INBOX"))
        assertFalse(again.exists())
    }

    @Test
    fun `the body cache evicts least recently used files past its limit`() {
        val cache = MailCache(tmp.root, maxBodyBytes = 3_000)
        cache.saveBody("INBOX", 1, 7, body(1_000))
        File(tmp.root, "bodies").listFiles()!!.forEach { it.setLastModified(1_000) }
        cache.saveBody("INBOX", 2, 7, body(1_000))
        cache.saveBody("INBOX", 3, 7, body(1_000))
        assertNull("oldest evicted", cache.loadBody("INBOX", 1, 7))
        assertTrue(cache.loadBody("INBOX", 3, 7) != null)
    }

    @Test
    fun `sources round trip, are keyed by uid validity and never collide with a body`() {
        val cache = MailCache(tmp.root)
        cache.saveBody("INBOX", 5, 7, body())
        cache.saveSource("INBOX", 5, 7, "Subject: x\r\n\r\nraw")
        assertEquals("Subject: x\r\n\r\nraw", cache.loadSource("INBOX", 5, 7))
        assertEquals("the source must not have overwritten the body", "x", cache.loadBody("INBOX", 5, 7)!!.plain)
        assertNull("a new UIDVALIDITY invalidates it", cache.loadSource("INBOX", 5, 8))
        assertNull(cache.loadSource("INBOX", 5, 7))
        assertNull(cache.loadSource("INBOX", 6, 7))
    }

    @Test
    fun `sources share the bodies' LRU budget instead of growing beside it`() {
        val cache = MailCache(tmp.root, maxBodyBytes = 3_000)
        cache.saveBody("INBOX", 1, 7, body(1_000))
        File(tmp.root, "bodies").listFiles()!!.forEach { it.setLastModified(1_000) }
        cache.saveSource("INBOX", 2, 7, "s".repeat(1_000))
        cache.saveSource("INBOX", 3, 7, "s".repeat(1_000))
        assertNull("the oldest entry is evicted, body or source alike", cache.loadBody("INBOX", 1, 7))
        assertTrue(cache.loadSource("INBOX", 3, 7) != null)
    }

    @Test
    fun `sizeBytes counts folders, bodies, sources and attachments together`() {
        val cache = MailCache(tmp.root)
        assertEquals(0L, cache.sizeBytes())
        cache.saveFolder("INBOX", MailPage(1, 1, listOf(summary(1)), null))
        cache.saveBody("INBOX", 1, 1, body())
        cache.saveSource("INBOX", 1, 1, "raw")
        cache.attachmentsDir.mkdirs()
        File(cache.attachmentsDir, "a.pdf").writeText("0123456789")
        val expected = tmp.root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertEquals(expected, cache.sizeBytes())
        assertTrue(cache.sizeBytes() > 10)
    }

    @Test
    fun `clearAll removes everything including attachments`() {
        val cache = MailCache(tmp.root)
        cache.saveBody("INBOX", 1, 7, body())
        cache.attachmentsDir.mkdirs()
        File(cache.attachmentsDir, "a.pdf").writeText("x")
        cache.clearAll()
        assertFalse(tmp.root.exists() && tmp.root.listFiles()!!.isNotEmpty())
    }

    @Test
    fun `a write that fails (read-only cache directory) never throws`() {
        val cache = MailCache(tmp.root)
        val foldersDir = File(tmp.root, "folders")
        foldersDir.mkdirs()
        foldersDir.setWritable(false)
        try {
            cache.saveFolder("INBOX", MailPage(1, 0, emptyList(), null))
            assertNull("the failed write must leave no readable cache entry", cache.loadFolder("INBOX"))
        } finally {
            foldersDir.setWritable(true)
        }
    }
}
