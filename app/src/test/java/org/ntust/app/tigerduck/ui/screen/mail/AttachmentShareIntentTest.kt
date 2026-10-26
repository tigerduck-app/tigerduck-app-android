package org.ntust.app.tigerduck.ui.screen.mail

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.util.forgetFileProviderRoots
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AttachmentShareIntentTest {

    @Before
    fun freshFileProvider() = forgetFileProviderRoots()
    @Test
    fun `an attachment goes to the share sheet as a read-only stream of its own type`() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "mail/attachments/slot/notes.pdf").apply {
            parentFile!!.mkdirs()
            writeText("pdf")
        }
        val chooser = attachmentShareIntent(context, SchoolMailMessageViewModel.OpenRequest(file, "application/pdf"))
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/pdf", send.type)
        @Suppress("DEPRECATION")
        val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", stream.scheme)
        assertTrue(stream.toString().endsWith("notes.pdf"))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        // Read-only: the app picked can read the attachment, never write to the app's copy of it.
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(0, chooser.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(stream, send.clipData!!.getItemAt(0).uri)
    }
}
