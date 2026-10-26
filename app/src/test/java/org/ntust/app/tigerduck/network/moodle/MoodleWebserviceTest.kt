package org.ntust.app.tigerduck.network.moodle

import kotlinx.coroutines.cancelAndJoin
import okio.buffer
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.MoodleWebserviceError

/** The one call helper every Moodle request goes through: its error mapping and its token handling. */
class MoodleWebserviceTest {

    private class Tokens(var stored: String?, private val fresh: String = "fresh") : MoodleWebservice.TokenSource {
        val renewed = mutableListOf<String?>()
        override fun stored(): String? = stored
        override suspend fun renew(rejected: String?): String {
            renewed += rejected
            stored = fresh
            return fresh
        }
    }

    private val sent = mutableListOf<Request>()

    private fun answering(vararg answers: Triple<Int, String, String?>): OkHttpClient {
        var next = 0
        return OkHttpClient.Builder().addInterceptor { chain ->
            sent += chain.request()
            val (code, body, retryAfter) = answers[minOf(next++, answers.lastIndex)]
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("")
                .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
    }

    private fun ok(body: String) = Triple(200, body, null)

    @Test
    fun `Moodle's error in a 200 throws its own case`() {
        assertThrows(MoodleWebserviceError.SiteMaintenance::class.java) {
            MoodleWebservice.checkAnswer(200, null, """{"errorcode":"sitemaintenance","exception":"moodle_exception"}""")
        }
        assertThrows(MoodleWebserviceError.AccessDenied::class.java) {
            MoodleWebservice.checkAnswer(200, null, """{"errorcode":"accessexception"}""")
        }
        assertThrows(MoodleWebserviceError.InvalidToken::class.java) {
            MoodleWebservice.checkAnswer(200, null, """{"errorcode":"invalidtoken"}""")
        }
    }

    @Test
    fun `the maintenance page is a 503 with Retry-After`() {
        assertThrows(MoodleWebserviceError.SiteMaintenance::class.java) {
            MoodleWebservice.checkAnswer(503, "120", "<html>maintenance</html>")
        }
        val plain = assertThrows(MoodleWebserviceError.HttpStatus::class.java) {
            MoodleWebservice.checkAnswer(503, null, "")
        }
        assertEquals(503, plain.code)
    }

    @Test
    fun `a call posts its parameters as a form under the token`() = runTest {
        val service = MoodleWebservice({ answering(ok("""{"status":true}""")) }, Tokens("t1"))

        service.call("mod_forum_view_forum_discussion", "t1", listOf("discussionid" to "42"))

        val request = sent.single()
        assertEquals("POST", request.method)
        assertEquals("mod_forum_view_forum_discussion", request.url.queryParameter("wsfunction"))
        // The token in the form only: a URL ends up in access logs.
        assertEquals(null, request.url.queryParameter("wstoken"))
        assertEquals(false, "t1" in request.url.toString())
        val form = request.body as FormBody
        assertEquals(
            listOf("wstoken" to "t1", "discussionid" to "42"),
            (0 until form.size).map { form.name(it) to form.value(it) },
        )
    }

    @Test
    fun `an access error is tried once more with the same token and starts no renewal`() = runTest {
        val tokens = Tokens("t1")
        val service = MoodleWebservice({ answering(ok("{}")) }, tokens)
        val used = mutableListOf<String>()

        val error = runCatching {
            service.withToken<Unit> { token ->
                used += token
                throw MoodleWebserviceError.AccessDenied()
            }
        }.exceptionOrNull()

        assertTrue(error is MoodleWebserviceError.AccessDenied)
        assertEquals(listOf("t1", "t1"), used)
        assertTrue(tokens.renewed.isEmpty())
    }

    @Test
    fun `an expired token answers access then invalid, and is renewed once`() = runTest {
        val tokens = Tokens("old")
        val service = MoodleWebservice({ answering(ok("{}")) }, tokens)
        val used = mutableListOf<String>()

        val result = service.withToken { token ->
            used += token
            when (used.size) {
                1 -> throw MoodleWebserviceError.AccessDenied()
                2 -> throw MoodleWebserviceError.InvalidToken()
                else -> "answer with $token"
            }
        }

        assertEquals("answer with fresh", result)
        assertEquals(listOf("old", "old", "fresh"), used)
        assertEquals(listOf<String?>("old"), tokens.renewed)
    }

    @Test
    fun `without a stored token one is obtained first`() = runTest {
        val tokens = Tokens(stored = null)
        val service = MoodleWebservice({ answering(ok("{}")) }, tokens)

        val result = service.withToken { it }

        assertEquals("fresh", result)
        assertEquals(listOf<String?>(null), tokens.renewed)
    }

    @Test
    fun `site info is asked once per token`() = runTest {
        val client = answering(
            ok("""{"userid":7,"functions":[{"name":"mod_forum_get_forums_by_courses"}],"userprivateaccesskey":"key"}"""),
        )
        val service = MoodleWebservice({ client }, Tokens("t1"))

        val first = service.siteInfo("t1")
        val second = service.siteInfo("t1")

        assertEquals(7, first.userId)
        assertEquals(setOf("mod_forum_get_forums_by_courses"), first.functions)
        assertEquals("key", first.privateAccessKey)
        assertEquals(first, second)
        assertEquals(1, sent.size)

        service.siteInfo("t2")
        assertEquals("another token asks again", 2, sent.size)
    }

    @Test
    fun `a downloaded file must be the file Moodle listed`() {
        MoodleWebservice.checkFile(200, "application/pdf; name=a.pdf", "application/pdf")
        MoodleWebservice.checkFile(200, "application/octet-stream", null)
        assertThrows("a sign-in page in its place", MoodleWebserviceError.MalformedResponse::class.java) {
            MoodleWebservice.checkFile(200, "text/html", "application/pdf")
        }
        assertThrows(MoodleWebserviceError.HttpStatus::class.java) {
            MoodleWebservice.checkFile(404, "text/html", "application/pdf")
        }
    }

    private fun downloading(body: okio.Buffer, type: String, length: Long = -1L) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("")
            .body(object : okhttp3.ResponseBody() {
                override fun contentType() = type.toMediaType()
                override fun contentLength() = length
                override fun source(): okio.BufferedSource = body
            })
            .build()
    }.build()

    private val fileRequest = Request.Builder().url("https://moodle2.ntust.edu.tw/tokenpluginfile.php/k/1/a.pdf").build()

    @get:org.junit.Rule
    val tmp = org.junit.rules.TemporaryFolder()

    @Test
    fun `a dead token in a download of unknown length is the token error, so a renewal starts`() = runTest {
        val client = downloading(okio.Buffer().writeUtf8("""{"errorcode":"invalidtoken","exception":"moodle_exception"}"""), "application/json")
        val target = tmp.newFile()

        val error = runCatching {
            MoodleWebservice({ client }, Tokens("t1")).downloadTo(fileRequest, target, "application/pdf", maxBytes = 1_000)
        }.exceptionOrNull()

        assertTrue(error is MoodleWebserviceError.InvalidToken)
    }

    @Test
    fun `a JSON file is kept whole`() = runTest {
        val json = """{"week":1,"topics":["lists","trees"]}"""
        val client = downloading(okio.Buffer().writeUtf8(json), "application/json; charset=utf-8")
        val target = tmp.newFile()

        MoodleWebservice({ client }, Tokens("t1")).downloadTo(fileRequest, target, "application/json", maxBytes = 1_000)

        assertEquals(json, target.readText())
    }

    @Test
    fun `a download larger than allowed stops`() = runTest {
        val client = downloading(okio.Buffer().write(ByteArray(5_000)), "application/pdf")
        val target = tmp.newFile()

        val error = runCatching {
            MoodleWebservice({ client }, Tokens("t1")).downloadTo(fileRequest, target, "application/pdf", maxBytes = 1_000)
        }.exceptionOrNull()

        assertTrue(error is MoodleWebserviceError.MalformedResponse)
    }

    @Test
    fun `a length over the limit stops before the body is read`() = runTest {
        var reads = 0
        val body = object : okio.Source {
            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                reads++
                sink.write(ByteArray(10))
                return 10
            }
            override fun timeout() = okio.Timeout.NONE
            override fun close() = Unit
        }.buffer()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("")
                .body(object : okhttp3.ResponseBody() {
                    override fun contentType() = "application/pdf".toMediaType()
                    override fun contentLength() = 5_000L
                    override fun source(): okio.BufferedSource = body
                })
                .build()
        }.build()
        val target = tmp.newFile()

        val error = runCatching {
            MoodleWebservice({ client }, Tokens("t1")).downloadTo(fileRequest, target, "application/pdf", maxBytes = 1_000)
        }.exceptionOrNull()

        assertTrue("got $error", error is MoodleWebserviceError.MalformedResponse)
        assertEquals("not a byte read", 0, reads)
        assertEquals(0L, target.length())
    }

    @Test
    fun `cancelling a download cancels its request, even with a read stuck on the network`() = kotlinx.coroutines.runBlocking {
        var call: okhttp3.Call? = null
        val stuck = java.util.concurrent.CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            call = chain.call()
            var sent = false
            val stalled = object : okio.Source {
                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                    if (!sent) {
                        sent = true
                        sink.write(ByteArray(10))
                        return 10
                    }
                    // A network that went quiet: the read returns only once the call is cancelled.
                    stuck.countDown()
                    val giveUp = System.nanoTime() + 10_000_000_000L
                    while (!chain.call().isCanceled() && System.nanoTime() < giveUp) Thread.sleep(5)
                    throw java.io.IOException("Canceled")
                }
                override fun timeout() = okio.Timeout.NONE
                override fun close() = Unit
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("")
                .body(object : okhttp3.ResponseBody() {
                    override fun contentType() = "application/pdf".toMediaType()
                    override fun contentLength() = -1L
                    override fun source(): okio.BufferedSource = stalled.buffer()
                })
                .build()
        }.build()
        val target = tmp.newFile()
        var failure: Throwable? = null

        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                MoodleWebservice({ client }, Tokens("t1")).downloadTo(fileRequest, target, "application/pdf", maxBytes = Long.MAX_VALUE)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                failure = e
            }
        }
        assertTrue("the read got stuck", stuck.await(5, java.util.concurrent.TimeUnit.SECONDS))
        kotlinx.coroutines.withTimeout(2_000) { job.cancelAndJoin() }

        assertTrue(call!!.isCanceled())
        assertTrue(job.isCancelled)
        assertEquals("a cancellation, not a network failure", null, failure)
    }

    @Test
    fun `a dead token on a picture download is the token error, charset and all`() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("")
                .header("Content-Type", "application/json; charset=utf-8")
                .body("""{"errorcode":"invalidtoken","exception":"moodle_exception"}""".toResponseBody("application/json; charset=utf-8".toMediaType()))
                .build()
        }.build()

        assertThrows(MoodleWebserviceError.InvalidToken::class.java) {
            MoodleWebservice({ client }, Tokens("t1")).download(fileRequest) { it?.startsWith("image/") == true }
        }
    }

    @Test
    fun `a JSON reply in place of a picture is read only as far as an error needs`() {
        var served = 0L
        val endless = object : okio.Source {
            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                if (served > 1_000_000L) throw AssertionError("read past any error's length")
                val n = minOf(byteCount, 8_192L)
                sink.write(ByteArray(n.toInt()) { ' '.code.toByte() })
                served += n
                return n
            }
            override fun timeout() = okio.Timeout.NONE
            override fun close() = Unit
        }.buffer()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("")
                .body(object : okhttp3.ResponseBody() {
                    override fun contentType() = "application/json".toMediaType()
                    override fun contentLength() = -1L
                    override fun source(): okio.BufferedSource = endless
                })
                .build()
        }.build()

        assertThrows(MoodleWebserviceError.MalformedResponse::class.java) {
            MoodleWebservice({ client }, Tokens("t1")).download(fileRequest) { it?.startsWith("image/") == true }
        }
        assertTrue("read $served", served <= 128 * 1024L)
    }
}
