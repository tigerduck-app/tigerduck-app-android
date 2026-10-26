package org.ntust.app.tigerduck.browser

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator

/** Clearing everything the browser keeps about the student, and the certificate's fingerprint. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BrowserDataCleanerTest {

    @Test
    fun `clearing ends the session, the downloads list, each site's choices and the WebView's stores`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val settings = BrowserSettings(context).apply {
            appearance = BrowserAppearance.DARK
            setZoom("moodle2.ntust.edu.tw", 150)
        }
        val never = CompletableDeferred<android.net.Uri>()
        val downloads = BrowserDownloads(this) { _, _ -> never.await() }
        downloads.start(DownloadPlan.Save("https://moodle2.ntust.edu.tw/a.pdf", "a.pdf", "application/pdf"))
        var storageCleared = 0
        val before = BrowserSession.generation.value

        BrowserDataCleaner(
            storage = { storageCleared++; StorageClearing(inTime = true) },
            settings = settings,
            downloads = downloads,
            cleanup = BrowserCleanup(context),
            scope = backgroundScope,
        ).clear()
        advanceUntilIdle()

        assertEquals(before + 2, BrowserSession.generation.value)
        assertTrue(downloads.items.value.isEmpty())
        assertEquals(BrowserZoom.DEFAULT, settings.zoom("moodle2.ntust.edu.tw"))
        assertEquals(BrowserAppearance.DARK, settings.appearance)
        assertEquals(1, storageCleared)
    }

    @Test
    fun `clearing website data clears the WebView's stores, and the downloads and each site's choices stay`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val settings = BrowserSettings(context).apply { setZoom("moodle2.ntust.edu.tw", 150) }
        val never = CompletableDeferred<android.net.Uri>()
        val downloads = BrowserDownloads(this) { _, _ -> never.await() }
        downloads.start(DownloadPlan.Save("https://moodle2.ntust.edu.tw/a.pdf", "a.pdf", "application/pdf"))
        var storageCleared = 0
        val before = BrowserSession.generation.value

        BrowserDataCleaner(
            storage = { storageCleared++; StorageClearing(inTime = true) },
            settings = settings,
            downloads = downloads,
            cleanup = BrowserCleanup(context),
            scope = backgroundScope,
        ).clearWebsiteData()

        assertEquals(1, storageCleared)
        // The screens open close, as the stores they show go.
        assertEquals(before + 2, BrowserSession.generation.value)
        assertEquals(1, downloads.items.value.size)
        assertEquals(150, settings.zoom("moodle2.ntust.edu.tw"))
        assertFalse(BrowserCleanup(context).isPending())
        never.cancel()
    }

    @Test
    fun `a WebView store that fails to clear does not stop the rest`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val settings = BrowserSettings(context).apply { setDesktopSite("moodle2.ntust.edu.tw", true) }
        val downloads = BrowserDownloads(this) { _, _ -> error("unused") }

        BrowserDataCleaner(
            storage = { throw IllegalStateException("no WebView") },
            settings = settings,
            downloads = downloads,
            cleanup = BrowserCleanup(context),
            scope = backgroundScope,
        ).clear()

        assertEquals(false, settings.desktopSite("moodle2.ntust.edu.tw"))
    }

    @Test
    fun `the fingerprint is SHA-256 over the certificate as colon-separated hex`() {
        // SHA-256 of the three bytes "abc".
        assertEquals(
            "BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD",
            CertificateInfo.fingerprint("abc".toByteArray()),
        )
        // Unrelated input, unrelated output: a stand-in for a certificate's encoding.
        val encoded = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public.encoded
        assertEquals(32 * 3 - 1, CertificateInfo.fingerprint(encoded).length)
    }

    @Test
    fun `clearing is marked while it runs and moves the session as it starts and as it ends`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val downloads = BrowserDownloads(this) { _, _ -> error("unused") }
        val before = BrowserSession.generation.value
        var seenDuring: Pair<Boolean, Int>? = null

        BrowserDataCleaner(
            storage = {
                seenDuring = BrowserSession.clearing.value to BrowserSession.generation.value
                StorageClearing(inTime = true)
            },
            settings = BrowserSettings(context),
            downloads = downloads,
            cleanup = BrowserCleanup(context),
            scope = backgroundScope,
        ).clear()

        assertEquals(true to before + 1, seenDuring)
        assertEquals(before + 2, BrowserSession.generation.value)
        assertEquals(false, BrowserSession.clearing.value)
    }

    @Test
    fun `a deletion that has not reported keeps the browser closed until it does`() = runTest {
        var report: (() -> Unit)? = null
        assertTrue(BrowserSession.usable)

        val inTime = reportsInTime(startDeletion { done -> report = done }, timeoutMs = 5_000)

        assertEquals(false, inTime)
        assertEquals(false, BrowserSession.usable)
        report!!()
        assertTrue(BrowserSession.usable)
    }

    @Test
    fun `a deletion that reports in time leaves the browser usable`() = runTest {
        assertTrue(reportsInTime(startDeletion { done -> done() }, timeoutMs = 5_000))
        assertTrue(BrowserSession.usable)
    }

    @Test
    fun `the clearing is owed on disk until every deletion reports, and a full one settles it`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val cleanup = BrowserCleanup(context)
        val downloads = BrowserDownloads(this) { _, _ -> error("unused") }
        var owedDuring = false

        cleaner(cleanup, downloads) { owedDuring = cleanup.isPending(); StorageClearing(inTime = false) }.clear()
        advanceUntilIdle()

        assertTrue("owed while it runs", owedDuring)
        assertTrue("still owed when a deletion did not report", cleanup.isPending())

        cleaner(cleanup, downloads) { StorageClearing(inTime = true) }.clear()

        assertEquals(false, cleanup.isPending())
    }

    @Test
    fun `a deletion that reports after the clearing gave up on it still settles the clearing`() = runTest {
        val cleanup = BrowserCleanup(RuntimeEnvironment.getApplication())
        val late = CompletableDeferred<Unit>()

        cleaner(cleanup) { StorageClearing(inTime = false) { late.await(); true } }.clear()
        advanceUntilIdle()
        assertTrue("owed while the deletion is under way", cleanup.isPending())

        late.complete(Unit)
        advanceUntilIdle()

        assertEquals(false, cleanup.isPending())
    }

    @Test
    fun `a late report does not settle a clearing an account change asked for since`() = runTest {
        val cleanup = BrowserCleanup(RuntimeEnvironment.getApplication())
        val late = CompletableDeferred<Unit>()
        cleaner(cleanup) { StorageClearing(inTime = false) { late.await(); true } }.clear()

        // A sign-out, say, which the next clearing is for.
        cleanup.markPending()
        late.complete(Unit)
        advanceUntilIdle()

        assertTrue(cleanup.isPending())
    }

    @Test
    fun `an account change while the clearing runs stays owed after it`() = runTest {
        val cleanup = BrowserCleanup(RuntimeEnvironment.getApplication())

        cleaner(cleanup) {
            cleanup.markPending()
            StorageClearing(inTime = true)
        }.clear()

        assertTrue(cleanup.isPending())
    }

    @Test
    fun `a WebView that can never delete everything leaves the clearing owed`() = runTest {
        val cleanup = BrowserCleanup(RuntimeEnvironment.getApplication())

        cleaner(cleanup) { StorageClearing(inTime = false) { false } }.clear()
        advanceUntilIdle()

        assertTrue(cleanup.isPending())
    }

    private fun TestScope.cleaner(
        cleanup: BrowserCleanup,
        downloads: BrowserDownloads = BrowserDownloads(this) { _, _ -> error("unused") },
        storage: WebViewStorage,
    // In the test's own scope, which advanceUntilIdle runs and which waits for the late wait to end.
    ) = BrowserDataCleaner(storage, BrowserSettings(RuntimeEnvironment.getApplication()), downloads, cleanup, this)

    @Test
    fun `the WebView's clearing takes Moodle's session cookie with it`() = runTest(UnconfinedTestDispatcher()) {
        // The clearing hops to the main thread, which is this test's: run that hop here.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val context = RuntimeEnvironment.getApplication()
            val cookies = android.webkit.CookieManager.getInstance()
            cookies.setCookie("https://moodle2.ntust.edu.tw/", "MoodleSession=fakesession000; Path=/; Secure; HttpOnly")
            assertTrue(cookies.getCookie("https://moodle2.ntust.edu.tw/my/").orEmpty().contains("MoodleSession"))

            AndroidWebViewStorage(context).clear()

            assertFalse(cookies.getCookie("https://moodle2.ntust.edu.tw/my/").orEmpty().contains("MoodleSession"))
        } finally {
            Dispatchers.resetMain()
        }
    }
}
