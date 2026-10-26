package org.ntust.app.tigerduck.ui.screen.files

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.files.FileViewer
import org.ntust.app.tigerduck.files.ViewerKind
import org.ntust.app.tigerduck.files.markedEncrypted
import org.ntust.app.tigerduck.util.forgetFileProviderRoots
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** The archive viewer, on a zip the test makes: its folders, a file that can't be taken out, one that can. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class ArchiveViewerTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val temp = TemporaryFolder()

    // The FileProvider keeps the roots it read first, under another test's data folder.
    @Before
    fun freshFileProvider() = forgetFileProviderRoots()

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun zip(): File = temp.newFile("fake.zip").also { file ->
        ZipArchiveOutputStream(file).use { out ->
            fun put(path: String, text: String) {
                out.putArchiveEntry(ZipArchiveEntry(path))
                out.write(text.toByteArray())
                out.closeArchiveEntry()
            }
            put("Lectures/week 1.txt", "first week")
            put("Lectures/notes.md", "# notes")
            put("readme.txt", "fake")
            put("secret.txt", "not really encrypted")
        }
    }.markedEncrypted("secret.txt")

    private var failed: Int? = -1
    private var elsewhere = 0

    private fun show(file: File, type: String = "application/zip") {
        composeRule.setContent {
            MaterialTheme {
                ArchiveViewer(
                    ViewedFile(Uri.fromFile(file), type, file.name, ViewerKind.ARCHIVE),
                    onFailed = { reason, _ -> failed = reason },
                    onOpenElsewhere = { elsewhere++ },
                )
            }
        }
    }

    private fun waitFor(text: String) =
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `an archive opens on its top folder, and a folder opens and goes back up`() {
        show(zip())
        waitFor("Lectures")
        composeRule.onNodeWithText("readme.txt").assertExists()

        composeRule.onNodeWithText("Lectures").performClick()
        composeRule.onNodeWithText("week 1.txt").assertExists()
        composeRule.onAllNodesWithText("readme.txt").assertCountEquals(0)

        composeRule.onNodeWithContentDescription(string(R.string.file_viewer_archive_up)).performClick()
        composeRule.onNodeWithText("readme.txt").assertExists()
    }

    @Test
    fun `a file that needs a password says so, and offers the archive to another app`() {
        show(zip())
        waitFor("secret.txt")

        composeRule.onNodeWithText("secret.txt").performClick()
        composeRule.onNodeWithText(string(R.string.file_viewer_archive_password)).assertExists()
        composeRule.onNodeWithText(string(R.string.file_viewer_open_elsewhere)).performClick()

        assertEquals(1, elsewhere)
    }

    @Test
    fun `a file tapped is taken out and opens in the viewer as its own kind`() {
        show(zip())
        waitFor("readme.txt")

        composeRule.onNodeWithText("readme.txt").performClick()
        composeRule.waitUntil(10_000) { shadowOf(composeRule.activity).peekNextStartedActivity() != null }

        val started = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(FileViewerActivity::class.java.name, started.component?.className)
        assertEquals(ViewerKind.TEXT.name, started.getStringExtra(FileViewer.EXTRA_KIND))
        assertEquals("readme.txt", started.getStringExtra(FileViewer.EXTRA_NAME))
        assertEquals("content", started.data?.scheme)
        assertTrue(started.data.toString(), started.data.toString().contains("archive_entries"))
        // The file the viewer is handed holds what the archive did, and nothing of its path.
        val taken = File(composeRule.activity.cacheDir, "archive_entries").walkTopDown().single { it.isFile }
        assertEquals("fake", taken.readText())
    }

    @Test
    fun `a tar gz sent as moodle's application g-zip lists, and a file in it opens`() {
        val file = temp.newFile("code.tar.gz")
        GzipCompressorOutputStream(file.outputStream()).use { gzip ->
            TarArchiveOutputStream(gzip, "UTF-8").use { tar ->
                val bytes = "fake".toByteArray()
                tar.putArchiveEntry(TarArchiveEntry("src/notes.txt").apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        show(file, type = "application/g-zip")
        waitFor("src")

        composeRule.onNodeWithText("src").performClick()
        composeRule.onNodeWithText("notes.txt").performClick()
        composeRule.waitUntil(10_000) { shadowOf(composeRule.activity).peekNextStartedActivity() != null }

        val started = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(ViewerKind.TEXT.name, started.getStringExtra(FileViewer.EXTRA_KIND))
    }

    @Test
    fun `a damaged archive fails the viewer, with no reason to give`() {
        val damaged = temp.newFile("damaged.zip").apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(100)) }
        show(damaged)

        composeRule.waitUntil(10_000) { failed != -1 }
        assertEquals(null, failed)
    }

    @Test
    fun `an empty archive says so`() {
        val empty = temp.newFile("empty.zip").also { ZipArchiveOutputStream(it).close() }
        show(empty)

        waitFor(string(R.string.file_viewer_archive_empty))
    }
}
