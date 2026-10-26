package org.ntust.app.tigerduck.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.files.FileOpening.InApp
import org.ntust.app.tigerduck.files.FileOpening.OtherApp
import org.ntust.app.tigerduck.files.FileOpening.ShareOnly
import java.io.ByteArrayInputStream

class FileViewingTest {

    private fun opening(type: String, name: String) = FileViewing.opening(type, name)

    @Test
    fun `a pdf, an image, a video, a sound, text and markdown open here`() {
        assertEquals(InApp(ViewerKind.PDF), opening("application/pdf", "week 3.pdf"))
        assertEquals(InApp(ViewerKind.IMAGE), opening("image/jpeg", "board.jpg"))
        assertEquals(InApp(ViewerKind.IMAGE), opening("image/heic", "board.heic"))
        assertEquals(InApp(ViewerKind.VIDEO), opening("video/quicktime", "demo.mov"))
        assertEquals(InApp(ViewerKind.VIDEO), opening("video/mp4", "demo.mp4"))
        assertEquals(InApp(ViewerKind.AUDIO), opening("audio/mp4", "lecture.m4a"))
        assertEquals(InApp(ViewerKind.AUDIO), opening("audio/ogg", "lecture.ogg"))
        assertEquals(InApp(ViewerKind.TEXT), opening("text/plain", "notes.txt"))
        assertEquals(InApp(ViewerKind.TEXT), opening("application/json", "data.json"))
        assertEquals(InApp(ViewerKind.TEXT), opening("text/x-java-source", "Main.java"))
        assertEquals(InApp(ViewerKind.MARKDOWN), opening("text/markdown", "README.md"))
    }

    @Test
    fun `word and powerpoint files open here, the old binary formats in another app`() {
        assertEquals(
            InApp(ViewerKind.DOCX),
            opening("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "report.docx"),
        )
        assertEquals(
            InApp(ViewerKind.PPTX),
            opening("application/vnd.openxmlformats-officedocument.presentationml.presentation", "slides.pptx"),
        )
        assertEquals(OtherApp, opening("application/msword", "report.doc"))
        assertEquals(OtherApp, opening("application/vnd.ms-powerpoint", "slides.ppt"))
        assertEquals(
            OtherApp,
            opening("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "grades.xlsx"),
        )
    }

    @Test
    fun `wma and anything else go to another app`() {
        assertEquals(OtherApp, opening("audio/x-ms-wma", "song.wma"))
        assertEquals(OtherApp, opening("application/vnd.android.package-archive", "app.apk"))
        assertEquals(OtherApp, opening("application/x-rar-compressed", "code.rar"))
    }

    @Test
    fun `zip, tar, tar gz and 7z archives open here, by type or by a generic type's name`() {
        assertEquals(InApp(ViewerKind.ARCHIVE), opening("application/zip", "code.zip"))
        assertEquals(InApp(ViewerKind.ARCHIVE), opening("application/x-zip-compressed", "code.zip"))
        assertEquals(InApp(ViewerKind.ARCHIVE), opening("application/x-7z-compressed", "code.7z"))
        assertEquals(InApp(ViewerKind.ARCHIVE), opening("application/x-tar", "code.tar"))
        assertEquals(InApp(ViewerKind.ARCHIVE), opening("application/gzip", "code.tar.gz"))
        assertEquals(InApp(ViewerKind.ARCHIVE), opening("application/x-compressed-tar", "code.tgz"))
        assertEquals(InApp(ViewerKind.ARCHIVE), opening("application/x-bzip2", "code.tar.bz2"))
        assertEquals(InApp(ViewerKind.ARCHIVE), opening("application/x-xz", "code.tar.xz"))
        for (name in listOf("code.zip", "code.7z", "code.tar", "code.tar.gz", "code.tgz", "code.tbz2", "code.txz", "log.gz")) {
            assertEquals(name, InApp(ViewerKind.ARCHIVE), opening("application/octet-stream", name))
        }
    }

    @Test
    fun `gzip and tar under the names moodle, android and desktop systems give them open here`() {
        // Moodle's own spelling for .tgz, .gz and .gzip (lib/classes/filetypes.php).
        assertEquals(InApp(ViewerKind.ARCHIVE), opening("application/g-zip", "code.tar.gz"))
        assertEquals(InApp(ViewerKind.ARCHIVE), opening("application/g-zip", "code.tgz"))
        assertEquals(InApp(ViewerKind.ARCHIVE), opening("application/g-zip", "log.gz"))
        // Debian's mime.types, which Android's own type map starts from, for .tgz.
        assertEquals(InApp(ViewerKind.ARCHIVE), opening("application/x-gtar-compressed", "code.tgz"))
        // shared-mime-info's names for bzip2 and a tar inside it.
        assertEquals(InApp(ViewerKind.ARCHIVE), opening("application/bzip2", "data.bz2"))
        assertEquals(InApp(ViewerKind.ARCHIVE), opening("application/x-bzip2-compressed-tar", "code.tar.bz2"))
        // Moodle's .gzip, by name when the type says nothing.
        assertEquals(InApp(ViewerKind.ARCHIVE), opening("application/octet-stream", "log.gzip"))
    }

    @Test
    fun `a word or powerpoint file sent as a zip opens as the document its name says`() {
        // Both are zips inside: a server that only sees that sends them as one.
        assertEquals(InApp(ViewerKind.DOCX), opening("application/zip", "report.docx"))
        assertEquals(InApp(ViewerKind.PPTX), opening("application/x-zip-compressed", "slides.pptx"))
        // Any other name keeps the type it was sent with.
        assertEquals(InApp(ViewerKind.ARCHIVE), opening("application/zip", "photo.png"))
    }

    @Test
    fun `a spreadsheet, a book or an app sent as a zip goes where its name says, not into the archive viewer`() {
        // Zips inside too, but their parts mean nothing listed one by one.
        for (name in listOf("grades.xlsx", "notes.odt", "book.epub", "app.apk", "tool.jar")) {
            assertEquals(name, OtherApp, opening("application/zip", name))
        }
    }

    @Test
    fun `web pages, svg and xml never render here, by type or by name`() {
        assertEquals(ShareOnly, opening("text/html", "page.html"))
        assertEquals(ShareOnly, opening("application/xhtml+xml", "page.xhtml"))
        assertEquals(ShareOnly, opening("image/svg+xml", "logo.svg"))
        assertEquals(ShareOnly, opening("text/xml", "data.xml"))
        assertEquals(ShareOnly, opening("application/xml", "data.xml"))
        // Named as a page, whatever type it was sent with.
        assertEquals(ShareOnly, opening("text/plain", "page.html"))
        assertEquals(ShareOnly, opening("image/png", "logo.svg"))
    }

    @Test
    fun `an xml file never renders here, whatever it was sent as`() {
        assertEquals(ShareOnly, opening("text/plain", "data.xml"))
        assertEquals(ShareOnly, opening("text/markdown", "feed.xml"))
    }

    @Test
    fun `big5 cut by the limit inside a character reads as big5 still`() {
        // Each of these is 2 bytes in Big5: 7 bytes end inside the fourth.
        val bytes = "課程大綱".toByteArray(charset("Big5"))
        assertEquals(TextPreview("課程大", truncated = true), TextPreviews.read(ByteArrayInputStream(bytes), limitBytes = 7))
    }

    @Test
    fun `a page is rendered no bigger than the pixel budget, its shape kept`() {
        // A long strip of a page: 1 inch wide, 200 inches tall.
        val size = PdfPages.renderSize(pageWidth = 72, pageHeight = 14_400, viewWidth = 1080)
        assertTrue("${size.first} x ${size.second}", size.first.toLong() * size.second <= PdfPages.MAX_RENDER_PIXELS)
        assertEquals(14_400.0 / 72, size.second.toDouble() / size.first, 1.0)
        assertTrue(size.first >= 1)
        // A page too thin to keep its shape within the budget at a pixel's width, and one whose
        // height in pixels wouldn't fit an Int: still within it, still taller than wide.
        for (height in listOf(30_000_000, 10_000_000, Int.MAX_VALUE)) {
            val (w, h) = PdfPages.renderSize(pageWidth = 1, pageHeight = height, viewWidth = 1080)
            assertTrue("$height: $w x $h", w >= 1 && h >= 1 && w.toLong() * h <= PdfPages.MAX_RENDER_PIXELS)
            assertTrue("$height: $w x $h", h > w)
        }
        // An ordinary page keeps the sharp width.
        assertEquals(1620 to 2292, PdfPages.renderSize(pageWidth = 595, pageHeight = 842, viewWidth = 1080))
    }

    @Test
    fun `a generic type opens by the name's extension`() {
        assertEquals(InApp(ViewerKind.PDF), opening("application/octet-stream", "week 3.pdf"))
        assertEquals(InApp(ViewerKind.DOCX), opening("document/unknown", "report.docx"))
        assertEquals(InApp(ViewerKind.TEXT), opening("", "notes.log"))
        assertEquals(OtherApp, opening("application/octet-stream", "blob.bin"))
    }

    @Test
    fun `a declared type is believed over the name, as attachments are`() {
        // An installer named like a picture still goes to other apps, which decide for themselves.
        assertEquals(OtherApp, opening("application/vnd.android.package-archive", "photo.png"))
        // A picture named like a pdf is shown as the picture it says it is; if it isn't, the viewer says so.
        assertEquals(InApp(ViewerKind.IMAGE), opening("image/png; name=x", "scan.pdf"))
    }

    @Test
    fun `text is read up to the limit, and says when there is more`() {
        val small = TextPreviews.read(ByteArrayInputStream("hello".toByteArray()), limitBytes = 10)
        assertEquals(TextPreview("hello", truncated = false), small)

        val big = TextPreviews.read(ByteArrayInputStream("0123456789abc".toByteArray()), limitBytes = 10)
        assertEquals(TextPreview("0123456789", truncated = true), big)
    }

    @Test
    fun `a character cut at the limit is left out, not garbled`() {
        // "中" is 3 bytes in UTF-8: 4 bytes of "a中中" end inside the first one.
        val preview = TextPreviews.read(ByteArrayInputStream("a中中".toByteArray()), limitBytes = 5)
        assertEquals(TextPreview("a中", truncated = true), preview)
    }

    @Test
    fun `big5 text, which school systems still send, reads as big5`() {
        val bytes = "課程大綱".toByteArray(charset("Big5"))
        assertEquals("課程大綱", TextPreviews.read(ByteArrayInputStream(bytes)).text)
    }

    @Test
    fun `a byte order mark picks the encoding, and is left out`() {
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "ok".toByteArray()
        assertEquals("ok", TextPreviews.read(ByteArrayInputStream(utf8)).text)
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "ok".toByteArray(Charsets.UTF_16LE)
        assertEquals("ok", TextPreviews.read(ByteArrayInputStream(utf16)).text)
    }

    @Test
    fun `utf-16 cut by the limit between a character's two halves leaves the half out`() {
        // "a" then 🦆, two UTF-16 units: 6 bytes after the mark end between them.
        for ((mark, charset) in listOf(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) to Charsets.UTF_16LE, byteArrayOf(0xFE.toByte(), 0xFF.toByte()) to Charsets.UTF_16BE)) {
            val bytes = mark + "ab🦆".toByteArray(charset)
            assertEquals(TextPreview("ab", truncated = true), TextPreviews.read(ByteArrayInputStream(bytes), limitBytes = 8))
        }
    }

    @Test
    fun `a line too long to lay out at once is shown in pieces`() {
        val minified = "x".repeat(5_000)
        val lines = TextPreviews.lines("first\n$minified\nlast", maxLine = 2_000)
        assertEquals(listOf("first", "x".repeat(2_000), "x".repeat(2_000), "x".repeat(1_000), "last"), lines)
    }

    @Test
    fun `a piece never ends between a character's two halves`() {
        val line = "x".repeat(1_999) + "🦆" + "y"
        assertEquals(listOf("x".repeat(1_999), "🦆y"), TextPreviews.lines(line, maxLine = 2_000))
    }

    @Test
    fun `the page shown is the one at the top, counted from one`() {
        assertEquals(1 to 12, PdfPages.position(firstVisible = 0, count = 12))
        assertEquals(12 to 12, PdfPages.position(firstVisible = 11, count = 12))
        assertEquals(0 to 0, PdfPages.position(firstVisible = 0, count = 0))
    }

    @Test
    fun `a page is drawn as tall as its shape at the screen's width, and rendered sharper`() {
        // A4 in points: 595 × 842.
        assertEquals(1528, PdfPages.displayHeight(pageWidth = 595, pageHeight = 842, width = 1080))
        assertEquals(1620, PdfPages.renderWidth(viewWidth = 1080))
        assertEquals(PdfPages.MAX_RENDER_WIDTH, PdfPages.renderWidth(viewWidth = 4000))
        assertTrue(PdfPages.renderWidth(viewWidth = 0) > 0)
        assertFalse(PdfPages.displayHeight(pageWidth = 0, pageHeight = 842, width = 1080) < 0)
    }
}
