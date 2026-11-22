package org.ntust.app.tigerduck.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.files.RasterImage

class MailFileNamesTest {

    @Test
    fun `a name never traverses out of the folder it is written in`() {
        assertEquals("attachment", MailFileNames.safe(".."))
        assertEquals("attachment", MailFileNames.safe("."))
        val traversal = MailFileNames.safe("../../x")
        assertFalse(traversal.contains('/'))
        assertFalse(traversal.contains('\\'))
        assertTrue(traversal != "." && traversal != "..")
        assertEquals("a_b_c", MailFileNames.safe("a/b\\c"))
    }

    @Test
    fun `a long name is truncated, keeping its extension and never splitting a surrogate pair`() {
        val longName = "a".repeat(200) + ".pdf"
        val result = MailFileNames.safe(longName)
        assertTrue(result.length <= 120)
        assertTrue(result.endsWith(".pdf"))

        // U+1F600 (an emoji outside the BMP) is a surrogate pair in UTF-16. The leading "x"
        // shifts every pair's boundary by one code unit relative to a bare run of pairs, so the
        // stem's truncation cut (120 - ".png".length = 116 code units in) lands exactly between
        // a pair's two halves -- precisely the case a naive take(116) would get wrong.
        val emojiName = "x" + "😀".repeat(70) + ".png"
        val truncatedEmoji = MailFileNames.safe(emojiName)
        assertTrue(truncatedEmoji.endsWith(".png"))
        val stem = truncatedEmoji.removeSuffix(".png")
        // A lone high surrogate at the very end (its low half was cut off) means a pair got
        // split; ending on a low surrogate is fine -- that IS a pair's completed second half.
        assertFalse(stem.isNotEmpty() && Character.isHighSurrogate(stem.last()))
    }

    /** A file name may run to 255 bytes, and a Chinese character is three of them in UTF-8. */
    @Test
    fun `a long Chinese name fits the file system's 255 bytes, its extension kept`() {
        val name = MailFileNames.safe("課".repeat(150) + ".png")
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(name.endsWith(".png"))
        assertTrue(name.removeSuffix(".png").all { it == '課' })

        val emoji = MailFileNames.safe("😀".repeat(100) + ".pdf")
        assertTrue(emoji.toByteArray(Charsets.UTF_8).size <= 200)
        assertFalse(Character.isHighSurrogate(emoji.removeSuffix(".pdf").last()))
    }

    @Test
    fun `a body image takes the extension of the kind its bytes are`() {
        assertEquals("photo.png", MailFileNames.image("photo.JPG", RasterImage.PNG))
        assertEquals("課表.jpg", MailFileNames.image("課表.png", RasterImage.JPEG))
        assertEquals("image.gif", MailFileNames.image(null, RasterImage.GIF))
        assertEquals("image.webp", MailFileNames.image(" ", RasterImage.WEBP))
        assertEquals("image.png", MailFileNames.image(".png", RasterImage.PNG))
        assertEquals("a_b.png", MailFileNames.image("a/b.png", RasterImage.PNG))
    }
}
