package org.ntust.app.tigerduck.browser

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a picker gave back, as far as the page may have it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BrowserUploadPicksTest {

    /** Another app's provider: anything served, and not ours. */
    private val foreign = { authority: String -> !authority.startsWith("org.ntust.app.tigerduck") && authority != "nobody.serves.this" }

    @Test
    fun `another app's content goes to the page`() {
        val photo = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/12")
        val doc = Uri.parse("content://com.android.providers.downloads.documents/document/34")
        assertArrayEquals(arrayOf(photo, doc), BrowserUpload.safePicks(listOf(photo, doc), null, foreign))
    }

    @Test
    fun `the app's own files and file URIs never reach the page`() {
        val ours = Uri.parse("content://org.ntust.app.tigerduck.mailfiles/moodle_downloads/grades.pdf")
        val fdroidOurs = Uri.parse("content://org.ntust.app.tigerduck.fdroid.mailfiles/mail_attachments/a.pdf")
        val file = Uri.parse("file:///data/data/org.ntust.app.tigerduck/shared_prefs/credentials.xml")
        val noAuthority = Uri.parse("content:///x")
        assertNull(BrowserUpload.safePicks(listOf(ours, fdroidOurs, file, noAuthority), null, foreign))
    }

    @Test
    fun `the browser's own camera shot goes, and only that one of ours`() {
        val shot = Uri.parse("content://org.ntust.app.tigerduck.mailfiles/browser_uploads/photo_1.jpg")
        val other = Uri.parse("content://org.ntust.app.tigerduck.mailfiles/browser_uploads/photo_2.jpg")
        assertArrayEquals(arrayOf(shot), BrowserUpload.safePicks(listOf(shot, other), shot, foreign))
    }

    @Test
    fun `an authority with a user in it, or one nothing serves, never reaches the page`() {
        // Android strips "0@" before it opens the provider, so this names one of ours.
        val disguised = Uri.parse("content://0@org.ntust.app.tigerduck.mailfiles/mail_attachments/a.pdf")
        val elsewhere = Uri.parse("content://10@com.example.files/doc/1")
        val unserved = Uri.parse("content://nobody.serves.this/doc/1")
        assertNull(BrowserUpload.safePicks(listOf(disguised, elsewhere, unserved), null) { it != "nobody.serves.this" })
    }
}
