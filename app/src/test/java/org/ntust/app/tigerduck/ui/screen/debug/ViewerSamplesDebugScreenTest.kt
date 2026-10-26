package org.ntust.app.tigerduck.ui.screen.debug

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.files.FileViewer
import org.ntust.app.tigerduck.files.ViewerKind
import org.ntust.app.tigerduck.ui.screen.files.FileViewerActivity
import org.ntust.app.tigerduck.util.forgetFileProviderRoots
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app this test does not need.
@Config(application = Application::class)
class ViewerSamplesDebugScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val assets = File("src/debug/assets/viewer-samples")

    @Before
    fun freshFileProvider() = forgetFileProviderRoots()

    private fun show() {
        composeRule.setContent { MaterialTheme { ViewerSamplesDebugScreen(onBack = {}) } }
        composeRule.waitForIdle()
    }

    private fun tap(name: String): Intent {
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(name))
        composeRule.onNodeWithText(name).performClick()
        composeRule.waitUntil(10_000) { shadowOf(composeRule.activity).peekNextStartedActivity() != null }
        return shadowOf(composeRule.activity).nextStartedActivity
    }

    private fun copied(name: String) = File(composeRule.activity.cacheDir, "viewer_samples/bundled/$name")

    @Test
    fun `the page lists the samples and offers a file on the phone`() {
        show()

        composeRule.onNodeWithText("Open a file on this phone").assertExists()
        composeRule.onNodeWithText("h264.mp4").assertExists()
    }

    @Test
    fun `a video sample opens in the viewer, as a Moodle file of its name and type would`() {
        show()

        val started = tap("hevc-main10-4k60.mov")

        assertEquals(FileViewerActivity::class.java.name, started.component?.className)
        assertEquals(ViewerKind.VIDEO.name, started.getStringExtra(FileViewer.EXTRA_KIND))
        assertEquals("hevc-main10-4k60.mov", started.getStringExtra(FileViewer.EXTRA_NAME))
        assertEquals("video/quicktime", started.type)
        assertTrue(started.data.toString(), started.data.toString().contains("viewer_samples"))
        assertArrayEquals(File(assets, "hevc-main10-4k60.mov").readBytes(), copied("hevc-main10-4k60.mov").readBytes())
    }

    @Test
    fun `a gzip sample opens under its own name, from the copy stored apart`() {
        show()

        val started = tap("archive.tar.gz")

        assertEquals(ViewerKind.ARCHIVE.name, started.getStringExtra(FileViewer.EXTRA_KIND))
        assertEquals("archive.tar.gz", started.getStringExtra(FileViewer.EXTRA_NAME))
        assertArrayEquals(File(assets, "archive.tar.gz.sample").readBytes(), copied("archive.tar.gz").readBytes())
    }

    @Test
    fun `a web page goes to the share sheet, as from Moodle`() {
        show()

        val started = tap("page.html")

        assertEquals(Intent.ACTION_CHOOSER, started.action)
        @Suppress("DEPRECATION")
        val inner = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, inner.action)
    }
}
