package org.ntust.app.tigerduck.files

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.ui.screen.files.FileViewerActivity
import org.ntust.app.tigerduck.ui.screen.files.lockDown
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FileViewerRoutingTest {

    private val app = RuntimeEnvironment.getApplication()
    private val uri = Uri.parse("content://org.ntust.app.tigerduck.mailfiles/moodle_downloads/fake/B00015999-notes.pdf")
    private var elsewhere = 0

    private fun open(type: String, name: String) = FileViewer.open(app, uri, type, name) {
        elsewhere++
        true
    }

    @Test
    fun `a file the viewer shows opens in it`() {
        assertTrue(open("application/pdf", "B00015999-notes.pdf"))

        val started = shadowOf(app).nextStartedActivity
        assertEquals(FileViewerActivity::class.java.name, started.component?.className)
        assertEquals(uri, started.data)
        assertEquals("application/pdf", started.type)
        assertEquals("B00015999-notes.pdf", started.getStringExtra(FileViewer.EXTRA_NAME))
        assertEquals(ViewerKind.PDF.name, started.getStringExtra(FileViewer.EXTRA_KIND))
        assertEquals(0, elsewhere)
    }

    @Test
    fun `a tar gz moodle sends as application g-zip opens in the archive viewer`() {
        assertTrue(open("application/g-zip", "B00015999-code.tar.gz"))

        val started = shadowOf(app).nextStartedActivity
        assertEquals(FileViewerActivity::class.java.name, started.component?.className)
        assertEquals(ViewerKind.ARCHIVE.name, started.getStringExtra(FileViewer.EXTRA_KIND))
        assertEquals(0, elsewhere)
    }

    @Test
    fun `a file it doesn't show goes where it always went`() {
        assertTrue(open("application/msword", "B00015999-notes.doc"))

        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(1, elsewhere)
    }

    @Test
    fun `a web page never opens in the viewer`() {
        open("text/html", "B00015999-page.html")

        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(1, elsewhere)
    }

    @Test
    fun `open in another app and share hand the file over through the chooser, readable once`() {
        val view = FileViewer.elsewhereIntent(uri, "application/pdf", "notes.pdf")
        val share = FileViewer.shareIntent(uri, "application/pdf", "notes.pdf")

        for ((chooser, action) in listOf(view to Intent.ACTION_VIEW, share to Intent.ACTION_SEND)) {
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION")
            val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(action, inner.action)
            assertEquals(uri, inner.clipData?.getItemAt(0)?.uri)
            assertTrue(inner.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }
    }

    @Test
    fun `the office viewer's web view reaches nothing but its page`() {
        val settings: WebSettings = WebView(app).settings

        lockDown(settings)

        assertTrue(settings.javaScriptEnabled)
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
        assertTrue(settings.blockNetworkLoads)
        assertFalse(settings.domStorageEnabled)
        assertFalse(settings.javaScriptCanOpenWindowsAutomatically)
        assertFalse(settings.supportMultipleWindows())
    }
}
