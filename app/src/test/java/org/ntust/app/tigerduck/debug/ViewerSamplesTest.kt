package org.ntust.app.tigerduck.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import java.io.InputStream
import java.io.IOException
import java.io.File

class ViewerSamplesTest {

    private val extensions = mapOf("video/quicktime" to "mov", "application/pdf" to "pdf")
    private val types = extensions.entries.associate { (type, extension) -> extension to type }

    private fun name(displayName: String?, mimeType: String? = null) =
        ViewerSamples.pickedName(displayName, mimeType) { extensions[it] }

    private fun type(providerType: String?, name: String) = ViewerSamples.pickedType(providerType, name) { types[it] }

    @Test
    fun `the index reads group, name, type and size, under its header`() {
        val text = "group\tname\tmime\tbytes\nVideo\th264.mov\tvideo/quicktime\t99748\n\nEdge cases\tzero-bytes.pdf\tapplication/pdf\t0\n"

        assertEquals(
            listOf(
                ViewerSample("Video", "h264.mov", "video/quicktime", 99_748),
                ViewerSample("Edge cases", "zero-bytes.pdf", "application/pdf", 0),
            ),
            ViewerSamples.parseIndex(text),
        )
    }

    @Test
    fun `a gzip sample is stored under another name, which the asset merge leaves alone`() {
        assertEquals("archive.tar.gz.sample", ViewerSamples.assetName("archive.tar.gz"))
        assertEquals("readme.txt.gz.sample", ViewerSamples.assetName("readme.txt.gz"))
        assertEquals("archive.tar", ViewerSamples.assetName("archive.tar"))
        assertEquals("h264.mov", ViewerSamples.assetName("h264.mov"))
    }

    @Test
    fun `a picked file keeps its own name`() {
        assertEquals("IMG_0042.MOV", name("IMG_0042.MOV"))
        assertEquals("作業 1.pdf", name("作業 1.pdf"))
    }

    @Test
    fun `a picked name never reaches outside the copy's folder`() {
        assertEquals("x.mov", name("../../files/x.mov"))
        assertEquals("c.txt", name("a\\b\\c.txt"))
        assertEquals("picked.mov", name("..", "video/quicktime"))
    }

    @Test
    fun `a picked name loses control characters`() {
        assertEquals("ab.pdf", name("a\u0000\nb.pdf"))
    }

    @Test
    fun `a picked file with no name is named for its type`() {
        assertEquals("picked.mov", name(null, "video/quicktime"))
        assertEquals("picked.pdf", name("  ", "application/pdf"))
        assertEquals("picked", name(null, "application/x-unknown"))
    }

    @Test
    fun `a long picked name is cut short, its extension kept`() {
        val picked = name("a".repeat(300) + ".mov")

        assertEquals(200, picked.toByteArray().size)
        assertEquals(".mov", picked.takeLast(4))
    }

    @Test
    fun `a picked name is cut by its bytes, which a file name is limited in`() {
        // 94 characters, but 274 bytes in UTF-8: past the 255 a file name may take.
        val picked = name("作".repeat(90) + ".pdf")

        assertTrue(picked.toByteArray().size <= 200)
        assertEquals("作".repeat(65) + ".pdf", picked)
    }

    @Test
    fun `a picked name is never cut inside a character`() {
        val picked = name("😀".repeat(100) + ".txt")

        assertTrue(picked.toByteArray().size <= 200)
        assertEquals(picked, String(picked.toByteArray(), Charsets.UTF_8))
        assertTrue(picked.endsWith(".txt"))
    }

    @Test
    fun `the newest picks stay, for an app that may still be reading one`() {
        val picks = listOf("1000-a", "3000-b", "junk", "2000-c", "4000-d")

        assertEquals(setOf("1000-a", "junk"), ViewerSamples.picksToDrop(picks, keep = 3).toSet())
        assertEquals(emptyList<String>(), ViewerSamples.picksToDrop(listOf("1000-a"), keep = 3))
    }

    @Test
    fun `a picked file opens as the type its provider gives`() {
        assertEquals("video/quicktime", type("video/quicktime", "clip.mp4"))
        assertEquals("application/octet-stream", type("application/octet-stream", "clip.mov"))
    }

    @Test
    fun `with no type from its provider, the name's extension gives it`() {
        assertEquals("video/quicktime", type(null, "clip.MOV"))
        assertEquals("application/pdf", type(" ", "a.pdf"))
        assertEquals("application/octet-stream", type(null, "a.unknown"))
    }

    @get:Rule
    val temp = TemporaryFolder()

    /** Gives [good] bytes, then fails, as a provider whose file went away mid-read does. */
    private fun failingAfter(good: Int) = object : InputStream() {
        var left = good
        override fun read(): Int = if (left-- > 0) 'x'.code else throw IOException("gone")
    }

    @Test
    fun `a copy that fails part way leaves no part of the file, nor its folder`() {
        val folder = File(temp.root, "picked/1-a")
        val file = File(folder, "clip.mov")

        assertThrows(IOException::class.java) { ViewerSamples.copyInto(file, folder) { failingAfter(10_000) } }

        assertFalse("the partial file is gone", file.exists())
        assertFalse("its folder is gone", folder.exists())
    }

    @Test
    fun `a copy that goes through leaves the file whole`() {
        val folder = File(temp.root, "bundled")
        val file = File(folder, "notes.md")

        ViewerSamples.copyInto(file, cleanUp = file) { "# Notes".byteInputStream() }

        assertEquals("# Notes", file.readText())
    }
}
