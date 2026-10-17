package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.network.moodle.MoodleAutologin
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Where a Moodle page goes: signed in only to the default browser, and as a plain link otherwise. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MoodleWebPagesTest {

    private val app = RuntimeEnvironment.getApplication()
    private val page = "https://moodle2.ntust.edu.tw/course/view.php?id=5"
    private val signed = "https://moodle2.ntust.edu.tw/admin/tool/mobile/autologin.php?userid=7&key=onetime&urltogo=x"

    private val started = mutableListOf<Intent>()
    private val context = object : ContextWrapper(app) {
        override fun startActivity(intent: Intent) {
            started += intent
        }

        override fun startActivity(intent: Intent, options: android.os.Bundle?) {
            started += intent
        }
    }

    private fun installBrowser(pkg: String = "com.example.browser") {
        val probe = Intent(Intent.ACTION_VIEW, "https://example.com/".toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = "$pkg.Browser"
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(probe, info)
    }

    private var asked = mutableListOf<String>()

    private fun pages(answer: String? = signed) = SignedInMoodleWebPages(
        signIn = {
            asked += it
            answer
        },
        browserPreference = { "system" },
        scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
        main = Dispatchers.Unconfined,
    )

    @Test
    fun `a signed-in page goes to the default browser only, never to whatever claims the link`() = runTest {
        installBrowser()

        pages().open(context, page, "system")

        assertEquals(listOf(page), asked)
        val intent = started.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(signed, intent.dataString)
        assertEquals("com.example.browser", intent.`package`)
    }

    @Test
    fun `in the app's browser it is a Custom Tab of the default browser`() = runTest {
        installBrowser()

        pages().open(context, page, "inApp")

        val intent = started.single()
        assertEquals(signed, intent.dataString)
        assertEquals("com.example.browser", intent.`package`)
        assertTrue(intent.hasExtra("android.support.customtabs.extra.SESSION"))
    }

    @Test
    fun `with no browser to hand it to, no key is asked for and the page opens as a plain link`() = runTest {
        pages().open(context, page, "system")

        assertTrue(asked.isEmpty())
        val intent = started.single()
        assertEquals(page, intent.dataString)
        assertNull(intent.`package`)
    }

    @Test
    fun `a page that gets no key opens as a plain link`() = runTest {
        installBrowser()

        pages(answer = null).open(context, page, "system")

        assertEquals(page, started.single().dataString)
        assertNull(started.single().`package`)
    }

    @Test
    fun `Moodle's sign-in and app-launch pages open as plain links, with no key`() = runTest {
        installBrowser()
        val plain = listOf(
            "https://moodle2.ntust.edu.tw/login/index.php",
            "https://moodle2.ntust.edu.tw/admin/tool/mobile/launch.php?service=moodle_mobile_app",
        )

        plain.forEach { pages().open(context, it, "system") }

        assertTrue(asked.isEmpty())
        assertEquals(plain, started.map { it.dataString })
    }

    @Test
    fun `only an https page on the Moodle site itself is signed in`() {
        assertTrue(MoodleAutologin.isSignInTarget(page))
        assertTrue("a host is the same in capitals", MoodleAutologin.isSignInTarget("https://MOODLE2.NTUST.EDU.TW/course/view.php?id=5"))
        assertFalse(MoodleAutologin.isSignInTarget("http://moodle2.ntust.edu.tw/course/view.php?id=5"))
        assertFalse(MoodleAutologin.isSignInTarget("https://evil.example@moodle2.ntust.edu.tw/course/view.php?id=5"))
        assertFalse(MoodleAutologin.isSignInTarget("https://user:pass@moodle2.ntust.edu.tw/course/view.php?id=5"))
        assertFalse(MoodleAutologin.isSignInTarget("https://moodle2.ntust.edu.tw.evil.example/course/view.php?id=5"))
        assertFalse(MoodleAutologin.isSignInTarget("https://evil.example/?u=https://moodle2.ntust.edu.tw/"))
        assertFalse(MoodleAutologin.isSignInTarget("https://moodle2.ntust.edu.tw:8443/course/view.php?id=5"))
        assertFalse(MoodleAutologin.isSignInTarget("https://moodle2.ntust.edu.tw/login/index.php"))
        assertFalse(MoodleAutologin.isSignInTarget("https://moodle2.ntust.edu.tw/admin/tool/mobile/autologin.php?key=x"))
        assertFalse(MoodleAutologin.isSignInTarget("moodlemobile://https://moodle2.ntust.edu.tw?redirect=/course/view.php"))
    }

    // --- routing ---

    private val routed = mutableListOf<Pair<String, String?>>()
    private val recording = MoodleWebPages { _, url, preference -> routed += url to preference }

    @Test
    fun `a Moodle link inside content goes through the signed-in path, without the web service token`() {
        openMoodleContentLink(context, page, "inApp") { recording }
        openMoodleContentLink(
            context,
            "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/1/mod_forum/attachment/9/a.pdf?token=secret&forcedownload=1",
            "system",
        ) { recording }

        assertEquals(page to "inApp", routed[0])
        assertEquals("https://moodle2.ntust.edu.tw/pluginfile.php/1/mod_forum/attachment/9/a.pdf?forcedownload=1", routed[1].first)
        assertTrue(started.isEmpty())
    }

    @Test
    fun `a link off the Moodle site, or mail, never goes near a key`() {
        openMoodleContentLink(context, "https://example.com/a", "system") { recording }
        openMoodleContentLink(context, "mailto:ta@mail.ntust.example", "system") { recording }

        assertTrue(routed.isEmpty())
        assertEquals(listOf(Intent.ACTION_VIEW, Intent.ACTION_SENDTO), started.map { it.action })
    }

    @Test
    fun `a Moodle page opens in the Moodle app, and nothing else`() {
        openMoodlePage(context, "/mod/assign/view.php?id=890") { recording }

        assertEquals(listOf("moodlemobile://https://moodle2.ntust.edu.tw?redirect=/mod/assign/view.php?id%3D890"), started.map { it.dataString })
        assertTrue("the website is not opened behind the app", routed.isEmpty())
    }

    @Test
    fun `without the Moodle app a Moodle page opens on the website through the signed-in path`() {
        val noMoodleApp = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) {
                if (intent.data?.scheme == "moodlemobile") throw ActivityNotFoundException()
                started += intent
            }
        }

        openMoodlePage(noMoodleApp, "/mod/assign/view.php?id=890") { recording }

        assertEquals(listOf("https://moodle2.ntust.edu.tw/mod/assign/view.php?id=890" to null), routed)
        assertTrue(started.isEmpty())
    }
}
