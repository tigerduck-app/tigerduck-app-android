package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.ui.screen.files.FileViewerActivity
import org.ntust.app.tigerduck.util.forgetFileProviderRoots
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/** What leaves the app when an attachment opens: a content URI to the copy in the app, never a link with a key. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MoodleFileIntentTest {

    @Before
    fun freshFileProvider() = forgetFileProviderRoots()

    /**
     * One test for everything the FileProvider decides: it keeps the roots it read the first time
     * for the rest of the process, and each Robolectric test has an app folder of its own.
     */
    @Test
    fun `an attachment leaves as a content URI, a web page or XML through the share sheet, nothing else at all`() {
        val context = RuntimeEnvironment.getApplication()
        fun file(path: String) = File(context.filesDir, path).apply {
            parentFile!!.mkdirs()
            writeText("x")
        }

        val chooser = moodleFileIntent(context, file("moodle_files/a1/5/p90/s1/1/hw3.pdf"), "application/pdf")
        // The system's own chooser, which says so itself when no app takes the file.
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertNull("no title of the app's own", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE))
        @Suppress("DEPRECATION")
        val view = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals("the grant rides the wrapped intent's clip through the chooser", view.data, view.clipData!!.getItemAt(0).uri)
        assertEquals("and the chooser carries it on", view.data, chooser.clipData!!.getItemAt(0).uri)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("content", view.data!!.scheme)
        assertEquals("application/pdf", view.type)
        assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(view.data.toString().let { it.contains("token") || it.contains("pluginfile") })

        val scripted = listOf(
            "notes.html" to "text/html", "page.xhtml" to "application/xhtml+xml", "logo.svg" to "image/svg+xml",
            "feed.xml" to "text/xml", "data.xml" to "application/xml", "news.rss" to "application/rss+xml",
            "upper.html" to "Text/HTML; charset=utf-8",
        )
        for ((name, type) in scripted) {
            val shareChooser = moodleFileIntent(context, file("moodle_files/a1/5/p90/s1/1/$name"), type)
            @Suppress("DEPRECATION")
            val shared = shareChooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(name, Intent.ACTION_SEND, shared.action)
            assertEquals(name, Intent.normalizeMimeType(type), shared.type)
            @Suppress("DEPRECATION")
            val stream = shared.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content", stream.scheme)
            assertEquals(stream, shared.clipData!!.getItemAt(0).uri)
            assertTrue(shared.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(stream, shareChooser.clipData!!.getItemAt(0).uri)
            assertTrue(shareChooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }

        // Listed as anything, a copy whose name says web page is a web page to the app that reads it.
        val named = moodleFileIntent(
            context, file("moodle_files/a1/5/p90/s1/1/a_html.html"), "application/octet-stream",
        ) { if (it == "html") "text/html" else null }
        @Suppress("DEPRECATION")
        val namedShared = named.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, namedShared.action)
        assertEquals("text/html", namedShared.type)

        // A saved web page the system has no type for goes to the share sheet by its name alone, as iOS sends it.
        for (name in listOf("page.mht", "page.mhtml", "logo.SVG")) {
            val archived = moodleFileIntent(context, file("moodle_files/a1/5/p90/s1/1/$name"), "application/octet-stream") { null }
            @Suppress("DEPRECATION")
            assertEquals(name, Intent.ACTION_SEND, archived.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!.action)
        }

        // Share sends any file to the share sheet, the copy and never a link.
        val asked = moodleFileIntent(context, file("moodle_files/a1/5/p90/s1/1/hw5.pdf"), "application/pdf", share = true)
        @Suppress("DEPRECATION")
        val sent = asked.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, sent.action)
        assertEquals("application/pdf", sent.type)
        @Suppress("DEPRECATION")
        assertEquals("content", sent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!.scheme)

        ShadowLog.clear()
        val noApp = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) = throw ActivityNotFoundException("week1-s1234567.pdf")
        }
        assertFalse(launchMoodleFile(noApp, file("moodle_files/a1/5/p90/s1/1/week1-s1234567.pdf"), "application/pdf"))
        val line = ShadowLog.getLogsForTag("MoodleLinks").single()
        assertEquals(Log.WARN, line.type)
        assertEquals("logged by the failure's class alone", "attachment not opened: ActivityNotFoundException", line.msg)
        assertNull(line.throwable)
        var launched: Intent? = null
        val viewer = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { launched = intent }
        }
        // A PDF shows in the app's own viewer; a file it doesn't show goes to the chooser as before.
        assertTrue(launchMoodleFile(viewer, file("moodle_files/a1/5/p90/s1/1/hw4.pdf"), "application/pdf"))
        assertEquals(FileViewerActivity::class.java.name, launched!!.component?.className)
        assertTrue(launchMoodleFile(viewer, file("moodle_files/a1/5/p90/s1/1/hw4.doc"), "application/msword"))
        assertEquals(Intent.ACTION_CHOOSER, launched.action)

        val cached = File(context.cacheDir, "TigerDuckCache/moodle/B1/courses/5/grades.json").apply {
            parentFile!!.mkdirs()
            writeText("{}")
        }
        val refused = runCatching { moodleFileIntent(context, cached, "application/json") }.exceptionOrNull()
        assertTrue("got $refused", refused is IllegalArgumentException)
    }

    @Test
    fun `a file the app cannot hand out is logged, not launched`() {
        ShadowLog.clear()
        var launched = false
        val viewer = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startActivity(intent: Intent) { launched = true }
        }

        // Outside the app's folder for opened files, so the FileProvider refuses it.
        assertFalse(launchMoodleFile(viewer, File("/data/week1-s1234567.pdf"), "application/pdf"))

        assertFalse(launched)
        val line = ShadowLog.getLogsForTag("MoodleLinks").single()
        assertEquals(Log.WARN, line.type)
        assertEquals("attachment not opened: IllegalArgumentException", line.msg)
    }

    @Test
    fun `Moodle's unknown document type is read from the file name`() {
        assertEquals("application/pdf", moodleFileType("document/unknown", "week1.pdf") { if (it == "pdf") "application/pdf" else null })
        assertEquals("application/pdf", moodleFileType(null, "week1.pdf") { if (it == "pdf") "application/pdf" else null })
        assertEquals("text/plain", moodleFileType("text/plain", "readme") { null })
    }
}
