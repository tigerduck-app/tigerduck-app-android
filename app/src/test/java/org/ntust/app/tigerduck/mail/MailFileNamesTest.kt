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
