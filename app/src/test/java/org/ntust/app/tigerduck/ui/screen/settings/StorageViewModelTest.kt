package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.moodle.MoodleFileDownloads
import org.ntust.app.tigerduck.data.storage.AppStorage
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StorageViewModelTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Stands in for the app's own scope, which outlives every page. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        appScope.cancel()
        Dispatchers.resetMain()
    }

    private val file get() = File(temp.root, "files/moodle_files/a1b2/1234/m/slot/0/Week 1.pdf")

    private fun files(): AppStorage {
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(1_000))
        return AppStorage(
            moodle = MoodleFileDownloads(root = { File(temp.root, "files/moodle_files") }, scope = appScope, io = Dispatchers.Unconfined),
            mailAttachments = { File(temp.root, "cache/mail/attachments") },
            attachmentsInUse = { emptySet() },
            classTableImages = { File(temp.root, "cache/class_table_export") },
            cachedMail = AppStorage.Store(bytes = { 0L }, clear = { }),
            downloadedMail = AppStorage.Store(bytes = { 0L }, clear = { }),
            websiteData = AppStorage.Store(bytes = { 0L }, clear = { }),
            io = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `a confirmed delete still happens when the page is already gone`() {
        val viewModel = StorageViewModel(files(), appScope)
        // Back pressed as the dialog closed: the page's own scope is gone.
        viewModel.viewModelScope.cancel()

        viewModel.clearDownloaded()

        assertFalse(file.exists())
    }

    @Test
    fun `the page open when a delete from an earlier one ends shows it`() {
        val files = files()
        val earlier = StorageViewModel(files, appScope)
        val later = StorageViewModel(files, appScope)
        later.measure()
        assertEquals(1_000L, later.sizes.value?.get(AppStorage.Kind.MOODLE_FILES))

        earlier.clearDownloaded()

        assertEquals(0L, later.sizes.value?.get(AppStorage.Kind.MOODLE_FILES))
    }
}
