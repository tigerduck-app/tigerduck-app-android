package org.ntust.app.tigerduck.mail.mime

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Properties

class MimeWalkerTest {

    private fun message(source: String) =
        MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(source.toByteArray()))

    /** [levels] nested multipart-mixed parts with one text-plain part at the bottom. */
    private fun deeplyNested(levels: Int): String = buildString {
        append("Subject: deep\r\n")
        append("Content-Type: multipart/mixed; boundary=\"b0\"\r\n\r\n")
        for (i in 1 until levels) {
            append("--b${i - 1}\r\n")
            append("Content-Type: multipart/mixed; boundary=\"b$i\"\r\n\r\n")
        }
        append("--b${levels - 1}\r\nContent-Type: text/plain\r\n\r\nhi\r\n")
        for (i in levels - 1 downTo 0) append("--b$i--\r\n")
    }

    /** One multipart-mixed part with [count] text-plain children. */
    private fun wide(count: Int): String = buildString {
        append("Subject: wide\r\n")
        append("Content-Type: multipart/mixed; boundary=\"b\"\r\n\r\n")
        repeat(count) { append("--b\r\nContent-Type: text/plain\r\n\r\npart $it\r\n") }
        append("--b--\r\n")
    }

    @Test
    fun `a normally nested message still walks to every leaf`() {
        val leaves = MimeWalker.leaves(message(deeplyNested(3)))
        assertEquals(listOf("1.1.1"), leaves.map { it.path })
    }

    /**
     * ~170 KB of headers buys two thousand levels, and two thousand recursive frames overflow
     * the stack -- a `StackOverflowError`, which the `catch (e: Exception)` around `fetchBody`
     * and `find` does not stop. Opening one such mail killed the process, deterministically.
     */
    @Test
    fun `a message nested past any sane depth is kept as one opaque leaf instead of overflowing`() {
        val leaves = MimeWalker.leaves(message(deeplyNested(2_000)))
        val leaf = leaves.single()
        // Stopped at the bound: the part at MAX_DEPTH is kept whole rather than descended into.
        assertEquals(MimeWalker.MAX_DEPTH, leaf.path.split('.').size)
        assertTrue(leaf.part.isMimeType("multipart/*"))
    }

    @Test
    fun `find over a message nested past any sane depth answers instead of overflowing`() {
        val root = message(deeplyNested(2_000))
        assertNotNull(MimeWalker.find(root, List(MimeWalker.MAX_DEPTH) { "1" }.joinToString(".")))
        assertNull(MimeWalker.find(root, "1.2.3"))
    }

    @Test
    fun `a message with more parts than the cap stops at the cap`() {
        assertEquals(MimeWalker.MAX_LEAVES, MimeWalker.leaves(message(wide(MimeWalker.MAX_LEAVES + 50))).size)
    }
}
