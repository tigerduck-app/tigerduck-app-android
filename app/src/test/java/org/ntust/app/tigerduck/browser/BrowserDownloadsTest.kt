package org.ntust.app.tigerduck.browser

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/** Which files are saved, the list of downloads, and how a download fetches. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BrowserDownloadsTest {

    private val guess = { url: String, cd: String?, _: String? -> cd?.substringAfter("filename=")?.trim('"') ?: url.substringAfterLast('/') }
    private val mimes = mapOf("pdf" to "application/pdf")

    private fun plan(url: String, cd: String? = null, mime: String? = null, allowlist: BrowserAllowlist = BrowserAllowlist.DEFAULT) =
        BrowserDownloadPlans.plan(url, cd, mime, allowlist, guess) { mimes[it] }

    @Test
    fun `a file on an allowlisted https site is saved under the name and type it comes with`() {
        assertEquals(
            DownloadPlan.Save("https://moodle2.ntust.edu.tw/pluginfile.php/1/a.pdf", "week3.pdf", "application/pdf"),
            plan("https://moodle2.ntust.edu.tw/pluginfile.php/1/a.pdf", cd = "attachment; filename=\"week3.pdf\"", mime = "application/pdf; charset=binary"),
        )
    }

    @Test
    fun `a generic type takes the name's`() {
        assertEquals("application/pdf", (plan("https://moodle2.ntust.edu.tw/x/notes.pdf", mime = "application/octet-stream") as DownloadPlan.Save).mimeType)
        assertEquals("application/octet-stream", (plan("https://moodle2.ntust.edu.tw/x/blob.bin") as DownloadPlan.Save).mimeType)
    }

    @Test
    fun `a file off the allowlist, or not over https, is not saved`() {
        listOf("https://example.com/a.pdf", "http://moodle2.ntust.edu.tw/a.pdf", "blob:https://moodle2.ntust.edu.tw/1", "data:application/pdf;base64,AA==")
            .forEach { assertEquals(it, DownloadPlan.Refused, plan(it)) }
    }

    // ── The list ──────────────────────────────────────────────────────────

    private val save = DownloadPlan.Save("https://moodle2.ntust.edu.tw/a.pdf", "a.pdf", "application/pdf")

    @Test
    fun `a download reports progress and ends done with its file`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val downloads = BrowserDownloads(this) { _, progress ->
            progress(10, 100)
            gate.await()
            progress(100, 100)
            Uri.parse("content://downloads/1")
        }
        val id = downloads.start(save)
        runCurrent()
        assertEquals(BrowserDownload.State.RUNNING, downloads.items.value.single().state)
        assertEquals(10L, downloads.items.value.single().bytes)

        gate.complete(Unit)
        advanceUntilIdle()

        val done = downloads.items.value.single()
        assertEquals(id, done.id)
        assertEquals(BrowserDownload.State.DONE, done.state)
        assertEquals(100L, done.bytes)
        assertEquals(Uri.parse("content://downloads/1"), done.uri)
    }

    @Test
    fun `a failed download says so and a cancelled one stops`() = runTest {
        val never = CompletableDeferred<Uri>()
        val downloads = BrowserDownloads(this) { plan, _ -> if (plan.fileName == "bad.pdf") throw IOException("x") else never.await() }
        downloads.start(save.copy(fileName = "bad.pdf"))
        val running = downloads.start(save)!!
        advanceUntilIdle()

        downloads.cancel(running)
        advanceUntilIdle()

        val states = downloads.items.value.associate { it.fileName to it.state }
        assertEquals(BrowserDownload.State.FAILED, states["bad.pdf"])
        assertEquals(BrowserDownload.State.CANCELLED, states["a.pdf"])
    }

    @Test
    fun `clearing stops what runs and forgets the list`() = runTest {
        val never = CompletableDeferred<Uri>()
        var stopped = false
        val downloads = BrowserDownloads(this) { _, _ ->
            try {
                never.await()
            } finally {
                stopped = true
            }
        }
        downloads.start(save)
        advanceUntilIdle()

        downloads.clear()
        advanceUntilIdle()

        assertTrue(downloads.items.value.isEmpty())
        assertTrue(stopped)
    }

    @Test
    fun `a page from before a clearing starts no download`() = runTest {
        val downloads = BrowserDownloads(this) { _, _ -> CompletableDeferred<Uri>().await() }
        // The departing student's page, not yet gone, asking after the clearing ended.
        val before = BrowserSession.generation.value
        BrowserSession.end()

        assertNull(downloads.start(save, session = before))
        assertTrue(downloads.items.value.isEmpty())
        assertTrue(downloads.start(save, session = BrowserSession.generation.value) != null)
        downloads.clear()
    }

    @Test
    fun `a clearing waits for a download cancelled just before it to finish stopping`() = runTest {
        val never = CompletableDeferred<Uri>()
        val tidied = CompletableDeferred<Unit>()
        var stopped = false
        val downloads = BrowserDownloads(this) { _, _ ->
            try {
                never.await()
            } finally {
                // Closing its request and deleting the unfinished file, which takes a while.
                withContext(NonCancellable) { tidied.await() }
                stopped = true
            }
        }
        val id = downloads.start(save)!!
        advanceUntilIdle()
        downloads.cancel(id)

        val clearing = launch { downloads.clear() }
        advanceUntilIdle()
        val ended = clearing.isCompleted

        tidied.complete(Unit)
        advanceUntilIdle()
        assertFalse("the clearing waits for the cancelled download", ended)
        assertTrue(clearing.isCompleted)
        assertTrue(stopped)
    }

    private fun TestScope.runCurrent() = testScheduler.runCurrent()

    // ── The fetch ─────────────────────────────────────────────────────────

    private class Hop(val code: Int, val location: String? = null, val body: String = "", val setCookies: List<String> = emptyList())

    /** A client answering each URL from [hops], recording each request's cookie. */
    private fun client(
        hops: Map<String, Hop>,
        sent: MutableList<Pair<String, String?>>,
        onRequest: (String) -> Unit = {},
    ) = OkHttpClient.Builder()
        .followRedirects(false)
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
            sent += request.url.toString() to request.header("Cookie")
            onRequest(request.url.toString())
            val hop = hops[request.url.toString()] ?: Hop(404)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(hop.code)
                .message("x")
                .apply {
                    hop.location?.let { header("Location", it) }
                    hop.setCookies.forEach { addHeader("Set-Cookie", it) }
                }
                .body(hop.body.toResponseBody("application/pdf".toMediaType()))
                .build()
        })
        .build()

    private fun transfer(
        hops: Map<String, Hop>,
        sent: MutableList<Pair<String, String?>>,
        saved: MutableList<String>,
        cookies: FakeCookieStore? = null,
        onRequest: (String) -> Unit = {},
    ) = MediaStoreDownloadTransfer(
        context = RuntimeEnvironment.getApplication(),
        allowlist = { BrowserAllowlist.DEFAULT },
        cookieFor = { url -> cookies?.cookieFor(url) ?: if (url.contains("moodle2")) "MoodleSession=abc" else "Other=1" },
        keepCookie = { url, value -> cookies?.keep(url, value) },
        userAgent = { "UA" },
        client = client(hops, sent, onRequest),
        save = { _, _, input, _ ->
            saved += input.readBytes().decodeToString()
            Uri.parse("content://downloads/9")
        },
    )

    @Test
    fun `each hop carries only its own host's cookies`() = runTest(StandardTestDispatcher()) {
        val sent = mutableListOf<Pair<String, String?>>()
        val saved = mutableListOf<String>()
        val hops = mapOf(
            "https://moodle2.ntust.edu.tw/a.pdf" to Hop(303, "https://files.ntust.edu.tw/real.pdf"),
            "https://files.ntust.edu.tw/real.pdf" to Hop(200, body = "PDF"),
        )

        val uri = transfer(hops, sent, saved).download(save) { _, _ -> }

        assertEquals(Uri.parse("content://downloads/9"), uri)
        assertEquals(listOf("PDF"), saved)
        assertEquals(
            listOf("https://moodle2.ntust.edu.tw/a.pdf" to "MoodleSession=abc", "https://files.ntust.edu.tw/real.pdf" to "Other=1"),
            sent,
        )
    }

    /** The browser's cookies, by host, as far as these tests need; [kept] says how many requests had gone when. */
    private class FakeCookieStore(private val sent: List<Pair<String, String?>>) {
        val kept = mutableListOf<String>()
        private val byHost = mutableMapOf("moodle2.ntust.edu.tw" to linkedMapOf("MoodleSession" to "abc"))

        fun keep(url: String, setCookie: String) {
            kept += "after ${sent.size}: $url $setCookie"
            val pair = setCookie.substringBefore(';')
            byHost.getOrPut(url.toHttpUrl().host) { linkedMapOf() }[pair.substringBefore('=')] = pair.substringAfter('=')
        }

        fun cookieFor(url: String): String? =
            byHost[url.toHttpUrl().host]?.entries?.joinToString("; ") { "${it.key}=${it.value}" }?.takeIf { it.isNotEmpty() }
    }

    @Test
    fun `a redirect's cookies go into the browser's, for the address that set them, before the next hop`() = runTest(StandardTestDispatcher()) {
        val sent = mutableListOf<Pair<String, String?>>()
        val store = FakeCookieStore(sent)
        val hops = mapOf(
            "https://moodle2.ntust.edu.tw/a.pdf" to Hop(302, "https://moodle2.ntust.edu.tw/b.pdf", setCookies = listOf("dl=1; Path=/; Secure", "MoodleSession=new; Path=/")),
            "https://moodle2.ntust.edu.tw/b.pdf" to Hop(200, body = "PDF"),
        )

        transfer(hops, sent, mutableListOf(), store).download(save) { _, _ -> }

        assertEquals(
            listOf(
                "after 1: https://moodle2.ntust.edu.tw/a.pdf dl=1; Path=/; Secure",
                "after 1: https://moodle2.ntust.edu.tw/a.pdf MoodleSession=new; Path=/",
            ),
            store.kept,
        )
        assertEquals("https://moodle2.ntust.edu.tw/b.pdf" to "MoodleSession=new; dl=1", sent.last())
    }

    @Test
    fun `a redirect that answers after the sites' data was cleared keeps none of its cookies`() = runTest(StandardTestDispatcher()) {
        val sent = mutableListOf<Pair<String, String?>>()
        val store = FakeCookieStore(sent)
        val hops = mapOf(
            "https://moodle2.ntust.edu.tw/a.pdf" to Hop(302, "https://moodle2.ntust.edu.tw/b.pdf", setCookies = listOf("MoodleSession=old; Path=/")),
            "https://moodle2.ntust.edu.tw/b.pdf" to Hop(200, body = "PDF"),
        )
        // Settings' Storage clears the sites' data, which leaves downloads running, while the
        // first request is out: its answer must not put back a cookie just deleted.
        val clearedMeanwhile = { url: String -> if (url.endsWith("a.pdf")) BrowserSession.end() }

        transfer(hops, sent, mutableListOf(), store, clearedMeanwhile).download(save) { _, _ -> }

        assertEquals(emptyList<String>(), store.kept)
    }

    @Test
    fun `a redirect off the allowlist stops the download before anything goes there`() = runTest(StandardTestDispatcher()) {
        val sent = mutableListOf<Pair<String, String?>>()
        val saved = mutableListOf<String>()
        val hops = mapOf("https://moodle2.ntust.edu.tw/a.pdf" to Hop(302, "https://example.com/steal"))

        try {
            transfer(hops, sent, saved).download(save) { _, _ -> }
            fail("left the allowlist")
        } catch (_: IOException) {
        }

        assertEquals(listOf("https://moodle2.ntust.edu.tw/a.pdf"), sent.map { it.first })
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a server error fails the download and saves nothing`() = runTest(StandardTestDispatcher()) {
        val sent = mutableListOf<Pair<String, String?>>()
        val saved = mutableListOf<String>()
        try {
            transfer(mapOf("https://moodle2.ntust.edu.tw/a.pdf" to Hop(500)), sent, saved).download(save) { _, _ -> }
            fail("500 saved")
        } catch (_: IOException) {
        }
        assertTrue(saved.isEmpty())
        assertNull(sent.firstOrNull { it.first.contains("example") })
    }

    @Test
    fun `a download stopped mid-request cancels the call and asks for nothing more`() = runTest {
        val sent = mutableListOf<String>()
        val inFlight = java.util.concurrent.CountDownLatch(1)
        var call: okhttp3.Call? = null
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor(Interceptor { chain ->
                sent += chain.request().url.toString()
                call = chain.call()
                inFlight.countDown()
                // Waits as a slow server would, until the call is cancelled.
                while (!chain.call().isCanceled()) Thread.sleep(5)
                throw IOException("Canceled")
            })
            .build()
        val transfer = MediaStoreDownloadTransfer(
            context = RuntimeEnvironment.getApplication(),
            allowlist = { BrowserAllowlist.DEFAULT },
            cookieFor = { "MoodleSession=abc" },
            keepCookie = { _, _ -> },
            userAgent = { null },
            client = client,
            save = { _, _, _, _ -> Uri.parse("content://downloads/1") },
        )
        val downloads = BrowserDownloads(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO), transfer)
        downloads.start(save)
        inFlight.await(5, java.util.concurrent.TimeUnit.SECONDS)

        downloads.clear()

        assertTrue(call!!.isCanceled())
        assertEquals(listOf("https://moodle2.ntust.edu.tw/a.pdf"), sent)
        assertTrue(downloads.items.value.isEmpty())
    }

    @Test
    fun `a download stopped while its body is read cancels the call at once`() = runTest {
        val reading = java.util.concurrent.CountDownLatch(1)
        var call: okhttp3.Call? = null
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor(Interceptor { chain ->
                call = chain.call()
                val stalled = object : okio.Source {
                    override fun read(sink: okio.Buffer, byteCount: Long): Long {
                        reading.countDown()
                        // A server that sends nothing more, until the call is cancelled.
                        while (!chain.call().isCanceled()) Thread.sleep(5)
                        throw IOException("Canceled")
                    }
                    override fun timeout() = okio.Timeout.NONE
                    override fun close() {}
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(stalled.buffer().asResponseBody("application/pdf".toMediaType(), -1))
                    .build()
            })
            .build()
        val transfer = MediaStoreDownloadTransfer(
            context = RuntimeEnvironment.getApplication(),
            allowlist = { BrowserAllowlist.DEFAULT },
            cookieFor = { null },
            keepCookie = { _, _ -> },
            userAgent = { null },
            client = client,
            save = { _, _, input, _ ->
                input.readBytes()
                Uri.parse("content://downloads/1")
            },
        )
        val downloads = BrowserDownloads(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO), transfer)
        downloads.start(save)
        assertTrue(reading.await(5, java.util.concurrent.TimeUnit.SECONDS))

        val started = System.nanoTime()
        downloads.clear()

        assertTrue(call!!.isCanceled())
        assertTrue("cleared in time", System.nanoTime() - started < 5_000_000_000L)
    }

    @Test
    fun `nothing is downloaded while the browser is cleared`() = runTest {
        val downloads = BrowserDownloads(this) { _, _ -> error("must not run") }
        BrowserSession.setClearing(true)
        try {
            assertNull(downloads.start(save))
        } finally {
            BrowserSession.setClearing(false)
        }
        advanceUntilIdle()
        assertTrue(downloads.items.value.isEmpty())
    }
}
