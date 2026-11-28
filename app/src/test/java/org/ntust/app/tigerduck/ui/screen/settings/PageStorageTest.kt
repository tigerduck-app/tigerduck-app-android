package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.moodle.MoodleFileDownloads
import org.ntust.app.tigerduck.data.storage.AppStorage
import org.ntust.app.tigerduck.data.storage.AppStorage.Kind
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.ui.component.PageSettings
import org.ntust.app.tigerduck.util.replaceIosArg
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A page's own storage, as the iPhone lists it on each page: the page's kinds alone, each with its
 * size and its delete, which asks first exactly where the Storage page asks.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need. Tall enough that the
// list lays out every row.
@Config(application = Application::class, qualifiers = "w400dp-h2000dp")
class PageStorageTest {

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

    private fun description(label: Int) = string(R.string.settings_storage_delete_kind).replaceIosArg(string(label))

    private fun confirm(text: Int) = composeRule.onNode(hasText(string(text)) and hasAnyAncestor(isDialog())).performClick()

    private fun open(page: PageSettings, firstSize: Long) {
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
            MaterialTheme { Column { PageStorageSection(page, viewModel) } }
        }
        composeRule.waitUntil { shows(size(firstSize)) }
    }

    @Test
    fun `each page lists its own kinds, in the iPhone's order`() {
        assertEquals(
            mapOf(
                PageSettings.CLASS_TABLE to listOf(Kind.CLASS_TABLE_IMAGES),
                PageSettings.MAIL to listOf(Kind.MAIL_ATTACHMENTS, Kind.CACHED_MAIL, Kind.DOWNLOADED_MAIL),
                PageSettings.BROWSER to listOf(Kind.WEBSITE_DATA),
                PageSettings.MOODLE to listOf(Kind.MOODLE_FILES),
            ),
            PageSettings.entries.associateWith { it.storageKinds }.filterValues { it.isNotEmpty() },
        )
        // Every kind on one page, and a page with storage has its Storage row to search for.
        assertEquals(Kind.entries.toSet(), PageSettings.entries.flatMap { it.storageKinds }.toSet())
        assertEquals(
            listOf(SettingsRow.CLASS_TABLE_STORAGE, SettingsRow.MAIL_STORAGE, SettingsRow.WEBSITE_DATA, SettingsRow.MOODLE_STORAGE),
            PageSettings.entries.filter { it.storageKinds.isNotEmpty() }.map { page ->
                page.rows.single { it.title == R.string.settings_storage_title }
            },
        )
    }

    @Test
    fun `School Mail's page shows its three kinds and nothing else's`() {
        open(PageSettings.MAIL, 7_000_000)

        composeRule.onNodeWithText(string(R.string.settings_storage_title)).assertExists()
        for ((label, bytes) in listOf(
            R.string.settings_downloaded_files_mail to 300_000L,
            R.string.school_mail_cache_size to 20_000L,
            R.string.school_mail_storage_title to 7_000_000L,
        )) {
            composeRule.onNodeWithText(string(label)).assertExists()
            composeRule.onNodeWithText(size(bytes)).assertExists()
        }
        composeRule.onNodeWithText(string(R.string.school_mail_storage_footer)).assertExists()
        listOf(
            R.string.settings_downloaded_files_moodle, R.string.settings_downloaded_files_images,
            R.string.settings_storage_browser_data, R.string.settings_storage_total, R.string.settings_downloaded_files_delete_all,
        ).forEach { composeRule.onNodeWithText(string(it)).assertDoesNotExist() }
    }

    @Test
    fun `the mail's attachments and cache clear at once, and the mail downloaded for search asks first`() {
        open(PageSettings.MAIL, 7_000_000)

        composeRule.onNodeWithContentDescription(description(R.string.settings_downloaded_files_mail)).performClick()
        composeRule.waitUntil { !attachment.exists() }
        composeRule.onNodeWithText(string(R.string.school_mail_clear_cache)).performClick()
        composeRule.waitForIdle()
        assertEquals(1, cachedMail.clears)

        composeRule.onNodeWithContentDescription(description(R.string.school_mail_storage_title)).performClick()
        composeRule.onNodeWithText(string(R.string.school_mail_storage_delete_title)).assertExists()
        assertEquals(0, downloadedMail.clears)
        confirm(R.string.school_mail_delete)
        composeRule.waitForIdle()

        assertEquals(1, downloadedMail.clears)
        assertTrue(moodleFile.exists() && image.exists())
        assertEquals(0, websiteData.clears)
    }

    @Test
    fun `the browser's website data asks first, and cancel keeps it`() {
        open(PageSettings.BROWSER, 400_000)
        // Where the browser's downloads go instead, as the Storage page says.
        composeRule.onNodeWithText(string(R.string.settings_downloaded_files_footer)).assertExists()

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
    fun `Moodle's files delete at once, alone`() {
        open(PageSettings.MOODLE, 1_000_000)
        composeRule.onNodeWithContentDescription(description(R.string.settings_downloaded_files_moodle)).performClick()
        composeRule.waitUntil { !moodleFile.exists() }
        assertTrue(attachment.exists() && image.exists())
    }

    @Test
    fun `the class table's images delete at once, alone`() {
        open(PageSettings.CLASS_TABLE, 50_000)
        composeRule.onNodeWithContentDescription(description(R.string.settings_downloaded_files_images)).performClick()
        composeRule.waitUntil { !image.exists() }
        assertTrue(attachment.exists() && moodleFile.exists())
    }
}
