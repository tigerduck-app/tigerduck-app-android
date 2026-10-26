package org.ntust.app.tigerduck.debug

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.files.FileOpening
import org.ntust.app.tigerduck.files.FileOpening.InApp
import org.ntust.app.tigerduck.files.FileOpening.OtherApp
import org.ntust.app.tigerduck.files.FileOpening.ShareOnly
import org.ntust.app.tigerduck.files.FileViewing
import org.ntust.app.tigerduck.files.ViewerKind
import java.io.File

/**
 * The debug build's file viewer samples, which tools/viewer-samples/make_samples.sh writes: the
 * index and the files agree, and each sample opens where this table says. A new sample needs a
 * line here, so whoever adds it decides where it should go.
 */
class ViewerSamplesIndexTest {

    private val dir = File("src/debug/assets/viewer-samples")
    private val samples = ViewerSamples.parseIndex(File(dir, "index.tsv").readText())

    private val expected: Map<String, FileOpening> = mapOf(
        "h264.mp4" to InApp(ViewerKind.VIDEO),
        "h264.mov" to InApp(ViewerKind.VIDEO),
        "hevc-8bit.mov" to InApp(ViewerKind.VIDEO),
        "hevc-main10-1080p.mov" to InApp(ViewerKind.VIDEO),
        "hevc-main10-4k60.mov" to InApp(ViewerKind.VIDEO),
        "av1.mp4" to InApp(ViewerKind.VIDEO),
        "vp9.webm" to InApp(ViewerKind.VIDEO),
        "h264.mkv" to InApp(ViewerKind.VIDEO),
        "h264.3gp" to InApp(ViewerKind.VIDEO),
        "tone.mp3" to InApp(ViewerKind.AUDIO),
        "tone.m4a" to InApp(ViewerKind.AUDIO),
        "tone.ogg" to InApp(ViewerKind.AUDIO),
        "tone.opus" to InApp(ViewerKind.AUDIO),
        "tone.flac" to InApp(ViewerKind.AUDIO),
        "tone.wav" to InApp(ViewerKind.AUDIO),
        "pattern.jpg" to InApp(ViewerKind.IMAGE),
        "pattern.png" to InApp(ViewerKind.IMAGE),
        "animated.gif" to InApp(ViewerKind.IMAGE),
        "pattern.webp" to InApp(ViewerKind.IMAGE),
        "pattern.heic" to InApp(ViewerKind.IMAGE),
        "pattern.bmp" to InApp(ViewerKind.IMAGE),
        "three-pages.pdf" to InApp(ViewerKind.PDF),
        "document.docx" to InApp(ViewerKind.DOCX),
        "slides.pptx" to InApp(ViewerKind.PPTX),
        "utf8.txt" to InApp(ViewerKind.TEXT),
        "big5.txt" to InApp(ViewerKind.TEXT),
        "utf16.txt" to InApp(ViewerKind.TEXT),
        "notes.md" to InApp(ViewerKind.MARKDOWN),
        "sample.kt" to InApp(ViewerKind.TEXT),
        "archive.zip" to InApp(ViewerKind.ARCHIVE),
        "archive.tar" to InApp(ViewerKind.ARCHIVE),
        "archive.tar.gz" to InApp(ViewerKind.ARCHIVE),
        "archive.tar.bz2" to InApp(ViewerKind.ARCHIVE),
        "archive.tar.xz" to InApp(ViewerKind.ARCHIVE),
        "readme.txt.gz" to InApp(ViewerKind.ARCHIVE),
        "sheet.xlsx" to OtherApp,
        "vector.svg" to ShareOnly,
        "page.html" to ShareOnly,
        "zero-bytes.pdf" to InApp(ViewerKind.PDF),
        "truncated.mp4" to InApp(ViewerKind.VIDEO),
        "mp4-named.mov" to InApp(ViewerKind.VIDEO),
        "mov-named.mp4" to InApp(ViewerKind.VIDEO),
    )

    @Test
    fun `the index lists every sample file, and only those, at its size`() {
        val files = dir.listFiles()!!.filter { it.name != "index.tsv" }.associate { it.name to it.length() }

        assertEquals(files, samples.associate { ViewerSamples.assetName(it.name) to it.bytes })
    }

    @Test
    fun `every sample has a decision in the table`() {
        assertEquals(expected.keys, samples.map { it.name }.toSet())
    }

    @Test
    fun `each sample opens where the table says`() {
        samples.forEach { assertEquals(it.name, expected[it.name], FileViewing.opening(it.mimeType, it.name)) }
    }
}
