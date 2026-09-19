package org.ntust.app.tigerduck.mail.imap

import jakarta.mail.internet.MimeBodyPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.MailLimits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The places one message's own claims used to decide how much of it the app would hold in memory,
 * or write to disk. Both sides of each are the sender's to choose, so neither may be trusted: an
 * `OutOfMemoryError` is an `Error`, and nothing between here and the uncaught handler catches one.
 */
class AngusMailPartsTest {

    /** A part whose `BODYSTRUCTURE` size is whatever [size] says -- -1 for "the server didn't say". */
    private fun imagePart(size: Int, bytes: ByteArray = ByteArray(16)) = object : MimeBodyPart() {
        override fun getSize(): Int = size
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
    }.apply {
        setHeader("Content-Type", "image/png")
        setHeader("Content-ID", "<logo@x>")
    }

    @Test
    fun `an inline image of unknown size is not inlined`() {
        // -1 means BODYSTRUCTURE never said how big it is. Treating that as inlineable skipped
        // the limit for exactly the parts whose size nothing had vouched for.
        assertEquals(AngusMailSession.PartKind.Attachment, AngusMailSession.kindOf(imagePart(size = -1)))
    }

    @Test
    fun `an inline image within the limit is still inlined, and one over it is not`() {
        assertEquals(
            AngusMailSession.PartKind.Inline("logo@x"),
            AngusMailSession.kindOf(imagePart(size = 1024)),
        )
        assertEquals(
            AngusMailSession.PartKind.Attachment,
            AngusMailSession.kindOf(imagePart(size = (MailLimits.INLINE_IMAGE_BYTES + 1).toInt())),
        )
    }

    @Test
    fun `a part is read only up to its limit, however much it actually holds`() {
        // The declared size is a lie: 16 bytes claimed, a megabyte delivered.
        val part = imagePart(size = 16, bytes = ByteArray(1024 * 1024) { 'x'.code.toByte() })
        assertEquals(4_096, AngusMailSession.readBounded(part, limit = 4_096).size)
    }

    @Test
    fun `a part shorter than its limit is read whole`() {
        val part = imagePart(size = 16, bytes = ByteArray(40) { 'y'.code.toByte() })
        val read = AngusMailSession.readBounded(part, limit = 4_096)
        assertEquals(40, read.size)
        assertTrue(read.all { it == 'y'.code.toByte() })
    }

    @Test
    fun `a part written past its ceiling is refused, never truncated`() {
        // The write path -- an attachment opened or saved -- makes the opposite trade from
        // readBounded: 16 bytes claimed and a megabyte delivered must fail, because a file cut
        // short at the destination the user chose is indistinguishable from a complete one.
        val part = imagePart(size = 16, bytes = ByteArray(1024 * 1024) { 'x'.code.toByte() })
        val out = ByteArrayOutputStream()
        val failure = runCatching { AngusMailSession.writeBounded(part, out, limit = 4_096L) }.exceptionOrNull()
        assertTrue("expected TooLarge, got $failure", failure is MailError.TooLarge)
        assertEquals(4_096L, (failure as MailError.TooLarge).limitBytes)
        // Nothing beyond the ceiling reached the stream, and what did is the caller's to discard.
        assertTrue(out.size() <= 4_096)
    }

    @Test
    fun `a part that exactly fills its ceiling is written whole`() {
        // size = -1: BODYSTRUCTURE never said, so only the bytes themselves decide.
        val part = imagePart(size = -1, bytes = ByteArray(4_096) { 'z'.code.toByte() })
        val out = ByteArrayOutputStream()
        AngusMailSession.writeBounded(part, out, limit = 4_096L)
        assertEquals(4_096, out.size())
        assertTrue(out.toByteArray().all { it == 'z'.code.toByte() })
    }
}
