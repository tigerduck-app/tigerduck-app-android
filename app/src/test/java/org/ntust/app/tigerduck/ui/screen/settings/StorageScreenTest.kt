package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.moodle.MoodleFileDownloads
import org.ntust.app.tigerduck.data.storage.AppStorage
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.util.replaceIosArg
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need. Tall enough that the
// list lays out every row.
@Config(application = Application::class, qualifiers = "w400dp-h2000dp")
class StorageScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private val moodleFile get() = File(temp.root, "files/moodle_files/a1b2/1234/m/slot/0/Week 1.pdf")
    private val attachment get() = File(temp.root, "cache/mail/attachments/0b5e/Syllabus.pdf")
    private val image get() = File(temp.root, "cache/class_table_export/4f1c/class table.png")

    /** A store kept as a whole, which counts its clearings. */
    private class FakeStore(var bytes: Long) {
        var clears = 0
        val store = AppStorage.Store(bytes = { bytes }, clear = { clears++; bytes = 0 })
    }

    private val cachedMail = FakeStore(20_000)
    private val downloadedMail = FakeStore(7_000_000)
    private val websiteData = FakeStore(400_000)

    private fun write(file: File, bytes: Int) {
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(bytes))
    }

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun size(bytes: Long) = Formatter.formatFileSize(composeRule.activity, bytes)

    private fun shows(text: String) = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun openPage() {
        write(moodleFile, 1_000_000)
        write(attachment, 300_000)
        write(image, 50_000)
        val storage = AppStorage(
            moodle = MoodleFileDownloads(root = { File(temp.root, "files/moodle_files") }, scope = scope, io = Dispatchers.Unconfined),
            mailAttachments = { MailCache(File(temp.root, "cache/mail")).attachmentsDir },
            attachmentsInUse = { emptySet() },
            classTableImages = { File(temp.root, "cache/class_table_export") },
            cachedMail = cachedMail.store,
            downloadedMail = downloadedMail.store,
            websiteData = websiteData.store,
            now = { System.currentTimeMillis() + 60 * 60 * 1000L },
            io = Dispatchers.Unconfined,
        )
        val viewModel = StorageViewModel(storage, scope)
        composeRule.setContent {
            MaterialTheme { StorageScreen(onBack = {}, viewModel = viewModel) }
        }
        composeRule.waitUntil { shows(size(8_770_000)) }
    }

    private fun description(label: Int) =
        string(R.string.settings_storage_delete_kind).replaceIosArg(string(label))

    private fun confirm(text: Int) =
        composeRule.onNode(hasText(string(text)) and hasAnyAncestor(isDialog())).performClick()

    @Test
    fun `the page shows the total and what each thing takes`() {
        openPage()

        for ((label, bytes) in listOf(
            R.string.settings_downloaded_files_moodle to 1_000_000L,
            R.string.settings_downloaded_files_mail to 300_000L,
            R.string.settings_downloaded_files_images to 50_000L,
            R.string.school_mail_cache_size to 20_000L,
            R.string.school_mail_storage_title to 7_000_000L,
            R.string.settings_storage_browser_data to 400_000L,
        )) {
            composeRule.onNodeWithText(string(label)).assertExists()
            composeRule.onNodeWithText(size(bytes)).assertExists()
        }
        composeRule.onNodeWithText(string(R.string.school_mail_storage_footer)).assertExists()
    }

    @Test
    fun `a downloaded kind's delete takes only that kind, at once`() {
        openPage()

        composeRule.onNodeWithContentDescription(description(R.string.settings_downloaded_files_moodle)).performClick()
        composeRule.waitUntil { !moodleFile.exists() }

        assertTrue(attachment.exists() && image.exists())
        composeRule.waitUntil { shows(size(7_770_000)) }
    }

    @Test
    fun `deleting every downloaded file asks first, and leaves the mail and the sites' data`() {
        openPage()

        composeRule.onNodeWithText(string(R.string.settings_downloaded_files_delete_all)).performClick()
        composeRule.onNodeWithText(string(R.string.settings_downloaded_files_delete_all_title)).assertExists()
        assertTrue(moodleFile.exists())

        confirm(R.string.action_delete)
        composeRule.waitUntil { !moodleFile.exists() && !attachment.exists() && !image.exists() }

        assertEquals(0, cachedMail.clears + downloadedMail.clears + websiteData.clears)
    }

    @Test
    fun `the mail's cache clears at once`() {
        openPage()

        composeRule.onNodeWithText(string(R.string.school_mail_clear_cache)).performClick()
        composeRule.waitForIdle()

        assertEquals(1, cachedMail.clears)
        assertEquals(0, downloadedMail.clears)
    }

    @Test
    fun `the mail downloaded for search asks first`() {
        openPage()

        composeRule.onNodeWithContentDescription(description(R.string.school_mail_storage_title)).performClick()
        composeRule.onNodeWithText(string(R.string.school_mail_storage_delete_title)).assertExists()
        assertEquals(0, downloadedMail.clears)

        confirm(R.string.school_mail_delete)
        composeRule.waitForIdle()

        assertEquals(1, downloadedMail.clears)
    }

    @Test
    fun `the browser's website data asks first, and cancel keeps it`() {
        openPage()

        composeRule.onNodeWithContentDescription(description(R.string.settings_storage_browser_data)).performClick()
        composeRule.onNodeWithText(string(R.string.settings_storage_browser_data_delete_title)).assertExists()
        composeRule.onNodeWithText(string(R.string.action_cancel)).performClick()
        composeRule.waitForIdle()
        assertEquals(0, websiteData.clears)

        composeRule.onNodeWithContentDescription(description(R.string.settings_storage_browser_data)).performClick()
        confirm(R.string.action_delete)
        composeRule.waitForIdle()

        assertEquals(1, websiteData.clears)
    }

    @Test
    fun `cancel leaves the downloaded files`() {
        openPage()

        composeRule.onNodeWithText(string(R.string.settings_downloaded_files_delete_all)).performClick()
        composeRule.onNodeWithText(string(R.string.action_cancel)).performClick()
        composeRule.waitForIdle()

        assertFalse(!moodleFile.exists() || !attachment.exists() || !image.exists())
    }
}
