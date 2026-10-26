package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.CompletableDeferred
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
import org.ntust.app.tigerduck.browser.BrowserCleanup
import org.ntust.app.tigerduck.network.moodle.MoodleAutologin
import org.ntust.app.tigerduck.ui.AppProcessing
import org.ntust.app.tigerduck.ui.screen.browser.InAppBrowser
import org.ntust.app.tigerduck.ui.screen.browser.InAppBrowserActivity
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
        val browser = ComponentName(pkg, "$pkg.Browser")
        val packages = shadowOf(app.packageManager)
        packages.addActivityIfNotPresent(browser)
        packages.addIntentFilterForActivity(
            browser,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
            },
        )
    }

    private var asked = mutableListOf<String>()
    private val processing = AppProcessing()

    private fun pages(answer: String? = signed) = pages { url, _ ->
        asked += url
        answer
    }

    private fun pages(
        scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
        signIn: suspend (String, (Boolean) -> Unit) -> String?,
    ) = SignedInMoodleWebPages(
        signIn = signIn,
        browserPreference = { "system" },
        scope = scope,
        processing = processing,
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

    // --- the app-wide spinner while an open waits on its sign-in ---

    @Test
    fun `the spinner is held while an open reports a wait, and let go before the page opens`() = runTest {
        installBrowser()
        val gate = CompletableDeferred<Unit>()
        var heldWhileWaiting = -1
        val scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        pages(scope) { _, waiting ->
            waiting(true)
            gate.await()
            heldWhileWaiting = processing.holds.value
            waiting(false)
            signed
        }.open(context, page, "system")
        assertEquals(1, processing.holds.value)
        assertTrue(started.isEmpty())

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(1, heldWhileWaiting)
        assertEquals(0, processing.holds.value)
        assertEquals(signed, started.single().dataString)
    }

    @Test
    fun `an open that reports no wait never holds the spinner`() = runTest {
        installBrowser()
        var held = -1

        pages { _, _ ->
            held = processing.holds.value
            signed
        }.open(context, page, "system")

        assertEquals(0, held)
        assertEquals(0, processing.holds.value)
    }

    @Test
    fun `the spinner is let go when the sign-in ends without saying so`() = runTest {
        installBrowser()

        pages { _, waiting ->
            waiting(true)
            null
        }.open(context, page, "system")

        assertEquals(0, processing.holds.value)
        assertEquals(page, started.single().dataString)
    }

    @Test
    fun `the spinner is let go when the sign-in fails or is cancelled`() = runTest {
        installBrowser()
        val failing = kotlinx.coroutines.CoroutineScope(
            UnconfinedTestDispatcher(testScheduler) + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> },
        )
        pages(failing) { _, waiting ->
            waiting(true)
            throw IllegalStateException("boom")
        }.open(context, page, "system")
        assertEquals(0, processing.holds.value)

        val gate = CompletableDeferred<Unit>()
        val cancelled = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler) + kotlinx.coroutines.Job())
        pages(cancelled) { _, waiting ->
            waiting(true)
            gate.await()
            signed
        }.open(context, page, "system")
        assertEquals(1, processing.holds.value)

        cancelled.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        testScheduler.advanceUntilIdle()

        assertEquals(0, processing.holds.value)
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
        openMoodlePage(context, "/mod/assign/view.php?id=890", inMoodleApp = { true }) { recording }

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

        openMoodlePage(noMoodleApp, "/mod/assign/view.php?id=890", inMoodleApp = { true }) { recording }

        assertEquals(listOf("https://moodle2.ntust.edu.tw/mod/assign/view.php?id=890" to null), routed)
        assertTrue(started.isEmpty())
    }

    // --- the TigerDuck browser first ---

    private val inApp = mutableListOf<String>()

    private fun inAppFirst(opens: Boolean) = InAppFirstMoodleWebPages(
        openInApp = { _, url -> inApp += url; opens },
        fallback = recording,
    )

    @Test
    fun `a Moodle page opens in the TigerDuck browser whatever the Browser setting says`() {
        listOf("system", "inApp", null).forEach { inAppFirst(opens = true).open(context, page, it) }

        assertEquals(List(3) { page }, inApp)
        assertTrue("nothing opens it elsewhere", routed.isEmpty())
        assertTrue(started.isEmpty())
    }

    @Test
    fun `where the TigerDuck browser declines, the page opens as before, by the setting`() {
        inAppFirst(opens = false).open(context, page, "system")

        assertEquals(listOf(page), inApp)
        assertEquals(listOf(page to "system"), routed)
    }

    @Test
    fun `a page off the Moodle site never goes to the TigerDuck browser this way`() {
        listOf("https://example.com/course/view.php?id=5", "https://moodle2.ntust.edu.tw.evil.example/x", "nonsense").forEach {
            inAppFirst(opens = true).open(context, it, "system")
        }

        assertTrue(inApp.isEmpty())
        assertEquals(3, routed.size)
    }

    @Test
    fun `without the Moodle app, Open in Moodle opens the TigerDuck browser`() {
        InAppBrowser.canClearEverything = { true }
        BrowserCleanup(app).run { markDone(markPending()) }
        try {
            val noMoodleApp = object : ContextWrapper(app) {
                override fun startActivity(intent: Intent) {
                    if (intent.data?.scheme == "moodlemobile") throw ActivityNotFoundException()
                    started += intent
                }
            }

            openMoodlePage(noMoodleApp, "/course/view.php?id=5", inMoodleApp = { true }) { MoodleWebPages.inAppFirst(fallback = recording) }

            assertEquals(InAppBrowserActivity::class.java.name, started.single().component?.className)
            assertEquals(page, started.single().getStringExtra(InAppBrowser.EXTRA_URL))
            assertTrue(routed.isEmpty())
        } finally {
            InAppBrowser.canClearEverything = { false }
        }
    }

    @Test
    fun `while the browser's clearing is owed, Open in Moodle opens the page as before`() {
        InAppBrowser.canClearEverything = { true }
        BrowserCleanup(app).markPending()
        try {
            MoodleWebPages.inAppFirst(fallback = recording).open(context, page, null)

            assertTrue(started.isEmpty())
            assertEquals(listOf(page to null), routed)
        } finally {
            BrowserCleanup(app).run { markDone(markPending()) }
            InAppBrowser.canClearEverything = { false }
        }
    }
}
