package org.ntust.app.tigerduck.ui.screen.files

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The raw error the file viewer prints under "can't be shown here", for a screenshot to explain. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ViewerErrorsTest {

    /** An iPhone HDR clip's track, as a Main-only HEVC decoder is handed it, labelled by whoever made the file. */
    private val hdrHevc = Format.Builder()
        .setId("1")
        .setLabel("Test Student homework")
        .setSampleMimeType(MimeTypes.VIDEO_H265)
        .setCodecs("hvc1.2.4.L123.B0")
        .setWidth(1920)
        .setHeight(1080)
        .setFrameRate(60f)
        .setColorInfo(
            ColorInfo.Builder()
                .setColorSpace(C.COLOR_SPACE_BT2020)
                .setColorTransfer(C.COLOR_TRANSFER_HLG)
                .setColorRange(C.COLOR_RANGE_LIMITED)
                .build(),
        )
        .build()

    private fun decoderFailure(code: Int = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) =
        ExoPlaybackException.createForRenderer(
            IllegalStateException("Decoder init failed: c2.mtk.hevc.decoder, $hdrHevc"),
            "MediaCodecVideoRenderer",
            0,
            hdrHevc,
            C.FORMAT_EXCEEDS_CAPABILITIES,
            false,
            code,
        )

    @Test
    fun `a decoder that can't take the track says so, with the track it was given`() {
        val text = ViewerErrors.describe(ViewerFailure(decoderFailure()))

        assertTrue(text, text.startsWith("ERROR_CODE_DECODER_INIT_FAILED"))
        assertTrue(text, "caused by IllegalStateException: Decoder init failed: c2.mtk.hevc.decoder" in text)
        assertTrue(text, "video/hevc" in text)
        assertTrue(text, "hvc1.2.4.L123.B0" in text)
        assertTrue(text, "1920x1080" in text)
        assertTrue(text, "60.00fps" in text)
        assertTrue(text, "NO_EXCEEDS_CAPABILITIES" in text)
    }

    @Test
    fun `a track's label, which its maker chose, is never shown`() {
        val text = ViewerErrors.describe(ViewerFailure(decoderFailure()))

        assertFalse(text, "Test Student" in text)
    }

    @Test
    fun `a playback error that isn't a renderer's names the track the player had`() {
        val error = PlaybackException("Source error", null, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)

        val text = ViewerErrors.describe(ViewerFailure(error, track = hdrHevc))

        assertTrue(text, text.startsWith("ERROR_CODE_PARSING_CONTAINER_MALFORMED: Source error"))
        assertTrue(text, "video/hevc" in text)
        assertTrue(text, "1920x1080" in text)
    }

    @Test
    fun `any other error gives its class and message, and its causes`() {
        val text = ViewerErrors.describe(ViewerFailure(java.io.IOException("read failed", IllegalArgumentException("bad header"))))

        assertEquals("IOException: read failed\ncaused by IllegalArgumentException: bad header", text)
    }

    @Test
    fun `a note comes first`() {
        val text = ViewerErrors.describe(ViewerFailure(note = "not rendered within 60 s"))

        assertEquals("not rendered within 60 s", text)
    }

    @Test
    fun `a decoder or format error is the device's not supporting the encoding`() {
        listOf(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        ).forEach { code ->
            assertTrue(PlaybackException.getErrorCodeName(code), ViewerErrors.isUnsupportedEncoding(ViewerFailure(decoderFailure(code))))
        }
    }

    @Test
    fun `a broken file or a read error is not the encoding`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        ).forEach { code ->
            val error = PlaybackException("Source error", null, code)
            assertFalse(PlaybackException.getErrorCodeName(code), ViewerErrors.isUnsupportedEncoding(ViewerFailure(error)))
        }
        assertFalse(ViewerErrors.isUnsupportedEncoding(ViewerFailure(java.io.IOException("read failed"))))
        assertFalse(ViewerErrors.isUnsupportedEncoding(ViewerFailure(note = "not rendered within 60 s")))
    }

    @Test
    fun `tokens are hidden, encoded or not`() {
        val text = ViewerErrors.scrub(
            "query failed for /webservice/rest/server.php?wstoken=def456 and privatetoken=ghi789 " +
                "and access_token=jkl012 and %74oken=mno345",
        )

        listOf("def456", "ghi789", "jkl012", "mno345").forEach { assertFalse(text, it in text) }
    }

    @Test
    fun `a link keeps only its host`() {
        val text = ViewerErrors.scrub(
            "GET https://moodle2.ntust.edu.tw/webservice/pluginfile.php/1/Test%20Student.pdf?token=abc123&studentid=%4200015999 failed",
        )

        assertTrue(text, "https://moodle2.ntust.edu.tw/" in text)
        listOf("abc123", "B00015999", "Test", "Student", "pluginfile").forEach { assertFalse(text, it in text) }
        assertTrue(text, text.endsWith(" failed"))
    }

    @Test
    fun `paths and content links, which can carry a name or a student ID, are hidden`() {
        val text = ViewerErrors.scrub(
            "open failed: /data/user/0/org.ntust.app.tigerduck/cache/moodle/B00015999_hw1.mov (No such file) " +
                "from content://org.ntust.app.tigerduck.fileprovider/cache/x/B00015999_report.pdf, " +
                "/storage/emulated/0/Download/B00015999.mov",
        )

        assertFalse(text, "B00015999" in text)
        assertFalse(text, "/data/user" in text)
        assertFalse(text, "Download" in text)
        assertTrue("the kind of file stays: $text", ".mov" in text)
        assertTrue(text, "content://org.ntust.app.tigerduck.fileprovider/" in text)
    }

    @Test
    fun `the viewed file's own name is hidden, spaces and brackets and all`() {
        val name = "Report (Test Student).pdf"
        val parts = ViewerErrors.privateParts(name, "content://com.android.providers.downloads.documents/document/msf%3A42", "/document/msf:42")

        val text = ViewerErrors.scrub(
            "Missing file for primary:Download/Report (Test Student).pdf at /storage/emulated/0/Download/Report (Test Student).pdf",
            parts,
        )

        assertFalse(text, "Test Student" in text)
        assertFalse(text, "Report" in text)
    }

    @Test
    fun `a track's label with a comma in it is hidden whole`() {
        val labelled = hdrHevc.buildUpon().setLabels(emptyList()).setLabel("Homework, Test Student").build()
        val error = ExoPlaybackException.createForRenderer(
            IllegalStateException("Decoder init failed: c2.mtk.hevc.decoder, $labelled"),
            "MediaCodecVideoRenderer", 0, labelled, C.FORMAT_EXCEEDS_CAPABILITIES, false,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        )

        val text = ViewerErrors.describe(ViewerFailure(error))

        assertFalse(text, "Test Student" in text)
        assertFalse(text, "Homework" in text)
    }

    @Test
    fun `a link that was itself encoded, or carries a login, keeps only its host`() {
        val text = ViewerErrors.scrub(
            "from https%3A%2F%2Fmoodle.example%2FTest%20Student.pdf and https://alice:secret@example.com/document",
        )

        listOf("Test", "Student", "alice", "secret", "document").forEach { assertFalse(text, it in text) }
        assertTrue(text, "example.com" in text)
    }

    @Test
    fun `a file inside an Office document is hidden`() {
        val text = ViewerErrors.scrub("renderer: Error: unknown (inner file : word/media/Test Student.png)")

        assertFalse(text, "Test Student" in text)
        assertTrue(text, "inner file" in text)
    }

    @Test
    fun `a file inside an Office document is hidden whole, brackets and line breaks and all`() {
        val text = ViewerErrors.scrub("renderer: Error: unknown (inner file : word/media/(draft) Test Student.png)")
        val split = ViewerErrors.scrub("renderer: Error: unknown (inner file : word/media/x\nTest Student.png)")

        listOf(text, split).forEach {
            assertFalse(it, "Test Student" in it)
            assertFalse(it, "draft" in it)
            assertTrue(it, "inner file" in it)
        }
    }

    @Test
    fun `a short name in another script is hidden too`() {
        val text = ViewerErrors.scrub("FileNotFoundException: 王小明", ViewerErrors.privateParts("王小明", "content://x/document/1", "/document/1"))

        assertFalse(text, "王小明" in text)
    }

    @Test
    fun `a student ID on its own is hidden too`() {
        assertFalse(ViewerErrors.scrub("for B00015999 only").contains("B00015999"))
    }

    @Test
    fun `a huge message with a deep path is read quickly and cut`() {
        val path = "/" + List(200_000) { "a" }.joinToString("/")

        val started = System.nanoTime()
        val text = ViewerErrors.describe(ViewerFailure(java.io.IOException(path)))

        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 2_000_000_000L)
        assertTrue(text, text.length < 1_000)
    }

    @Test
    fun `a long chain of causes is cut to a screenful`() {
        var error: Throwable = RuntimeException("innermost")
        repeat(30) { error = RuntimeException("level $it", error) }

        val lines = ViewerErrors.describe(ViewerFailure(error)).lines()

        assertTrue("${lines.size} lines", lines.size <= ViewerErrors.MAX_LINES)
    }

    @Test
    fun `the Office page's title says why it failed`() {
        assertEquals("renderer: TypeError: x is undefined", officeFailure("failed: TypeError: x is undefined"))
        assertEquals("the document's renderer failed", officeFailure("failed"))
        assertEquals(null, officeFailure("shown"))
        assertEquals(null, officeFailure(null))
    }
}
